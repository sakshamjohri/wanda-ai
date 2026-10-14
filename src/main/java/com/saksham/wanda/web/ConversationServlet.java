package com.saksham.wanda.web;

import com.saksham.wanda.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * GET    /api/conversations       list the user's chats
 * GET    /api/conversations/{id}  messages of one chat
 * PUT    /api/conversations/{id}  rename and/or pin  {"title": "...", "pinned": true}  (either field may be omitted)
 * DELETE /api/conversations/{id}  delete one chat
 * DELETE /api/conversations       delete all chats
 */
public class ConversationServlet extends BaseServlet {

    private record UpdateBody(String title, Boolean pinned) { }

    private final ChatService chat;

    public ConversationServlet(ChatService chat) {
        this.chat = chat;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        Long id = pathId(req);
        writeJson(resp, id == null ? chat.listConversations(userId) : chat.history(userId, id));
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        UpdateBody body = readJson(req, UpdateBody.class);
        writeJson(resp, chat.update(userId, requirePathId(req), body.title(), body.pinned()));
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        Long id = pathId(req);
        if (id == null) {
            writeJson(resp, Map.of("deleted", chat.deleteAllConversations(userId)));
        } else {
            chat.deleteConversation(userId, id);
            writeJson(resp, Map.of("ok", true));
        }
    }
}
