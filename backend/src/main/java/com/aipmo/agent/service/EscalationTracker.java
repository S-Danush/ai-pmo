package com.aipmo.agent.service;

import com.aipmo.agent.dto.AgentRunResponse;
import com.aipmo.agent.util.Severity;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks consecutive agent runs where a ticket remained HIGH severity (in-memory; resets on
 * restart).
 */
@Component
public class EscalationTracker {

    private final ConcurrentHashMap<String, Integer> consecutiveHigh = new ConcurrentHashMap<>();

    /**
     * Updates streak state for this ticket after metrics. Call once per ticket per run.
     *
     * @return escalation level (0 if not HIGH; 1 first HIGH streak; 2+ if HIGH persisted since
     *     last run)
     */
    public int recordAndGetLevel(String ticketId, boolean currentIsHigh, AgentRunResponse previousRun) {
        if (!currentIsHigh) {
            consecutiveHigh.remove(ticketId);
            return 0;
        }
        boolean wasHighInPrevious =
                previousRun != null
                        && previousRun.getTickets() != null
                        && previousRun.getTickets().stream()
                                .filter(t -> ticketId.equals(t.getId()))
                                .anyMatch(t -> Severity.HIGH.equalsIgnoreCase(s(t.getSeverity())));
        int next = wasHighInPrevious ? consecutiveHigh.getOrDefault(ticketId, 0) + 1 : 1;
        consecutiveHigh.put(ticketId, next);
        return next;
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }
}
