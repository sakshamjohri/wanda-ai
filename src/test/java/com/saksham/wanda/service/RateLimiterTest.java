package com.saksham.wanda.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsUpToTheLimitThenBlocks() {
        RateLimiter<String> limiter = new RateLimiter<>(3, 60_000);
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
    }

    @Test
    void keysDoNotAffectEachOther() {
        RateLimiter<Long> limiter = new RateLimiter<>(1, 60_000);
        assertTrue(limiter.tryAcquire(1L));
        assertFalse(limiter.tryAcquire(1L));
        assertTrue(limiter.tryAcquire(2L));
    }

    @Test
    void oldRequestsFallOutOfTheWindow() throws InterruptedException {
        RateLimiter<String> limiter = new RateLimiter<>(1, 50);
        assertTrue(limiter.tryAcquire("k"));
        assertFalse(limiter.tryAcquire("k"));
        Thread.sleep(80);
        assertTrue(limiter.tryAcquire("k"));
    }
}
