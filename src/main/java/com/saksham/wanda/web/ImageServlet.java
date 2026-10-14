package com.saksham.wanda.web;

import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Base64;

/**
 * POST /api/image       {"conversationId": 12 or null, "prompt": "a red fox in the snow"}
 *                       creates a picture and returns {conversation, userMessage, reply}
 * GET  /api/image/{id}  the picture of one of the signed-in user's messages (JPEG or PNG)
 */
public class ImageServlet extends BaseServlet {

    private record ImageBody(Long conversationId, String prompt) { }

    private final ChatService chat;

    public ImageServlet(ChatService chat) {
        this.chat = chat;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        ImageBody body = readJson(req, ImageBody.class);
        writeJson(resp, chat.createImage(userId, body.conversationId(), body.prompt()));
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        String dataUrl = chat.createdImage(userId, requirePathId(req));
        int comma = dataUrl == null ? -1 : dataUrl.indexOf(',');
        if (comma < 0) {
            throw new ValidationException(404, "Image not found");
        }
        String mime = dataUrl.substring(5, dataUrl.indexOf(';'));
        resp.setContentType(mime);
        resp.setHeader("Cache-Control", "private, max-age=86400"); // a stored picture never changes
        resp.getOutputStream().write(Base64.getDecoder().decode(dataUrl.substring(comma + 1)));
    }
}
