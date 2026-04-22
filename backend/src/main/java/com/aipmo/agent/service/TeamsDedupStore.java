package com.aipmo.agent.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory deduplication of Teams alerts per ticket id (resets on restart). */
@Component
public class TeamsDedupStore {

    private static final Duration COOLDOWN = Duration.ofHours(1);

    private final ConcurrentHashMap<String, Instant> lastSent = new ConcurrentHashMap<>();

    public boolean shouldSend(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return true;
        }
        Instant prev = lastSent.get(ticketId);
        if (prev == null) {
            return true;
        }
        return prev.plus(COOLDOWN).isBefore(Instant.now());
    }

    public void markSent(String ticketId) {
        if (ticketId != null && !ticketId.isBlank()) {
            lastSent.put(ticketId, Instant.now());
        }
    }
}
