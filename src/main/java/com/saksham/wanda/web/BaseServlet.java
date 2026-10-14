package com.saksham.wanda.web;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.saksham.wanda.exception.AppException;
import com.saksham.wanda.exception.AuthException;
import com.saksham.wanda.exception.ClientGoneException;
import com.saksham.wanda.exception.DataAccessException;
import com.saksham.wanda.exception.ValidationException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Map;

/**
 * Parent of all API servlets. Provides JSON input/output, session-based
 * authentication and one central place where every exception becomes a
 * clean JSON error response.
 *
 * @author Saksham Johri
 */
public abstract class BaseServlet extends HttpServlet {

    protected static final Gson GSON = new Gson();
    protected static final String SESSION_USER = "userId";

    /** Wraps the normal doGet/doPost dispatch with uniform error handling. */
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        try {
            super.service(req, resp);
        } catch (ClientGoneException e) {
            // The browser disconnected mid-response; there is nobody left to answer.
        } catch (AppException e) {
            // Polymorphism: Auth/Validation/AI/DataAccess exceptions each carry their own status.
            // Expected conditions (a missing key, an AI service refusing) get one quiet line; real faults get the trace.
            if (e instanceof DataAccessException) {
                log("Request failed: " + req.getRequestURI() + " (" + e.getMessage() + ")", e.getCause());
            } else if (e.getStatus() >= 500 && !(e instanceof ValidationException)) {
                log("Request failed: " + req.getRequestURI() + " (" + e.getMessage() + ")");
            }
            // Database errors can contain SQL text: log it, but only tell the user something generic.
            sendError(resp, e.getStatus(), e instanceof DataAccessException
                    ? "Something went wrong on the server" : e.getMessage());
        } catch (JsonParseException e) {
            sendError(resp, 400, "Malformed JSON request");
        } catch (RuntimeException e) {
            log("Unexpected error: " + req.getRequestURI(), e);
            sendError(resp, 500, "Something went wrong on the server");
        }
    }

    /** Parses the request body as JSON into the given type. */
    protected <T> T readJson(HttpServletRequest req, Class<T> type) throws IOException {
        T value = GSON.fromJson(req.getReader(), type);
        if (value == null) {
            throw new ValidationException("Request body is required");
        }
        return value;
    }

    protected void writeJson(HttpServletResponse resp, Object body) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(GSON.toJson(body));
    }

    protected void sendError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        writeJson(resp, Map.of("error", message == null ? "Error" : message));
    }

    /** Returns the logged-in user's id or rejects the request with 401. */
    protected long requireUser(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        Object id = session == null ? null : session.getAttribute(SESSION_USER);
        if (id == null) {
            throw new AuthException("Please sign in to continue");
        }
        return (Long) id;
    }

    /** Extracts the numeric id from a path such as /api/conversations/42. */
    protected Long pathId(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.equals("/")) {
            return null;
        }
        try {
            return Long.parseLong(path.substring(1));
        } catch (NumberFormatException e) {
            throw new ValidationException(404, "Not found");
        }
    }

    protected long requirePathId(HttpServletRequest req) {
        Long id = pathId(req);
        if (id == null) {
            throw new ValidationException("An id is required in the URL");
        }
        return id;
    }
}
