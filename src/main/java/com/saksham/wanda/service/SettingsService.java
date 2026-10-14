package com.saksham.wanda.service;

import com.saksham.wanda.db.SettingsDao;
import com.saksham.wanda.exception.ValidationException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user preferences with an in-memory cache.
 * The cache is a ConcurrentHashMap; writes are synchronized so the database
 * and the cache are always updated together.
 *
 * @author Saksham Johri
 */
public class SettingsService {

    public static final String API_KEY = "apiKey";
    public static final String IMAGE_TOKEN = "imageToken";

    /** Cloudflare credentials for creating pictures. */
    public record ImageCredentials(String accountId, String token) {
        public boolean ready() {
            return !accountId.isBlank() && !token.isBlank();
        }
    }

    /** Every supported setting with its default value. Unknown keys are rejected. */
    private static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("theme", "dark"); // the Wanda look; users can switch to light or follow the device
        d.put("model", ""); // blank = provider default
        d.put("temperature", "0.7");
        d.put("thinking", "fast"); // fast, balanced or deep: how long the model thinks before answering
        d.put("systemPrompt", "You are Wanda, a helpful AI assistant created by Saksham Johri. "
                + "Answer clearly and accurately, use Markdown for structure and code, and keep replies "
                + "concise unless the user asks for detail. When the user attaches files, their contents "
                + "appear in the message between '--- Attached file' markers; read them carefully and use them.");
        d.put("autoSpeak", "false");
        d.put("voiceFeatures", "true"); // master switch: microphone, voice chat and read-aloud buttons
        d.put("voiceReplies", "true");  // Wanda speaks her answers in voice chat (off = talk, then read the reply)
        d.put("voice", "");
        d.put(API_KEY, "");
        d.put("imageAccountId", ""); // Cloudflare Account ID (not secret)
        d.put(IMAGE_TOKEN, "");      // Cloudflare API token (secret, never sent to the browser)
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    private final SettingsDao dao;
    private final String serverApiKey;
    private final ImageCredentials serverImage;
    private final Map<Long, Map<String, String>> cache = new ConcurrentHashMap<>();

    public SettingsService(SettingsDao dao, String serverApiKey, String serverImageAccountId, String serverImageToken) {
        this.dao = dao;
        this.serverApiKey = serverApiKey;
        this.serverImage = new ImageCredentials(serverImageAccountId == null ? "" : serverImageAccountId,
                serverImageToken == null ? "" : serverImageToken);
    }

    public SettingsService(SettingsDao dao, String serverApiKey) {
        this(dao, serverApiKey, "", "");
    }

    /** Full settings for a user (defaults merged with saved values). */
    public Map<String, String> get(long userId) {
        return Collections.unmodifiableMap(cache.computeIfAbsent(userId, id -> {
            Map<String, String> merged = new LinkedHashMap<>(DEFAULTS);
            merged.putAll(dao.load(id));
            return merged;
        }));
    }

    /** Settings safe to send to the browser: the API key is replaced by a flag. */
    public Map<String, Object> publicView(long userId) {
        Map<String, Object> view = new LinkedHashMap<>(get(userId));
        boolean personalKey = !get(userId).get(API_KEY).isBlank();
        view.remove(API_KEY);
        view.remove(IMAGE_TOKEN);
        view.put("hasPersonalKey", personalKey);
        view.put("hasServerKey", !serverApiKey.isBlank());
        view.put("hasPersonalImageKey", !get(userId).get(IMAGE_TOKEN).isBlank());
        view.put("hasServerImageKey", serverImage.ready());
        view.put("imageReady", imageCredentialsFor(userId).ready());
        return view;
    }

    public synchronized void update(long userId, Map<String, String> changes) {
        Map<String, String> current = new LinkedHashMap<>(get(userId));
        for (Map.Entry<String, String> e : changes.entrySet()) {
            if (!DEFAULTS.containsKey(e.getKey())) {
                continue; // ignore unknown keys such as displayName
            }
            String value = e.getValue() == null ? "" : e.getValue().trim();
            validate(e.getKey(), value);
            dao.put(userId, e.getKey(), value);
            current.put(e.getKey(), value);
        }
        cache.put(userId, current);
    }

    /** The Cloudflare credentials used for a user's pictures: their own if both are set, else the server's. */
    public ImageCredentials imageCredentialsFor(long userId) {
        Map<String, String> mine = get(userId);
        ImageCredentials own = new ImageCredentials(mine.get("imageAccountId"), mine.get(IMAGE_TOKEN));
        return own.ready() ? own : serverImage;
    }

    /** Maps the "thinking" preference to the effort level models understand. */
    public String effortFor(long userId) {
        return switch (get(userId).get("thinking")) {
            case "deep" -> "high";
            case "balanced" -> "medium";
            default -> "low";
        };
    }

    /** The key used for a user's requests: their own key first, then the server key. */
    public String apiKeyFor(long userId) {
        String own = get(userId).get(API_KEY);
        return own.isBlank() ? serverApiKey : own;
    }

    private static void validate(String key, String value) {
        switch (key) {
            case "temperature" -> {
                try {
                    double t = Double.parseDouble(value);
                    if (t < 0 || t > 1.5) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    throw new ValidationException("Temperature must be a number between 0 and 1.5");
                }
            }
            case "theme" -> {
                if (!value.equals("dark") && !value.equals("light") && !value.equals("system")) {
                    throw new ValidationException("Theme must be system, light or dark");
                }
            }
            case "thinking" -> {
                if (!value.equals("fast") && !value.equals("balanced") && !value.equals("deep")) {
                    throw new ValidationException("Reply style must be fast, balanced or deep");
                }
            }
            case "imageAccountId" -> {
                if (!value.isEmpty() && !value.matches("[A-Za-z0-9]{8,64}")) {
                    throw new ValidationException("The Cloudflare Account ID is a code of letters and numbers (no spaces)");
                }
            }
            case IMAGE_TOKEN -> {
                if (value.startsWith("gsk_")) {
                    throw new ValidationException("That is a Groq key. Image creation needs a Cloudflare API token (Workers AI permission)");
                }
                if (!value.isEmpty() && (value.length() < 20 || value.contains(" "))) {
                    throw new ValidationException("That does not look like a Cloudflare API token");
                }
            }
            case "autoSpeak", "voiceFeatures", "voiceReplies" -> {
                if (!value.equals("true") && !value.equals("false")) {
                    throw new ValidationException(key + " must be true or false");
                }
            }
            case "systemPrompt" -> {
                if (value.length() > 4000) {
                    throw new ValidationException("Custom instructions are limited to 4000 characters");
                }
            }
            case API_KEY -> {
                if (value.startsWith("xai-")) {
                    throw new ValidationException("That is an xAI key. Wanda uses a free Groq key (starts with gsk_) from console.groq.com/keys");
                }
            }
            default -> { }
        }
    }
}
