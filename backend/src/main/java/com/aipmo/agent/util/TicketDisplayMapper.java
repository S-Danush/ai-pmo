package com.aipmo.agent.util;

import com.aipmo.agent.service.MetricsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * User-facing status and progress text for API (dashboard) and notifications — avoids raw Jira
 * values and large hour counts.
 */
public final class TicketDisplayMapper {

    private TicketDisplayMapper() {}

    public static String toFriendlyStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Stuck";
        }
        String n = raw.trim();
        if (n.equalsIgnoreCase("Unknown")) {
            return "Stuck";
        }
        if (n.equalsIgnoreCase("In Progress")) {
            return "In Progress";
        }
        if (n.equalsIgnoreCase("Backlog")) {
            return "Backlog";
        }
        if (n.equalsIgnoreCase("Blocked")) {
            return "Blocked";
        }
        if (n.equalsIgnoreCase("Review")) {
            return "Review";
        }
        if (n.equalsIgnoreCase("To Do") || n.equalsIgnoreCase("ToDo") || n.equalsIgnoreCase("Open")) {
            return "Not Started";
        }
        if (n.equalsIgnoreCase("Done")
                || n.equalsIgnoreCase("Closed")
                || n.equalsIgnoreCase("Resolved")) {
            return "Completed";
        }
        return n;
    }

    /** “Stuck / Delayed / On track” for the dashboard, based on time in state only. */
    public static String toProgressLabel(int timeInStateHours) {
        if (timeInStateHours > 168) {
            return "Stuck";
        }
        if (timeInStateHours > 72) {
            return "Delayed";
        }
        return "On track";
    }

    /**
     * Qualitative phrase for copy (no hour integers). &gt;168 → long stuck; &gt;72 → no recent
     * progress; else a neutral/positive line.
     */
    public static String toTimeInStateNarrative(int timeInStateHours) {
        if (timeInStateHours > 168) {
            return "stuck for a long time";
        }
        if (timeInStateHours > 72) {
            return "no recent progress";
        }
        return "on track for now";
    }

    /**
     * Comma-separated labels for the UI, omitting data-quality flags that are noisy for readers.
     */
    public static String toFlagSummary(Set<String> flagSet) {
        if (flagSet == null || flagSet.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String f : flagSet) {
            String m = mapFlag(f);
            if (m != null) {
                out.add(m);
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        return String.join(", ", out);
    }

    public static String mapFlag(String f) {
        if (f == null) {
            return null;
        }
        return switch (f) {
            case MetricsService.FLAG_STUCK -> "Needs attention";
            case MetricsService.FLAG_CRITICAL_STUCK -> "High risk";
            case MetricsService.FLAG_PR_DELAY -> "Slow PR cycle";
            case MetricsService.FLAG_BOUNCING -> "Status churn";
            case MetricsService.FLAG_TREND_SPIKE -> "Dwell well above team average";
            case MetricsService.FLAG_SLOWDOWN -> "PR merge well above team average";
            case MetricsService.FLAG_BLOCKED -> "Blocked / dependency";
            case MetricsService.FLAG_DEPENDENCY_RISK -> "Dependency / wait risk";
            case MetricsService.FLAG_DEV_NOT_STARTED -> "No commits yet";
            case MetricsService.FLAG_PR_NOT_CREATED -> "PR not opened";
            case MetricsService.FLAG_MERGED_NOT_DEPLOYED -> "Merged, not deployed";
            case MetricsService.FLAG_ACTIVE_DEVELOPMENT -> "Recent commits";
            case MetricsService.FLAG_PR_DATA_MISSING, MetricsService.FLAG_DATA_INSUFFICIENT -> null;
            default -> null;
        };
    }
}
