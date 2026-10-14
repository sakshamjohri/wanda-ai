package com.saksham.wanda;

import com.saksham.wanda.ai.ProviderFactory;
import com.saksham.wanda.config.AppConfig;
import com.saksham.wanda.db.ConversationDao;
import com.saksham.wanda.db.Database;
import com.saksham.wanda.db.MessageDao;
import com.saksham.wanda.db.SettingsDao;
import com.saksham.wanda.db.UserDao;
import com.saksham.wanda.service.AuthService;
import com.saksham.wanda.service.ChatService;
import com.saksham.wanda.service.FileService;
import com.saksham.wanda.service.SettingsService;
import com.saksham.wanda.service.UploadStore;
import com.saksham.wanda.web.AuthServlet;
import com.saksham.wanda.web.ChatServlet;
import com.saksham.wanda.web.ChatStreamServlet;
import com.saksham.wanda.web.ConversationServlet;
import com.saksham.wanda.web.ImageServlet;
import com.saksham.wanda.web.SecurityHeadersFilter;
import com.saksham.wanda.web.SettingsServlet;
import com.saksham.wanda.web.TempChatServlet;
import com.saksham.wanda.web.StaticServlet;
import com.saksham.wanda.web.UploadServlet;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.FileSessionDataStore;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.File;
import java.net.BindException;
import java.util.EnumSet;

/**
 * Wanda AI Assistant - application entry point.
 * Wires the database, DAOs, services and servlets together and starts an
 * embedded Jetty server.
 *
 * @author Saksham Johri
 */
public final class App {

    /** Keeps the terminal readable: only warnings from Jetty and PDFBox, no routine startup chatter. */
    private static void quietLogging() {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        System.setProperty("org.slf4j.simpleLogger.log.org.apache.pdfbox", "error");
        PDFBOX_LOG.setLevel(java.util.logging.Level.SEVERE);
    }

    /** Held in a field so the JDK does not discard the level setting. */
    private static final java.util.logging.Logger PDFBOX_LOG = java.util.logging.Logger.getLogger("org.apache.pdfbox");

    /**
     * Sign-in sessions live in files, so restarting Wanda (for example after pasting an API key)
     * does not sign everyone out. The cookie has its own name so other apps on localhost,
     * which usually call theirs JSESSIONID, cannot overwrite it.
     */
    private static void configureSessions(ServletContextHandler context, String sessionDir) {
        SessionHandler sessions = context.getSessionHandler();
        sessions.setSessionCookie("WANDA_SESSION");
        sessions.setHttpOnly(true);
        sessions.setSameSite(HttpCookie.SameSite.LAX);
        sessions.setMaxInactiveInterval(30 * 24 * 3600);

        FileSessionDataStore store = new FileSessionDataStore();
        store.setStoreDir(new File(sessionDir));
        DefaultSessionCache cache = new DefaultSessionCache(sessions);
        cache.setSessionDataStore(store);
        cache.setFlushOnResponseCommit(true); // saved before the browser sees the sign-in succeed
        sessions.setSessionCache(cache);
    }

    private static boolean portInUse(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof BindException) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true"); // picture resizing needs no screen
        quietLogging();
        AppConfig config;
        try {
            config = AppConfig.load();
        } catch (IllegalArgumentException e) {
            System.err.println("\n  " + e.getMessage() + "\n");
            System.exit(1);
            return;
        }

        // Data layer (JDBC)
        Database db;
        try {
            db = Database.init(config.dbUrl());
        } catch (RuntimeException e) {
            System.err.println("\n  Could not open the database: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            System.err.println("  If Wanda is already running in another window, close that one first.\n");
            System.exit(1);
            return;
        }
        UserDao userDao = new UserDao(db);

        // Service layer
        AuthService auth = new AuthService(userDao);
        SettingsService settings = new SettingsService(new SettingsDao(db), config.groqApiKey(),
                config.cloudflareAccountId(), config.cloudflareApiToken());
        ProviderFactory providers = new ProviderFactory();
        ChatService chat = new ChatService(new ConversationDao(db), new MessageDao(db), settings, providers);
        UploadStore uploads = new UploadStore();

        // Web layer (Servlets)
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        configureSessions(context, config.sessionDir());
        context.addFilter(new FilterHolder(new SecurityHeadersFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(new AuthServlet(auth)), "/api/auth/*");
        context.addServlet(new ServletHolder(new ChatServlet(chat, uploads)), "/api/chat");
        context.addServlet(new ServletHolder(new ChatStreamServlet(chat, uploads)), "/api/chat/stream");
        context.addServlet(new ServletHolder(new TempChatServlet(chat)), "/api/chat/temp/*");
        context.addServlet(new ServletHolder(new ConversationServlet(chat)), "/api/conversations/*");
        context.addServlet(new ServletHolder(new ImageServlet(chat)), "/api/image/*");
        context.addServlet(new ServletHolder(new UploadServlet(new FileService(), uploads)), "/api/upload/*");
        context.addServlet(new ServletHolder(new SettingsServlet(settings, auth, providers)), "/api/settings");
        context.addServlet(new ServletHolder(new StaticServlet()), "/");

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(config.port());
        connector.setIdleTimeout(180_000); // a slow answer may leave a streaming connection quiet for a while
        server.addConnector(connector);
        server.setHandler(context);
        try {
            server.start();
        } catch (Exception e) {
            if (portInUse(e)) {
                System.err.println("\n  Port " + config.port() + " is already in use (is Wanda already running?).");
                System.err.println("  Stop the other program, or pick another port: PORT=9090 ./run.sh\n");
                System.exit(1);
            }
            throw e;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            chat.close();
            try {
                server.stop();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }, "wanda-shutdown"));

        String url = "http://localhost" + (config.port() == 80 ? "" : ":" + config.port());
        System.out.println();
        System.out.println("  Wanda AI is running.");
        System.out.println("  Open " + url);
        if (config.groqApiKey().isBlank()) {
            System.out.println("  Groq API key: not set - add GROQ_API_KEY to wanda.properties (or paste it in Settings) and restart");
        } else if (config.looksLikeXaiKey()) {
            System.out.println("  Groq API key: WARNING - that looks like an xAI key (xai-...). Wanda uses a free Groq key (gsk_...) from console.groq.com/keys");
        } else {
            System.out.println("  Groq API key: loaded");
        }
        System.out.println("  Image creation: " + (!config.cloudflareAccountId().isBlank() && !config.cloudflareApiToken().isBlank()
                ? "on (Cloudflare Workers AI)" : "off - add CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN (free) to turn it on"));
        System.out.println("  Press Ctrl+C to stop.");
        server.join();
    }
}
