package com.saksham.wanda.service;

import com.saksham.wanda.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary ("incognito") chats. Their messages live only in this object's memory: nothing is
 * written to the database or to disk, they never appear in the chat history, and they vanish when
 * the user leaves the chat, after {@link #TTL_MS} of inactivity, or when the server restarts.
 *
 * @author Saksham Johri
 */
public class TempChatStore {

    private static final long TTL_MS = 2 * 60 * 60_000L;
    private static final int MAX_CHATS_PER_USER = 5;
    private static final int MAX_MESSAGES = 40;

    /** One temporary conversation. Callers lock on the chat itself while they use it. */
    public static final class TempChat {
        private final List<Message> messages = new ArrayList<>();
        private volatile long lastUsed = System.currentTimeMillis();

        /** The messages so far (a copy, oldest first). */
        public List<Message> snapshot() {
            return new ArrayList<>(messages);
        }

        public void add(Message m) {
            messages.add(m);
            while (messages.size() > MAX_MESSAGES) {
                messages.remove(0);
            }
            lastUsed = System.currentTimeMillis();
        }

        /** Removes and returns the newest message, or null if there is none. */
        public Message removeLast() {
            return messages.isEmpty() ? null : messages.remove(messages.size() - 1);
        }

        public Message last() {
            return messages.isEmpty() ? null : messages.get(messages.size() - 1);
        }
    }

    private final Map<Long, Map<String, TempChat>> byUser = new ConcurrentHashMap<>();

    /** The user's temporary chat with this id, created on first use. */
    public TempChat open(long userId, String tempId) {
        sweep();
        Map<String, TempChat> mine = byUser.computeIfAbsent(userId, id -> new ConcurrentHashMap<>());
        if (!mine.containsKey(tempId) && mine.size() >= MAX_CHATS_PER_USER) {
            mine.entrySet().stream().min((a, b) -> Long.compare(a.getValue().lastUsed, b.getValue().lastUsed))
                    .ifPresent(oldest -> mine.remove(oldest.getKey()));
        }
        TempChat chat = mine.computeIfAbsent(tempId, id -> new TempChat());
        chat.lastUsed = System.currentTimeMillis();
        return chat;
    }

    /** Forgets a temporary chat for good. */
    public void discard(long userId, String tempId) {
        Map<String, TempChat> mine = byUser.get(userId);
        if (mine != null) {
            mine.remove(tempId);
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        byUser.values().forEach(m -> m.values().removeIf(c -> now - c.lastUsed > TTL_MS));
        byUser.values().removeIf(Map::isEmpty);
    }
}
