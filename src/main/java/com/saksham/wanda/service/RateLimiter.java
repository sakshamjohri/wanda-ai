package com.saksham.wanda.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limiter: at most {@code limit} requests per key within {@code windowMs}.
 * Each key's queue of timestamps is guarded by synchronizing on that queue, so
 * different keys (users, or IP and username pairs) never block each other.
 *
 * @param <K> what is being limited, for example a user id or a "ip|username" string
 */
public class RateLimiter<K> {

    /** Above this many tracked keys, idle ones are dropped so memory cannot grow without bound. */
    private static final int SWEEP_THRESHOLD = 5_000;

    private final int limit;
    private final long windowMs;
    private final Map<K, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    /** Records a request and returns false if the key is over the limit. */
    public boolean tryAcquire(K key) {
        long now = System.currentTimeMillis();
        if (hits.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
        Deque<Long> queue = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > windowMs) {
                queue.pollFirst();
            }
            if (queue.size() >= limit) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }

    /** Forgets keys whose newest request is older than the window. */
    private void sweep(long now) {
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long newest = e.getValue().peekLast();
                return newest == null || now - newest > windowMs;
            }
        });
    }
}
