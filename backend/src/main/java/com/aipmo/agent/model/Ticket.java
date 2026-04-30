package com.aipmo.agent.model;

import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.dto.RootCauseDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** Simulated priority for evaluation dashboards: Low, Medium, High, Critical. */
    private String priority;
    /**
     * Plain-language bottleneck label for grouping (e.g. "Pull request review slow", "No owner assigned").
     */
    private String bottleneckCategory;
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
    /**
     * Simulated hours spent in each SDLC stage (synthetic dataset). Keys e.g. BACKLOG, DEV, REVIEW,
     * QA, UAT, DONE — {@link #totalTat} equals the sum of values.
     */
    @Builder.Default
    private Map<String, Integer> stageDurations = new LinkedHashMap<>();

    /** Sum of {@link #stageDurations} values (hours). */
    private int totalTat;
    private int prTime;
    @Builder.Default
    private int statusChanges = 0;
    @Builder.Default
    private int pingPongTransitions = 0;
    /**
     * Simulated: times work moved between Dev and QA. Same cohort metric as {@link #pingPongTransitions} when
     * loaded from simulation; kept explicit for display.
     */
    @Builder.Default
    private int bounceCount = 0;
    @Builder.Default
    private List<String> flags = new ArrayList<>();
    private String insight;
    private String nudge;
    /** Multi-signal PMO-style reading; may mirror structured insight reasoning. */
    private String reasoning;
    private String rootCause;
    /** Structured multi-signal root cause (intelligence layer). */
    private RootCauseDto rootCauseAnalysis;
    /** Human-readable factors backing risk / insight (for “Why this?” UI). */
    @Builder.Default
    private List<String> explainabilityFactors = new ArrayList<>();
    private String impact;
    private String recommendedAction;
    /** Who should drive the recommended next step (reviewer, assignee, external team, …). */
    private String actionOwner;
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

    // --- Simulation / portfolio metadata (synthetic dataset; exposed for dashboards & demos) ---

    /**
     * Short cross-signal notes from metrics (e.g. Jira dwell vs PR vs dependency), for synthesis
     * and local AI copy.
     */
    @Builder.Default
    private List<String> correlationInsights = new ArrayList<>();

    /** NOT_CREATED, OPEN, IN_REVIEW, MERGED — from synthetic scenario. */
    private String prStatus;
    /** NONE, API, DESIGN, EXTERNAL_TEAM. */
    private String dependency;
    /** SIMPLE, MEDIUM, COMPLEX — delivery sizing for narratives. */
    private String complexity;

    // --- Synthetic Git / PR line (demo only; no live GitHub) ---

    /** Pull request number when a PR exists for this scenario. */
    private Integer prNumber;
    /** Placeholder GitHub-style PR URL (simulation). */
    private String prUrl;
    /** Typical feature branch name for the change. */
    private String branchName;
    /** Last commit timestamp on the PR branch (ISO-8601). */
    private Instant lastCommitAt;
    /** GitHub login style author for the PR (may differ from Jira assignee). */
    private String prAuthor;

    /** Synthetic commit subjects (smart-commit style SIM-NNNN prefixes). */
    @Builder.Default
    private List<String> commitMessages = new ArrayList<>();

    /** PR title (often contains SIM-NNNN). */
    private String prTitle;

    /** Same intent as {@link #prUrl} — demo-friendly explicit link field for judges. */
    private String prLink;

    /** Denormalized count of synthetic commits (matches {@link #commitMessages} size when loaded). */
    @Builder.Default
    private int commitCount = 0;

    /** Release tag after merge (simulation). */
    private String deploymentTag;

    /** Whether the merged change reached an environment (simulation — only meaningful when merged). */
    @Builder.Default
    private boolean deployed = false;

    private Instant deployedAt;

    /** DEV, QA, or PROD when deployed. */
    private String deployEnvironment;

    /** Hours the pull request has been open (simulated pipeline / review clock). */
    private Double prAgeHours;

    /** Synthetic reviewer queue delay (hours). */
    private Double reviewerDelayHours;

    /** Set when the user manually sends an update to Teams for this ticket (evaluation / demo). */
    private Instant lastNotifiedAt;
}
