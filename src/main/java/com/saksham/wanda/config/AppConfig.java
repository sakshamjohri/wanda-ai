package com.saksham.wanda.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Immutable application configuration, read once at startup.
 *
 * Values come from the file {@code wanda.properties} in the folder the app is started from,
 * and environment variables with the same names override the file (useful for cloud hosting).
 *
 * <pre>
 *   GROQ_API_KEY      free Groq key (gsk_...) from console.groq.com/keys
 *   PORT              HTTP port (default 8080)
 *   DB_URL            JDBC URL (default: file database in ./data)
 *   SESSION_DIR       where sign-in sessions are kept so users stay signed in across restarts
 *   CLOUDFLARE_ACCOUNT_ID, CLOUDFLARE_API_TOKEN   free Cloudflare Workers AI credentials for image creation (optional)
 * </pre>
 *
 * @author Saksham Johri
 */
public record AppConfig(int port, String dbUrl, String groqApiKey, String sessionDir,
                        String cloudflareAccountId, String cloudflareApiToken) {

    private static final Path FILE = Path.of("wanda.properties");

    public static AppConfig load() {
        Properties file = new Properties();
        if (Files.exists(FILE)) {
            try (Reader in = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                file.load(in);
            } catch (IOException e) {
                System.err.println("Could not read wanda.properties: " + e.getMessage());
            }
        }
        return new AppConfig(
                port(value(file, "PORT", "8080")),
                keepDatabaseOpen(value(file, "DB_URL", "jdbc:h2:./data/wanda")),
                value(file, "GROQ_API_KEY", value(file, "GROK_API_KEY", "")), // GROK_ accepted as a common misspelling
                value(file, "SESSION_DIR", "data/sessions"),
                value(file, "CLOUDFLARE_ACCOUNT_ID", ""),
                value(file, "CLOUDFLARE_API_TOKEN", ""));
    }

    /** True when the key looks like an xAI key (xai-...) instead of a Groq key (gsk_...). */
    public boolean looksLikeXaiKey() {
        return groqApiKey.startsWith("xai-");
    }

    private static int port(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORT must be a number between 1 and 65535 (got \"" + text + "\")");
        }
    }

    /**
     * H2 closes a file database when its last connection closes, and every query here opens a
     * fresh connection, so without this flag each query would reopen the database file and a
     * chat message would take a noticeable fraction of a second. DB_CLOSE_DELAY=-1 keeps it open.
     */
    static String keepDatabaseOpen(String url) {
        if (url.startsWith("jdbc:h2:") && !url.toUpperCase().contains("DB_CLOSE_DELAY")) {
            return url + ";DB_CLOSE_DELAY=-1";
        }
        return url;
    }

    /** Environment variable first, then the properties file, then the fallback. */
    private static String value(Properties file, String name, String fallback) {
        String env = System.getenv(name);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String prop = file.getProperty(name);
        return prop == null || prop.isBlank() ? fallback : prop.trim();
    }
}
