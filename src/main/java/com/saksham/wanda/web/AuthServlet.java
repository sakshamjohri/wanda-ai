package com.saksham.wanda.web;

import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.User;
import com.saksham.wanda.service.AuthService;
import com.saksham.wanda.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Map;

/**
 * POST /api/auth/register, /api/auth/login, /api/auth/logout
 * GET  /api/auth/me (current user), /api/auth/status (signed in or not, never 401)
 */
public class AuthServlet extends BaseServlet {

    /** JSON body for register and login. */
    private record Credentials(String username, String password, String displayName) { }

    private final AuthService auth;
    /** Slows down password guessing: 10 attempts per address and username every 5 minutes. */
    private final RateLimiter<String> loginLimiter = new RateLimiter<>(10, 5 * 60_000);
    /** Slows down mass sign-ups from one address (generous, since a school or office shares one address). */
    private final RateLimiter<String> registerLimiter = new RateLimiter<>(20, 60 * 60_000);

    public AuthServlet(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = req.getPathInfo() == null ? "" : req.getPathInfo();
        switch (action) {
            case "/me" -> writeJson(resp, auth.findUser(requireUser(req)));
            case "/status" -> {
                HttpSession session = req.getSession(false);
                Object id = session == null ? null : session.getAttribute(SESSION_USER);
                writeJson(resp, Map.of("signedIn", id != null));
            }
            default -> throw new ValidationException(404, "Not found");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = req.getPathInfo() == null ? "" : req.getPathInfo();
        switch (action) {
            case "/register" -> {
                Credentials c = readJson(req, Credentials.class);
                throttle(registerLimiter, req.getRemoteAddr());
                startSession(req, auth.register(c.username(), c.displayName(), c.password()));
                writeJson(resp, auth.findUser(requireUser(req)));
            }
            case "/login" -> {
                Credentials c = readJson(req, Credentials.class);
                throttle(loginLimiter, req.getRemoteAddr() + "|" + (c.username() == null ? "" : c.username().trim().toLowerCase()));
                startSession(req, auth.login(c.username(), c.password()));
                writeJson(resp, auth.findUser(requireUser(req)));
            }
            case "/logout" -> {
                HttpSession session = req.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                writeJson(resp, Map.of("ok", true));
            }
            default -> throw new ValidationException(404, "Not found");
        }
    }

    private static void throttle(RateLimiter<String> limiter, String key) {
        if (!limiter.tryAcquire(key)) {
            throw new ValidationException(429, "Too many attempts. Please wait a few minutes and try again.");
        }
    }

    /** Replaces any old session to prevent session fixation, then stores the user id. */
    private static void startSession(HttpServletRequest req, User user) {
        HttpSession old = req.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = req.getSession(true);
        session.setMaxInactiveInterval(30 * 24 * 3600);
        session.setAttribute(SESSION_USER, user.getId());
    }
}
