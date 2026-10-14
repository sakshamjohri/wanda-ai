package com.saksham.wanda.web;

import com.saksham.wanda.exception.AppException;
import com.saksham.wanda.exception.ClientGoneException;
import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.Attachment;
import com.saksham.wanda.model.Conversation;
import com.saksham.wanda.model.Message;
import com.saksham.wanda.service.ChatService;
import com.saksham.wanda.service.UploadStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POST /api/chat/stream - same request as /api/chat, but the reply arrives as it is written.
 *
 * The response is newline-delimited JSON, one event per line:
 * <pre>
 *   {"type":"start","conversation":{...},"userMessage":{...}}   the user's message is saved
 *   {"type":"delta","text":"..."}                               another piece of the reply (many)
 *   {"type":"done","conversation":{...},"reply":{...}}          the finished reply is saved
 *   {"type":"error","status":502,"message":"...","partial":true} failure after the stream began
 * </pre>
 * With {"temporary": true, "tempId": "..."} the chat is an incognito chat: its start event is
 * {"type":"start","temporary":true,"tempId":"..."}, the done event carries only the reply text,
 * and nothing is saved anywhere.
 * Problems found before streaming starts (not signed in, empty message, ...) are ordinary JSON
 * error responses with the matching HTTP status. If the browser disconnects, the work stops and
 * whatever was written so far is kept.
 */
public class ChatStreamServlet extends BaseServlet {

    private record ChatBody(Long conversationId, String message, List<String> attachmentIds, boolean regenerate,
                            boolean temporary, String tempId) { }

    private final ChatService chat;
    private final UploadStore uploads;

    public ChatStreamServlet(ChatService chat, UploadStore uploads) {
        this.chat = chat;
        this.uploads = uploads;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long userId = requireUser(req);
        ChatBody body = readJson(req, ChatBody.class);
        List<String> ids = body.attachmentIds() == null ? List.of() : body.attachmentIds();
        if (body.regenerate() && !body.temporary() && body.conversationId() == null) {
            throw new ValidationException("Choose a conversation to regenerate");
        }
        List<Attachment> files = body.regenerate() ? List.of() : uploads.resolve(userId, ids);

        Events out = new Events(resp);
        boolean[] gotText = {false};
        if (body.temporary()) {
            streamTemporary(userId, body, ids, out, gotText);
            return;
        }
        ChatService.StreamListener listener = new ChatService.StreamListener() {
            @Override
            public void onStart(Conversation conversation, Message userMessage) {
                Map<String, Object> e = event("start");
                e.put("conversation", conversation);
                if (userMessage != null) {
                    e.put("userMessage", userMessage);
                }
                out.send(e);
            }

            @Override
            public void onDelta(String text) {
                gotText[0] = true;
                Map<String, Object> e = event("delta");
                e.put("text", text);
                out.send(e);
            }
        };

        try {
            ChatService.ChatResult result = body.regenerate()
                    ? chat.regenerateStreaming(userId, body.conversationId(), listener)
                    : chat.sendStreaming(userId, body.conversationId(), body.message(), files, listener);
            uploads.removeAll(userId, ids); // files are consumed only after a successful reply, so retries keep them
            Map<String, Object> done = event("done");
            done.put("conversation", result.conversation());
            done.put("reply", result.reply());
            out.send(done);
        } catch (ClientGoneException e) {
            // The user pressed Stop or left; the service already saved what exists.
        } catch (AppException e) {
            if (!out.started()) {
                throw e; // nothing sent yet: let BaseServlet answer with a normal JSON error
            }
            Map<String, Object> err = event("error");
            err.put("status", e.getStatus());
            err.put("message", e.getMessage());
            err.put("partial", gotText[0]);
            try {
                out.send(err);
            } catch (ClientGoneException ignored) {
                // gone already
            }
        }
    }

    /** Incognito chat: the same events, but nothing is stored and there is no conversation record. */
    private void streamTemporary(long userId, ChatBody body, List<String> ids, Events out, boolean[] gotText) {
        String tempId = body.tempId() == null || body.tempId().isBlank() ? UUID.randomUUID().toString() : body.tempId();
        List<Attachment> files = body.regenerate() ? List.of() : uploads.resolve(userId, ids);
        try {
            Message reply = chat.tempStreaming(userId, tempId, body.message(), files, body.regenerate(),
                    () -> {
                        Map<String, Object> start = event("start");
                        start.put("temporary", true);
                        start.put("tempId", tempId);
                        out.send(start);
                    },
                    piece -> {
                        gotText[0] = true;
                        Map<String, Object> e = event("delta");
                        e.put("text", piece);
                        out.send(e);
                    });
            uploads.removeAll(userId, ids);
            Map<String, Object> done = event("done");
            done.put("reply", reply);
            out.send(done);
        } catch (ClientGoneException e) {
            // The user pressed Stop or left; the part that arrived was kept in memory.
        } catch (AppException e) {
            if (!out.started()) {
                throw e;
            }
            Map<String, Object> err = event("error");
            err.put("status", e.getStatus());
            err.put("message", e.getMessage());
            err.put("partial", gotText[0]);
            try {
                out.send(err);
            } catch (ClientGoneException ignored) {
                // gone already
            }
        }
    }

    private static Map<String, Object> event(String type) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        return e;
    }

    /** Writes newline-delimited JSON events, flushing each one so the browser sees it immediately. */
    private static final class Events {
        private final HttpServletResponse resp;
        private OutputStream out;

        Events(HttpServletResponse resp) {
            this.resp = resp;
        }

        boolean started() {
            return out != null;
        }

        void send(Map<String, Object> event) {
            try {
                if (out == null) {
                    resp.setContentType("application/x-ndjson");
                    resp.setCharacterEncoding("UTF-8");
                    resp.setHeader("Cache-Control", "no-cache, no-transform");
                    resp.setHeader("X-Accel-Buffering", "no"); // tell proxies (nginx, Render) not to buffer
                    out = resp.getOutputStream();
                }
                out.write((GSON.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new ClientGoneException(e);
            }
        }
    }
}
