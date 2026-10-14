package com.saksham.wanda.service;

import com.saksham.wanda.ai.AIProvider;
import com.saksham.wanda.ai.ChatRequest;
import com.saksham.wanda.ai.ImageProvider;
import com.saksham.wanda.ai.ProviderFactory;
import com.saksham.wanda.db.ConversationDao;
import com.saksham.wanda.db.MessageDao;
import com.saksham.wanda.exception.AIException;
import com.saksham.wanda.exception.AppException;
import com.saksham.wanda.exception.ClientGoneException;
import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.Attachment;
import com.saksham.wanda.model.Conversation;
import com.saksham.wanda.model.Message;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Core business logic: conversations, messages and model calls.
 *
 * Replies can be produced in one piece ({@link #send}) or streamed to the browser as they are
 * written ({@link #sendStreaming}). Both keep the database consistent when something goes wrong:
 * a failed exchange is rolled back so a retry never duplicates the user's message, and a reply
 * that was stopped halfway is kept as far as it got.
 *
 * @author Saksham Johri
 */
public class ChatService implements AutoCloseable {

    private static final int CONTEXT_MESSAGES = 20;
    private static final int MAX_MESSAGE_CHARS = 20_000;
    private static final long MODEL_TIMEOUT_SECONDS = 120;
    private static final int MAX_STREAMS = 16;
    private static final int MAX_IMAGE_PROMPT = 1000;

    /** Result of one exchange: the conversation and the new messages (userMessage is null on regenerate). */
    public record ChatResult(Conversation conversation, Message userMessage, Message reply) { }

    /** Receives the pieces of a streamed exchange. May throw {@link ClientGoneException} if the browser left. */
    public interface StreamListener {
        /** The user's message is stored (null when regenerating) and the model is about to answer. */
        void onStart(Conversation conversation, Message userMessage);

        /** Another piece of the reply. */
        void onDelta(String text);
    }

    /** A user message that has been stored and is waiting for its reply. */
    private record Turn(Conversation conversation, Message userMessage, boolean isNewConversation) { }

    private final ConversationDao conversations;
    private final MessageDao messages;
    private final SettingsService settings;
    private final ProviderFactory providers;
    private final TempChatStore temp = new TempChatStore();
    private final RateLimiter<Long> limiter = new RateLimiter<>(30, 60_000);
    private final RateLimiter<Long> imageLimiter = new RateLimiter<>(15, 10 * 60_000);
    private final Semaphore streamSlots = new Semaphore(MAX_STREAMS);
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final ExecutorService workers = Executors.newFixedThreadPool(8, task -> {
        Thread t = new Thread(task, "wanda-model-" + threadCounter.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public ChatService(ConversationDao conversations, MessageDao messages,
                       SettingsService settings, ProviderFactory providers) {
        this.conversations = conversations;
        this.messages = messages;
        this.settings = settings;
        this.providers = providers;
    }

    /* ------------------------------------------------------------ sending */

    /**
     * Stores the user's message, asks the model for a reply and stores that too.
     * If the model fails, the user's message is rolled back so a retry does not duplicate it.
     */
    public ChatResult send(long userId, Long conversationId, String text, List<Attachment> files) {
        Turn turn = begin(userId, conversationId, text, files);
        try {
            return new ChatResult(turn.conversation(), turn.userMessage(), ask(userId, turn.conversation()));
        } catch (AppException e) {
            rollback(turn);
            throw e;
        }
    }

    /** Like {@link #send}, but the reply is passed to the listener piece by piece as it is written. */
    public ChatResult sendStreaming(long userId, Long conversationId, String text,
                                    List<Attachment> files, StreamListener listener) {
        Turn turn = begin(userId, conversationId, text, files);
        try {
            listener.onStart(turn.conversation(), turn.userMessage());
        } catch (RuntimeException e) {
            rollback(turn); // the browser left before the model was even asked
            throw e;
        }
        Message reply = streamReply(userId, turn.conversation(), listener, () -> rollback(turn));
        return new ChatResult(turn.conversation(), turn.userMessage(), reply);
    }

    /** Replaces the latest reply of a conversation with a freshly generated one. */
    public ChatResult regenerate(long userId, long conversationId) {
        Conversation conv = ownedConversation(userId, conversationId);
        Message old = latestReply(conv);
        checkRate(userId);
        messages.delete(old.getId());
        try {
            return new ChatResult(conv, null, ask(userId, conv));
        } catch (AppException e) {
            restore(conv, old); // keep the old answer
            throw e;
        }
    }

    /** Streaming version of {@link #regenerate}. */
    public ChatResult regenerateStreaming(long userId, long conversationId, StreamListener listener) {
        Conversation conv = ownedConversation(userId, conversationId);
        Message old = latestReply(conv);
        checkRate(userId);
        messages.delete(old.getId());
        try {
            listener.onStart(conv, null);
        } catch (RuntimeException e) {
            restore(conv, old);
            throw e;
        }
        Message reply = streamReply(userId, conv, listener, () -> restore(conv, old));
        return new ChatResult(conv, null, reply);
    }

    /* ------------------------------------------------------------ internals */

    /** What a validated user turn consists of. */
    private record Draft(String text, List<String> pictures) { }

    /** Checks a message and its files against the limits; returns the cleaned text and the pictures. */
    private Draft draft(long userId, String text, List<Attachment> files) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty() && files.isEmpty()) {
            throw new ValidationException("Type a message first");
        }
        if (text.length() > MAX_MESSAGE_CHARS) {
            throw new ValidationException("Messages are limited to " + MAX_MESSAGE_CHARS + " characters");
        }
        List<String> pictures = files.stream()
                .filter(f -> f.kind() == Attachment.Kind.IMAGE).map(Attachment::dataUrl).toList();
        if (pictures.size() > UploadStore.MAX_IMAGES) {
            throw new ValidationException("You can attach up to " + UploadStore.MAX_IMAGES + " pictures per message.");
        }
        checkRate(userId);
        if (text.isEmpty()) {
            text = "Please look at the attached file" + (files.size() > 1 ? "s." : ".");
        }
        return new Draft(text, pictures);
    }

    /** Builds the (unsaved) user message for a draft, with its file text and pictures. */
    private Message userMessage(long conversationId, Draft d, List<Attachment> files, String kind) {
        Message m = new Message(conversationId, Message.Role.USER, d.text());
        m.setKind(kind);
        if (!files.isEmpty()) {
            m.setAttachments(files.stream().map(Attachment::name).collect(Collectors.joining(", ")));
            m.setContext(fileContext(files));
            m.setImages(d.pictures().isEmpty() ? null : String.join("\n", d.pictures()));
            m.setImageCount(d.pictures().size());
        }
        return m;
    }

    /** Validates and stores the user's message (creating the conversation if needed). */
    private Turn begin(long userId, Long conversationId, String text, List<Attachment> files) {
        return begin(userId, conversationId, text, files, "text");
    }

    private Turn begin(long userId, Long conversationId, String text, List<Attachment> files, String kind) {
        Draft d = draft(userId, text, files);
        boolean isNew = conversationId == null;
        Conversation conv = isNew
                ? conversations.save(new Conversation(userId, titleFrom(d.text())))
                : ownedConversation(userId, conversationId);
        Message userMsg = userMessage(conv.getId(), d, files, kind);
        messages.save(userMsg);
        return new Turn(conv, userMsg, isNew);
    }

    private void rollback(Turn turn) {
        messages.delete(turn.userMessage().getId());
        if (turn.isNewConversation()) {
            conversations.delete(turn.conversation().getId());
        }
    }

    private Message latestReply(Conversation conv) {
        List<Message> last = messages.findRecent(conv.getId(), 1);
        if (last.isEmpty() || last.get(0).getRole() != Message.Role.ASSISTANT) {
            throw new ValidationException("There is no reply to regenerate yet");
        }
        return last.get(0);
    }

    private void restore(Conversation conv, Message old) {
        messages.save(new Message(conv.getId(), Message.Role.ASSISTANT, old.getContent()));
    }

    /** Builds the model request from the stored history, including the newest pictures. */
    private ChatRequest requestFor(long userId, Conversation conv) {
        List<Message> history = messages.findRecent(conv.getId(), CONTEXT_MESSAGES);
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m.getRole() == Message.Role.USER && m.getImageCount() > 0) {
                m.setImages(messages.loadImages(m.getId())); // only the newest picture message is sent
                break;
            }
        }
        return buildRequest(userId, history);
    }

    private ChatRequest buildRequest(long userId, List<Message> history) {
        Map<String, String> prefs = settings.get(userId);
        return new ChatRequest(history, prefs.get("systemPrompt"), prefs.get("model"),
                Double.parseDouble(prefs.get("temperature")), settings.apiKeyFor(userId), settings.effortFor(userId));
    }

    /** Sends the recent history to the model in one piece and stores the reply. */
    private Message ask(long userId, Conversation conv) {
        ChatRequest request = requestFor(userId, conv);
        AIProvider ai = providers.textProvider(request.apiKey());
        Message reply = messages.save(new Message(conv.getId(), Message.Role.ASSISTANT, callModel(ai, request)));
        conversations.touch(conv.getId());
        return reply;
    }

    /**
     * Streams the model's reply to the listener and stores it. If the stream breaks or the browser
     * leaves after some text arrived, that text is stored as the reply; if nothing arrived,
     * {@code undo} runs so the exchange leaves no trace.
     */
    private Message streamReply(long userId, Conversation conv, StreamListener listener, Runnable undo) {
        StringBuilder text = new StringBuilder();
        boolean slot = false;
        try {
            ChatRequest request = requestFor(userId, conv);
            AIProvider ai = providers.textProvider(request.apiKey());
            slot = streamSlots.tryAcquire(5, TimeUnit.SECONDS);
            if (!slot) {
                throw new AIException("Wanda is busy right now. Please try again in a moment.");
            }
            ai.stream(request, piece -> {
                text.append(piece);
                listener.onDelta(piece);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            undoOrKeep(conv, text, undo);
            throw new AIException("Request was interrupted", e);
        } catch (RuntimeException e) {
            undoOrKeep(conv, text, undo); // AI failures, a browser that left, or an unexpected error
            throw e;
        } finally {
            if (slot) {
                streamSlots.release();
            }
        }
        return finishReply(conv, text.toString());
    }

    /** Keeps whatever part of the reply arrived; if nothing did, undoes the whole exchange. */
    private void undoOrKeep(Conversation conv, CharSequence text, Runnable undo) {
        if (text.toString().isBlank()) {
            undo.run();
        } else {
            finishReply(conv, text.toString());
        }
    }

    private Message finishReply(Conversation conv, String text) {
        Message reply = messages.save(new Message(conv.getId(), Message.Role.ASSISTANT, text));
        conversations.touch(conv.getId());
        return reply;
    }

    private void checkRate(long userId) {
        if (!limiter.tryAcquire(userId)) {
            throw new ValidationException(429, "You're sending messages too quickly. Wait a moment and try again.");
        }
    }

    /** Runs the model on the worker pool and waits at most MODEL_TIMEOUT_SECONDS. */
    private String callModel(AIProvider ai, ChatRequest request) {
        return onWorker(() -> ai.reply(request), "The model took too long to respond. Please try again.", "The model failed: ");
    }

    /** Runs work on the worker pool with a hard timeout, turning every failure into an AppException. */
    private <T> T onWorker(Callable<T> work, String timeoutMessage, String failurePrefix) {
        Future<T> future = workers.submit(work);
        try {
            return future.get(MODEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AIException(timeoutMessage);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AppException app) {
                throw app;
            }
            throw new AIException(failurePrefix + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIException("Request was interrupted", e);
        }
    }

    /* ------------------------------------------------------ temporary chats */

    /** True if the id looks like one made by the browser (letters, digits and dashes). */
    public static boolean validTempId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{8,64}");
    }

    /**
     * Streams a reply in a temporary chat (or regenerates the last one). Nothing is saved: the
     * conversation exists only in memory. If the reply is cut short, the part that arrived is kept.
     */
    public Message tempStreaming(long userId, String tempId, String text, List<Attachment> files,
                                 boolean regenerate, Runnable onStart, java.util.function.Consumer<String> onDelta) {
        if (!validTempId(tempId)) {
            throw new ValidationException("Invalid temporary chat id");
        }
        TempChatStore.TempChat chat = temp.open(userId, tempId);
        synchronized (chat) {
            Message userMsg = null;
            Message removed = null;
            if (regenerate) {
                if (chat.last() == null || chat.last().getRole() != Message.Role.ASSISTANT) {
                    throw new ValidationException("There is no reply to regenerate yet");
                }
                checkRate(userId);
                removed = chat.removeLast();
            } else {
                userMsg = userMessage(0, draft(userId, text, files), files, "text");
            }
            List<Message> history = chat.snapshot();
            if (userMsg != null) {
                history.add(userMsg);
            }
            if (history.size() > CONTEXT_MESSAGES) {
                history = new java.util.ArrayList<>(history.subList(history.size() - CONTEXT_MESSAGES, history.size()));
            }

            StringBuilder reply = new StringBuilder();
            boolean slot = false;
            try {
                ChatRequest request = buildRequest(userId, history);
                AIProvider ai = providers.textProvider(request.apiKey());
                onStart.run();
                slot = streamSlots.tryAcquire(5, TimeUnit.SECONDS);
                if (!slot) {
                    throw new AIException("Wanda is busy right now. Please try again in a moment.");
                }
                ai.stream(request, piece -> {
                    reply.append(piece);
                    onDelta.accept(piece);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                keepOrRestore(chat, userMsg, removed, reply);
                throw new AIException("Request was interrupted", e);
            } catch (RuntimeException e) {
                keepOrRestore(chat, userMsg, removed, reply);
                throw e;
            } finally {
                if (slot) {
                    streamSlots.release();
                }
            }
            if (userMsg != null) {
                chat.add(userMsg);
            }
            Message answer = new Message(0, Message.Role.ASSISTANT, reply.toString());
            chat.add(answer);
            return answer;
        }
    }

    /** After a failed or stopped temporary reply: keep what arrived, or put things back as they were. */
    private void keepOrRestore(TempChatStore.TempChat chat, Message userMsg, Message removed, CharSequence partial) {
        if (!partial.toString().isBlank()) {
            if (userMsg != null) {
                chat.add(userMsg);
            }
            chat.add(new Message(0, Message.Role.ASSISTANT, partial.toString()));
        } else if (removed != null) {
            chat.add(removed);
        }
    }

    /** Forgets a temporary chat. */
    public void discardTemp(long userId, String tempId) {
        if (validTempId(tempId)) {
            temp.discard(userId, tempId);
        }
    }

    /* ------------------------------------------------------------- pictures */

    /**
     * Creates a picture from a description and stores both the request and the picture in the chat.
     * Like {@link #send}, a failure leaves no trace so the user can simply try again.
     */
    public ChatResult createImage(long userId, Long conversationId, String prompt) {
        prompt = prompt == null ? "" : prompt.trim();
        if (prompt.isEmpty()) {
            throw new ValidationException("Describe the image you want first");
        }
        if (prompt.length() > MAX_IMAGE_PROMPT) {
            throw new ValidationException("Image descriptions are limited to " + MAX_IMAGE_PROMPT + " characters");
        }
        SettingsService.ImageCredentials creds = settings.imageCredentialsFor(userId);
        if (!creds.ready()) {
            throw new ValidationException(503, "Image creation isn't set up yet. Add a free Cloudflare key in Settings → Images "
                    + "(or CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN in wanda.properties).");
        }
        if (!imageLimiter.tryAcquire(userId)) {
            throw new ValidationException(429, "You're creating images too quickly. Wait a few minutes and try again.");
        }
        Turn turn = begin(userId, conversationId, prompt, List.of(), "image");
        try {
            ImageProvider provider = providers.imageProvider();
            String description = prompt;
            ImageProvider.GeneratedImage image = onWorker(() -> provider.generate(description, creds.accountId(), creds.token()),
                    "Creating the image took too long. Please try again.", "Image creation failed: ");
            Message reply = new Message(turn.conversation().getId(), Message.Role.ASSISTANT, prompt);
            reply.setKind("image");
            reply.setImageCount(1);
            reply.setImages("data:" + image.mime() + ";base64," + Base64.getEncoder().encodeToString(image.bytes()));
            messages.save(reply);
            conversations.touch(turn.conversation().getId());
            return new ChatResult(turn.conversation(), turn.userMessage(), reply);
        } catch (AppException e) {
            rollback(turn);
            throw e;
        }
    }

    /** The picture of one of the user's messages as a data URL, or null. */
    public String createdImage(long userId, long messageId) {
        return messages.loadCreatedImage(messageId, userId);
    }

    /* -------------------------------------------------------- conversations */

    /** The user's chats, pinned ones first, then most recently active first. */
    public List<Conversation> listConversations(long userId) {
        return conversations.findByOwner(userId);
    }

    public List<Message> history(long userId, long conversationId) {
        ownedConversation(userId, conversationId);
        return messages.findByOwner(conversationId);
    }

    /** Renames and/or pins a chat; pass null for whatever should stay as it is. */
    public Conversation update(long userId, long conversationId, String title, Boolean pinned) {
        Conversation conv = ownedConversation(userId, conversationId);
        if (title != null) {
            if (title.isBlank()) {
                throw new ValidationException("Title cannot be empty");
            }
            conv.setTitle(titleFrom(title));
            conversations.rename(conversationId, conv.getTitle());
        }
        if (pinned != null) {
            conv.setPinned(pinned);
            conversations.setPinned(conversationId, pinned);
        }
        return conv;
    }

    public void deleteConversation(long userId, long conversationId) {
        ownedConversation(userId, conversationId);
        conversations.delete(conversationId);
    }

    public int deleteAllConversations(long userId) {
        return conversations.deleteAllForUser(userId);
    }

    /** Loads a conversation and checks it belongs to the user. */
    private Conversation ownedConversation(long userId, long conversationId) {
        return conversations.findById(conversationId)
                .filter(c -> c.getUserId() == userId)
                .orElseThrow(() -> new ValidationException(404, "Conversation not found"));
    }

    private static String fileContext(List<Attachment> files) {
        StringBuilder sb = new StringBuilder();
        for (Attachment f : files) {
            if (f.kind() == Attachment.Kind.TEXT) {
                sb.append("--- Attached file: ").append(f.name()).append(" ---\n")
                  .append(f.text()).append("\n--- End of ").append(f.name()).append(" ---\n\n");
            }
        }
        return sb.toString().trim();
    }

    static String titleFrom(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 57) + "..." : oneLine;
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
