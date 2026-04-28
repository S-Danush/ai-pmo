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
    /** Executive strip: HIGH / MEDIUM / LOW derived from overall {@link #status}. */
    private String portfolioDeliveryRisk;
    /** vs demo baseline average PR hours (positive = slower). */
    private Double prDelayTrendPercent;
    /** Rough demo estimate from longest dwell past 24h threshold. */
    private Double estimatedDelayDays;
    /** Human-readable trend line (run-over-run when available, else vs baseline). */
    private String trendSummary;
    /** Plain-language explanation for RED/AMBER/GREEN. */
    private String reasonForStatus;
    /**
     * Executive-style narrative: counts of high-priority / stuck / PR delay / churn signals, as short
     * bullet lines (newlines between lead-in and bullets).
     */
    private String projectRiskSummary;
    /** Lightweight forecast when many tickets show long dwell; null when not triggered. */
    private String deliveryInsight;

    private DataQuality dataQuality;
    private boolean prDataAvailable;
    private boolean jiraDataAvailable;
}
