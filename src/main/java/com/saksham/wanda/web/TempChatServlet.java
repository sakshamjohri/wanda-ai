package com.saksham.wanda.web;

import com.saksham.wanda.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/** DELETE /api/chat/temp/{tempId} - forget a temporary (incognito) chat right away. */
public class TempChatServlet extends BaseServlet {

    private final ChatService chat;

    public TempChatServlet(ChatService chat) {
        this.chat = chat;
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        String path = req.getPathInfo();
        chat.discardTemp(userId, path == null ? "" : path.substring(1));
        writeJson(resp, Map.of("ok", true));
    }
}
