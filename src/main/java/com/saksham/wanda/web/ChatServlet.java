package com.saksham.wanda.web;

import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.Attachment;
import com.saksham.wanda.service.ChatService;
import com.saksham.wanda.service.UploadStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * POST /api/chat - sends a message and returns the reply.
 * Uploaded files are referenced by the ids returned from {@link UploadServlet}.
 * With {"regenerate": true} the latest reply of the conversation is generated again.
 * (The browser uses {@link ChatStreamServlet}, which delivers the same reply as it is written.)
 */
public class ChatServlet extends BaseServlet {

    private record ChatBody(Long conversationId, String message, List<String> attachmentIds, boolean regenerate) { }

    private final ChatService chat;
    private final UploadStore uploads;

    public ChatServlet(ChatService chat, UploadStore uploads) {
        this.chat = chat;
        this.uploads = uploads;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        ChatBody body = readJson(req, ChatBody.class);
        if (body.regenerate()) {
            if (body.conversationId() == null) {
                throw new ValidationException("Choose a conversation to regenerate");
            }
            writeJson(resp, chat.regenerate(userId, body.conversationId()));
            return;
        }

        List<String> ids = body.attachmentIds() == null ? List.of() : body.attachmentIds();
        List<Attachment> files = uploads.resolve(userId, ids);

        ChatService.ChatResult result = chat.send(userId, body.conversationId(), body.message(), files);
        uploads.removeAll(userId, ids); // files are consumed only after a successful reply, so retries keep them
        writeJson(resp, result);
    }
}
