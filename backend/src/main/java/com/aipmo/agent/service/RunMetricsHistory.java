package com.aipmo.agent.service;

import com.aipmo.agent.model.Ticket;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * In-memory snapshots from completed agent runs for run-over-run trend copy and dashboard charts.
 * Resets on process restart.
 */
@Component
public class RunMetricsHistory {

    private static final int MAX_SNAPSHOTS = 8;

    private final Deque<ProjectSnapshot> snapshots = new ArrayDeque<>();

    public Optional<ProjectSnapshot> getLastRunSnapshot() {
        ProjectSnapshot last = snapshots.peekLast();
        return Optional.ofNullable(last);
    }

    /** Oldest → newest for chart / trend UI. */
    public List<ProjectSnapshot> getRecentSnapshots() {
        return new ArrayList<>(snapshots);
    }

    public void recordCompletedRun(List<Ticket> analyzed) {
        if (analyzed == null || analyzed.isEmpty()) {
            return;
        }
        snapshots.addLast(ProjectSnapshot.from(analyzed, Instant.now()));
        while (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.removeFirst();
        }
    }

    public record ProjectSnapshot(
            double avgPrHours,
            double avgDwellHours,
            int ticketCount,
            Instant recordedAt) {

        static ProjectSnapshot from(List<Ticket> tickets, Instant at) {
            double avgPr =
                    tickets.stream().mapToInt(Ticket::getPrTime).average().orElse(0.0);
            double avgDwell =
                    tickets.stream().mapToInt(Ticket::getTimeInState).average().orElse(0.0);
            return new ProjectSnapshot(avgPr, avgDwell, tickets.size(), at);
        }
    }
}
