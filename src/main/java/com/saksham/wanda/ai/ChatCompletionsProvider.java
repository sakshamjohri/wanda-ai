package com.saksham.wanda.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.saksham.wanda.exception.AIException;
import com.saksham.wanda.model.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Base class for AI services that speak the OpenAI-compatible "chat completions" format.
 * {@link GroqProvider} supplies the endpoint and models; another compatible service
 * could be added by writing one more small subclass.
 *
 * Replies can be requested in one piece ({@link #reply}) or streamed token by token
 * ({@link #stream}) using server-sent events. Pictures from the newest picture message
 * are sent as {@code image_url} parts.
 *
 * @author Saksham Johri
 */
public abstract class ChatCompletionsProvider implements AIProvider {

    /** Optional override of every endpoint, used to point the app at a proxy or a local test server. */
    private static final String ENDPOINT_OVERRIDE = System.getenv("AI_API_URL");

    /** A streamed reply is cut off after this long, so a stalled connection cannot hang a request. */
    private static final long STREAM_LIMIT_SECONDS = 150;

    // HttpClient is thread-safe and meant to be shared.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** Closes stalled streams (one shared daemon thread). */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread t = new Thread(task, "wanda-stream-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** Full URL of the provider's chat-completions endpoint. */
    protected abstract String endpoint();

    /** Model used when the user has not chosen one (or chose one from another provider). */
    protected abstract String defaultModel();

    /** Model used when the message includes pictures. */
    protected abstract String visionModel();

    /** True if a model id belongs to this provider. */
    protected abstract boolean supportsModel(String model);

    /** Optional extra JSON fields for a model (for example its reasoning settings); null for none. */
    protected JsonObject extraParams(String model, String effort) {
        return null;
    }

    /** Picks the model for one request: vision model for pictures, else the user's choice or the default. */
    protected String resolveModel(String requested, boolean hasImages) {
        if (hasImages) {
            return visionModel();
        }
        return requested == null || requested.isBlank() || !supportsModel(requested) ? defaultModel() : requested;
    }

    /* ------------------------------------------------------------ one piece */

    @Override
    public String reply(ChatRequest req) throws AIException {
        try {
            HttpResponse<InputStream> res = send(req, false);
            String body = readAll(res.body());
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonElement content = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content");
            if (content == null || content.isJsonNull() || content.getAsString().isBlank()) {
                throw new AIException(name() + " returned an empty reply. Please try again.");
            }
            return content.getAsString();
        } catch (IOException e) {
            throw new AIException("Could not reach " + name() + ". Check your internet connection.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIException("Request was interrupted", e);
        } catch (JsonParseException | IllegalStateException | NullPointerException e) {
            throw new AIException("Unexpected response from " + name(), e);
        }
    }

    /* ------------------------------------------------------------ streaming */

    @Override
    public String stream(ChatRequest req, Consumer<String> onDelta) throws AIException {
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> cutOff = null;
        try {
            HttpResponse<InputStream> res;
            try {
                res = send(req, true);
            } catch (IOException e) {
                throw new AIException("Could not reach " + name() + ". Check your internet connection.", e);
            }
            InputStream body = res.body();
            cutOff = WATCHDOG.schedule(() -> {
                timedOut.set(true);
                try { body.close(); } catch (IOException ignored) { /* closing to unblock the reader */ }
            }, STREAM_LIMIT_SECONDS, TimeUnit.SECONDS);

            StringBuilder all = new StringBuilder();
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                for (String line; (line = lines.readLine()) != null; ) {
                    if (!line.startsWith("data:")) {
                        continue; // blank separators and comments
                    }
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    String piece = deltaText(data);
                    if (!piece.isEmpty()) {
                        all.append(piece);
                        onDelta.accept(piece);
                    }
                }
            }
            if (all.toString().isBlank()) {
                throw new AIException(name() + " returned an empty reply. Please try again.");
            }
            return all.toString();
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new AIException("The model took too long to respond. Please try again.", e);
            }
            throw new AIException("The connection to " + name() + " was interrupted. Please try again.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIException("Request was interrupted", e);
        } finally {
            if (cutOff != null) {
                cutOff.cancel(false);
            }
        }
    }

    /** Text carried by one streamed chunk ("" for chunks without text); throws if the chunk reports an error. */
    String deltaText(String data) {
        try {
            JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
            if (chunk.has("error")) {
                throw new AIException(name() + " stopped the reply: " + errorText(chunk));
            }
            JsonArray choices = chunk.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return "";
            }
            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
            JsonElement content = delta == null ? null : delta.get("content");
            return content == null || content.isJsonNull() ? "" : content.getAsString();
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new AIException("Unexpected response from " + name(), e);
        }
    }

    /* --------------------------------------------------------------- request */

    /**
     * Sends the request and returns the response if it succeeded; maps failures to friendly errors.
     * Optional model parameters are dropped and the call repeated once if the service rejects them.
     */
    private HttpResponse<InputStream> send(ChatRequest req, boolean stream) throws IOException, InterruptedException {
        String model = resolveModel(req.model(), req.pictureMessage() != null);
        JsonObject extra = extraParams(model, req.effort());
        HttpResponse<InputStream> res = post(req, model, stream, extra);
        if (res.statusCode() == 200) {
            return res;
        }
        String body = readAll(res.body());
        if (res.statusCode() == 400 && extra != null && body.toLowerCase().contains("reasoning")) {
            res = post(req, model, stream, null);
            if (res.statusCode() == 200) {
                return res;
            }
            body = readAll(res.body());
        }
        throw failure(res.statusCode(), body);
    }

    private HttpResponse<InputStream> post(ChatRequest req, String model, boolean stream, JsonObject extra)
            throws IOException, InterruptedException {
        String url = ENDPOINT_OVERRIDE == null || ENDPOINT_OVERRIDE.isBlank() ? endpoint() : ENDPOINT_OVERRIDE;
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + req.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(req, model, stream, extra).toString()))
                .build();
        return http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    }

    private AIException failure(int status, String body) {
        return switch (status) {
            case 401, 403 -> new AIException("Your " + name() + " API key was rejected. Check it in Settings → Model.");
            case 429 -> new AIException(name() + " rate limit reached. Wait a minute and try again.");
            default -> new AIException(name() + " returned " + status + ": " + errorText(body));
        };
    }

    /** Builds the JSON payload: system prompt, history, and the newest pictures. */
    JsonObject buildBody(ChatRequest req, String model, boolean stream, JsonObject extra) {
        JsonArray messages = new JsonArray();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            messages.add(textMessage("system", req.systemPrompt()));
        }
        Message withPictures = req.pictureMessage();
        for (Message m : req.history()) {
            if (m == withPictures) {
                messages.add(messageWithImages(m.promptText(), m.pictureList()));
            } else {
                messages.add(textMessage(m.getRole().apiName(), m.promptText()));
            }
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", req.temperature());
        body.add("messages", messages);
        if (stream) {
            body.addProperty("stream", true);
        }
        if (extra != null) {
            extra.entrySet().forEach(e -> body.add(e.getKey(), e.getValue()));
        }
        return body;
    }

    private static JsonObject textMessage(String role, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", text);
        return o;
    }

    private static JsonObject messageWithImages(String text, List<String> images) {
        JsonArray parts = new JsonArray();
        for (String dataUrl : images) {
            JsonObject url = new JsonObject();
            url.addProperty("url", dataUrl);
            JsonObject part = new JsonObject();
            part.addProperty("type", "image_url");
            part.add("image_url", url);
            parts.add(part);
        }
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", text);
        parts.add(textPart);

        JsonObject o = new JsonObject();
        o.addProperty("role", "user");
        o.add("content", parts);
        return o;
    }

    private static String readAll(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Error text from a response body (error bodies differ between services; may not be JSON at all). */
    private static String errorText(String body) {
        try {
            return errorText(JsonParser.parseString(body).getAsJsonObject());
        } catch (JsonParseException | IllegalStateException e) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    private static String errorText(JsonObject json) {
        // {"error":"..."} or {"error":{"message":"..."}}
        if (json.has("error")) {
            var err = json.get("error");
            if (err.isJsonPrimitive()) {
                return err.getAsString();
            }
            return err.isJsonObject() && err.getAsJsonObject().has("message")
                    ? err.getAsJsonObject().get("message").getAsString() : err.toString();
        }
        return json.toString();
    }
}
