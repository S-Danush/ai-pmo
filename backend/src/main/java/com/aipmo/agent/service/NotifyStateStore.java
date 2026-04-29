package com.aipmo.agent.service;

import com.aipmo.agent.model.Ticket;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists manual Teams notification timestamps across agent runs so {@code lastNotifiedAt} is not
 * lost when the dashboard refreshes from a new snapshot.
 */
@Component
public class NotifyStateStore {

    private final ConcurrentHashMap<String, Instant> lastNotifiedByTicketId = new ConcurrentHashMap<>();

    /** Overlay stored notification times onto tickets from the latest run / metrics pass. */
    public List<Ticket> mergeAll(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return tickets == null ? List.of() : tickets;
        }
        List<Ticket> out = new ArrayList<>(tickets.size());
        for (Ticket t : tickets) {
            out.add(merge(t));
        }
        return out;
    }

    public Ticket merge(Ticket t) {
        if (t == null || t.getId() == null || t.getId().isBlank()) {
            return t;
        }
        Instant stored = lastNotifiedByTicketId.get(t.getId());
        if (stored == null) {
            return t;
        }
        Instant onTicket = t.getLastNotifiedAt();
        if (onTicket == null || stored.isAfter(onTicket)) {
            return t.toBuilder().lastNotifiedAt(stored).build();
        }
        return t;
    }

    public void recordNotification(String ticketId, Instant at) {
        if (ticketId == null || ticketId.isBlank() || at == null) {
            return;
        }
        lastNotifiedByTicketId.merge(ticketId, at, (a, b) -> a.isAfter(b) ? a : b);
    }

    /** Alias for {@link #recordNotification(String, Instant)} — used after a successful Teams send. */
    public void recordSent(String ticketId, Instant sentAt) {
        recordNotification(ticketId, sentAt);
    }
}
