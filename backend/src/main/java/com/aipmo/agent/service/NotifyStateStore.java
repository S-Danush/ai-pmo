package com.aipmo.agent.service;

import com.aipmo.agent.model.Ticket;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** In-memory “last manual Teams notify” timestamps for demo / evaluation (not persisted). */
@Component
public class NotifyStateStore {

    private final ConcurrentHashMap<String, Instant> lastNotifiedByTicketId = new ConcurrentHashMap<>();

    public void recordSent(String ticketId, Instant at) {
        if (ticketId != null && !ticketId.isBlank()) {
            lastNotifiedByTicketId.put(ticketId.trim(), at);
        }
    }

    public Instant getLastNotified(String ticketId) {
        if (ticketId == null) {
            return null;
        }
        return lastNotifiedByTicketId.get(ticketId.trim());
    }

    public Ticket merge(Ticket t) {
        if (t == null || t.getId() == null) {
            return t;
        }
        Instant at = lastNotifiedByTicketId.get(t.getId().trim());
        if (at == null) {
            return t;
        }
        return t.toBuilder().lastNotifiedAt(at).build();
    }

    public List<Ticket> mergeAll(List<Ticket> tickets) {
        if (tickets == null) {
            return List.of();
        }
        return tickets.stream().map(this::merge).collect(Collectors.toList());
    }
}
