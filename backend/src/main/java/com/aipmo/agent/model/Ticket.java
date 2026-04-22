package com.aipmo.agent.model;

import com.aipmo.agent.dto.DataQuality;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Ticket {

    private String id;
    /** Jira issue summary (issue title). */
    private String summary;
    private String status;
    /** e.g. To Do, In Progress, Done — from Jira status category when present. */
    private String statusCategory;
    private Instant createdAt;
    private Instant jiraUpdatedAt;
    /** Jira assignee displayName, or "Unassigned" when none. */
    private String assignee;
    /** User-facing status for UI (e.g. "Not Started" instead of "To Do"). */
    private String displayStatus;
    /**
     * Short progress label: On track / Delayed / Stuck — derived from time in state, for dashboard
     * use instead of raw hours.
     */
    private String progressLabel;
    /**
     * Comma-separated human risk labels; internal codes stay in {@link #flags} for processing.
     */
    private String flagSummary;
    /** Capped time in current Jira status (hours). */
    private int timeInState;
    private int prTime;
    @Builder.Default
    private int statusChanges = 0;
    @Builder.Default
    private int pingPongTransitions = 0;
    @Builder.Default
    private List<String> flags = new ArrayList<>();
    private String insight;
    private String nudge;
    private String rootCause;
    private String impact;
    private String recommendedAction;
    private String severity;
    private String trendIndicator;
    private String confidence;
    private Instant lastUpdated;

    /** e.g. Normal, Watch, At Risk — from dwell time. */
    private String agingBucket;
    /** User-facing: LOW, MEDIUM, HIGH. */
    private String deliveryRisk;
    /** Active vs No Activity (Jira update within 24h). */
    private String movementStatus;
    /** e.g. "6 days" — for cards and Team messages, no huge hour integers. */
    private String timeInStatusLabel;
    /** e.g. "2 days ago" — for UI. */
    private String lastActivityLabel;
    /**
     * UNASSIGNED, BLOCKED, AT_RISK, HEALTHY — for dashboard grouping. {@link
     * com.aipmo.agent.util.DeliveryViewEnricher}
     */
    private String viewGroup;

    @Builder.Default
    private DataQuality dataQuality = DataQuality.MOCK;
    @Builder.Default
    private boolean jiraDataAvailable = false;
    @Builder.Default
    private boolean prDataAvailable = false;
}
