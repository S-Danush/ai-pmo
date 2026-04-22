package com.aipmo.agent.util;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-slot in-memory cache with TTL.
 */
public final class TimedCache<T> {

    private final AtomicReference<Entry<T>> slot = new AtomicReference<>();

    public Optional<T> getIfFresh() {
        Entry<T> e = slot.get();
        if (e == null || Instant.now().isAfter(e.expiresAt)) {
            return Optional.empty();
        }
        return Optional.of(e.value);
    }

    public void put(T value, Duration ttl) {
        slot.set(new Entry<>(value, Instant.now().plus(ttl)));
    }

    public void invalidate() {
        slot.set(null);
    }

    private record Entry<T>(T value, Instant expiresAt) {}
}
