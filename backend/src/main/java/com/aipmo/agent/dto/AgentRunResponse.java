package com.aipmo.agent.dto;

import com.aipmo.agent.model.Ticket;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunResponse {

    @Builder.Default
    private List<Ticket> tickets = new ArrayList<>();
    private ProjectSummaryDto projectSummary;
    /** Counts and breakdown for the dashboard "project health" strip. */
    private ProjectHealthDto projectHealth;
    /** Matches the {@code simulation} flag used for {@code POST /api/run-agent}. */
    @Builder.Default
    private boolean simulation = false;

    /** ISO-8601 timestamp when this payload was produced (dashboard “Last updated”). */
    private String generatedAt;
}
