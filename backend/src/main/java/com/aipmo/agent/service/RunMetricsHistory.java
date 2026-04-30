package com.aipmo.agent.service;

import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.TicketWorkflow;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    public Optional<ProjectSnapshot> previewSnapshot(List<Ticket> analyzed) {
        if (analyzed == null || analyzed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ProjectSnapshot.from(analyzed, Instant.now(), null));
    }

    public void recordCompletedRun(List<Ticket> analyzed) {
        if (analyzed == null || analyzed.isEmpty()) {
            return;
        }
        ProjectSnapshot prev = snapshots.peekLast();
        snapshots.addLast(ProjectSnapshot.from(analyzed, Instant.now(), prev));
        while (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.removeFirst();
        }
    }

    public record ProjectSnapshot(
            double avgPrHours,
            double avgDwellHours,
            int ticketCount,
            int doneCount,
            double avgTotalTatHours,
            double velocityTicketsPerDay,
            Instant recordedAt) {

        static ProjectSnapshot from(List<Ticket> tickets, Instant at, ProjectSnapshot previous) {
            double avgPr = tickets.stream().mapToInt(Ticket::getPrTime).average().orElse(0.0);
            double avgDwell = tickets.stream().mapToInt(Ticket::getTimeInState).average().orElse(0.0);
            int n = tickets.size();
            int done = (int) tickets.stream().filter(TicketWorkflow::isDone).count();
            double avgTat =
                    tickets.stream()
                            .filter(t -> t.getTotalTat() > 0)
                            .mapToInt(Ticket::getTotalTat)
                            .average()
                            .orElse(0.0);

            double velocity;
            if (previous != null) {
                double days =
                        Math.max(
                                1.0 / 96.0,
                                ChronoUnit.SECONDS.between(previous.recordedAt(), at) / 86400.0);
                int deltaDone = done - previous.doneCount();
                if (deltaDone > 0) {
                    velocity = deltaDone / days;
                } else {
                    velocity =
                            Math.max(
                                    0.12,
                                    Math.min(previous.velocityTicketsPerDay(), 8.0));
                }
            } else {
                velocity = Math.max(0.18, done / 36.0);
            }
            if (velocity < 0.1) {
                velocity = 0.22;
            }
            return new ProjectSnapshot(avgPr, avgDwell, n, done, avgTat, velocity, at);
        }
    }
}
