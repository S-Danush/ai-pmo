package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSummaryDto {

    private int totalTickets;
    private int stuckTickets;
    private int criticalTickets;
    private String topBottleneck;
    private ProjectStatus status;
    /** vs demo baseline average PR hours (positive = slower). */
    private Double prDelayTrendPercent;
    /** Rough demo estimate from longest dwell past 24h threshold. */
    private Double estimatedDelayDays;
    /** Human-readable trend line (run-over-run when available, else vs baseline). */
    private String trendSummary;
    /** Plain-language explanation for RED/AMBER/GREEN. */
    private String reasonForStatus;

    private DataQuality dataQuality;
    private boolean prDataAvailable;
    private boolean jiraDataAvailable;
}
