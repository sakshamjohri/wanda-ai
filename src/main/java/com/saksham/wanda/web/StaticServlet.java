package com.saksham.wanda.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Serves the front end from the classpath folder /public.
 * "/" opens the app for signed-in users and redirects everyone else to the login page.
 * Pages opened at a *.localhost name (such as wanda.localhost) move to plain localhost, the one
 * address whose sign-in cookie is shared by everything.
 */
public class StaticServlet extends HttpServlet {

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=UTF-8", "css", "text/css; charset=UTF-8",
            "js", "application/javascript; charset=UTF-8", "svg", "image/svg+xml",
            "png", "image/png", "ico", "image/x-icon", "json", "application/json", "webp", "image/webp");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        boolean page = path.equals("/") || path.endsWith(".html");
        if (page && req.getServerName().endsWith(".localhost")) {
            String port = req.getServerPort() == 80 ? "" : ":" + req.getServerPort();
            resp.sendRedirect("http://localhost" + port + path);
            return;
        }
        if (path.equals("/") || path.equals("/index.html")) {
            HttpSession session = req.getSession(false);
            boolean signedIn = session != null && session.getAttribute(BaseServlet.SESSION_USER) != null;
            if (!signedIn) {
                resp.sendRedirect("/login.html");
                return;
            }
            path = "/index.html";
        }
        if (path.contains("..")) {
            resp.sendError(400);
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/public" + path)) {
            if (in == null) {
                resp.sendError(404);
                return;
            }
            String ext = path.substring(path.lastIndexOf('.') + 1);
            resp.setContentType(TYPES.getOrDefault(ext, "application/octet-stream"));
            resp.setHeader("Cache-Control", "no-cache");
            in.transferTo(resp.getOutputStream());
        }
    }
}
