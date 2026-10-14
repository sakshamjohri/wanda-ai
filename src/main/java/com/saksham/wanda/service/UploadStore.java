package com.saksham.wanda.service;

import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.Attachment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files a user has uploaded but not sent yet. Kept in memory per user (not in the HTTP session,
 * so sessions stay small enough to be saved to disk) and dropped after {@link #TTL_MS}.
 *
 * @author Saksham Johri
 */
public class UploadStore {

    public static final int MAX_IMAGES = 3;
    private static final int MAX_PENDING = 20;
    private static final long TTL_MS = 30 * 60_000L;

    private record Entry(Attachment file, long storedAt) { }

    private final Map<Long, Map<String, Entry>> byUser = new ConcurrentHashMap<>();

    /** Stores a processed upload for the user. */
    public void put(long userId, Attachment file) {
        sweep();
        Map<String, Entry> mine = byUser.computeIfAbsent(userId, id -> new ConcurrentHashMap<>());
        if (mine.size() >= MAX_PENDING) {
            throw new ValidationException("Too many files waiting. Send your message or remove some files first.");
        }
        long pictures = mine.values().stream().filter(e -> e.file().kind() == Attachment.Kind.IMAGE).count();
        if (file.kind() == Attachment.Kind.IMAGE && pictures >= MAX_IMAGES) {
            throw new ValidationException("You can attach up to " + MAX_IMAGES + " pictures per message.");
        }
        mine.put(file.id(), new Entry(file, System.currentTimeMillis()));
    }

    /** Looks up the user's uploads by id, in the order given; unknown or expired ids are rejected. */
    public List<Attachment> resolve(long userId, Collection<String> ids) {
        Map<String, Entry> mine = byUser.getOrDefault(userId, Map.of());
        List<Attachment> files = new ArrayList<>();
        for (String id : ids) {
            Entry e = mine.get(id);
            if (e == null || expired(e)) {
                throw new ValidationException("An attached file expired. Please attach it again.");
            }
            files.add(e.file());
        }
        return files;
    }

    /** Removes one upload; returns whether it existed. */
    public boolean remove(long userId, String id) {
        Map<String, Entry> mine = byUser.get(userId);
        return mine != null && mine.remove(id) != null;
    }

    public void removeAll(long userId, Collection<String> ids) {
        ids.forEach(id -> remove(userId, id));
    }

    private static boolean expired(Entry e) {
        return System.currentTimeMillis() - e.storedAt() > TTL_MS;
    }

    private void sweep() {
        byUser.values().forEach(m -> m.values().removeIf(UploadStore::expired));
        byUser.values().removeIf(Map::isEmpty);
    }
}
