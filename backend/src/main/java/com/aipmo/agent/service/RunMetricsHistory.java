package com.aipmo.agent.service;

import com.aipmo.agent.model.Ticket;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * In-memory snapshot from the last completed agent run for run-over-run trend copy. Resets on
 * process restart.
 */
@Component
public class RunMetricsHistory {

    private volatile ProjectSnapshot lastCompletedRun;

    public Optional<ProjectSnapshot> getLastRunSnapshot() {
        return Optional.ofNullable(lastCompletedRun);
    }

    public void recordCompletedRun(List<Ticket> analyzed) {
        if (analyzed == null || analyzed.isEmpty()) {
            return;
        }
        lastCompletedRun = ProjectSnapshot.from(analyzed, Instant.now());
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
