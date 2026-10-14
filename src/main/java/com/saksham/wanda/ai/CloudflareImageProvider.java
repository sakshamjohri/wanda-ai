package com.saksham.wanda.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.saksham.wanda.exception.AIException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Image generation with FLUX.1 schnell on Cloudflare Workers AI. A free Cloudflare account includes
 * a daily allowance (10,000 Neurons per day at the time of writing); when it is used up the service
 * returns errors instead of charging, so it stays free.
 *
 * Needs the account's Account ID and an API token that has the Workers AI permission.
 *
 * @author Saksham Johri
 */
public class CloudflareImageProvider implements ImageProvider {

    private static final String MODEL = "@cf/black-forest-labs/flux-1-schnell";
    /** Optional override of the endpoint, used to point the app at a local test server. */
    private static final String ENDPOINT_OVERRIDE = System.getenv("AI_IMAGE_URL");
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Override
    public String name() {
        return "Cloudflare Workers AI";
    }

    @Override
    public GeneratedImage generate(String prompt, String accountId, String apiToken) throws AIException {
        if (!accountId.matches("[A-Za-z0-9]{8,64}")) {
            throw new AIException("The Cloudflare Account ID looks wrong. It is a code of letters and numbers (no spaces).");
        }
        String url = ENDPOINT_OVERRIDE == null || ENDPOINT_OVERRIDE.isBlank()
                ? "https://api.cloudflare.com/client/v4/accounts/" + accountId + "/ai/run/" + MODEL
                : ENDPOINT_OVERRIDE;
        JsonObject body = new JsonObject();
        body.addProperty("prompt", prompt);
        body.addProperty("steps", 4); // schnell is built for 4 steps: fast, with good quality
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<byte[]> res = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                throw failure(res.statusCode(), new String(res.body(), StandardCharsets.UTF_8));
            }
            return parse(res);
        } catch (IOException e) {
            throw new AIException("Could not reach Cloudflare. Check your internet connection.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIException("Request was interrupted", e);
        }
    }

    private AIException failure(int status, String body) {
        return switch (status) {
            case 401, 403 -> new AIException("Cloudflare rejected your Account ID or API token. Check them in Settings → Images "
                    + "(the token needs the Workers AI permission).");
            case 429 -> new AIException("Image limit reached for now. Cloudflare's free daily allowance may be used up; try again later.");
            default -> new AIException("Cloudflare could not create the image (" + status + "): " + errorText(body));
        };
    }

    /** Picture from either a raw image body or Cloudflare's JSON envelope {"result":{"image":"base64"}}. */
    private GeneratedImage parse(HttpResponse<byte[]> res) {
        String type = res.headers().firstValue("Content-Type").orElse("");
        byte[] bytes = res.body();
        if (type.startsWith("image/")) {
            return checked(bytes);
        }
        try {
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("success") && !json.get("success").getAsBoolean()) {
                throw new AIException("Cloudflare could not create the image: " + errorText(json.toString()));
            }
            JsonObject result = json.has("result") && json.get("result").isJsonObject() ? json.getAsJsonObject("result") : json;
            JsonElement image = result.get("image");
            if (image == null || image.isJsonNull()) {
                throw new AIException("Cloudflare returned no image. Try a different description.");
            }
            String b64 = image.getAsString();
            return checked(Base64.getDecoder().decode(b64.substring(b64.indexOf(',') + 1)));
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
            throw new AIException("Unexpected response from Cloudflare", e);
        }
    }

    /** Detects the real format from the first bytes (the service says JPEG, but be safe). */
    private static GeneratedImage checked(byte[] bytes) {
        if (bytes.length < 16 || bytes.length > MAX_IMAGE_BYTES) {
            throw new AIException("Cloudflare returned an unusable image. Please try again.");
        }
        boolean png = (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P';
        return new GeneratedImage(png ? "image/png" : "image/jpeg", bytes);
    }

    private static String errorText(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonArray errors = json.has("errors") && json.get("errors").isJsonArray() ? json.getAsJsonArray("errors") : null;
            if (errors != null && !errors.isEmpty() && errors.get(0).isJsonObject()
                    && errors.get(0).getAsJsonObject().has("message")) {
                return errors.get(0).getAsJsonObject().get("message").getAsString();
            }
        } catch (JsonParseException | IllegalStateException ignored) {
            // not JSON: fall through
        }
        return body.length() > 160 ? body.substring(0, 160) : body;
    }
}
