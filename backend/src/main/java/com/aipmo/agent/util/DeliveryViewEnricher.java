package com.aipmo.agent.util;

import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.service.MetricsService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * User-facing delivery metrics: aging bucket, risk, movement, UI group, and display strings.
 * Capped time-in-status avoids absurd hour values in the API.
 */
public final class DeliveryViewEnricher {

    public static final int MAX_HOURS_IN_STATUS = 90 * 24;

    public static final String AGING_NORMAL = "Normal";
    public static final String AGING_WATCH = "Watch";
    public static final String AGING_AT_RISK = "At Risk";

    public static final String RISK_LOW = "LOW";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_HIGH = "HIGH";

    public static final String MOVEMENT_ACTIVE = "Active";
    public static final String MOVEMENT_NO_ACTIVITY = "No Activity";

    public static final String GROUP_UNASSIGNED = "UNASSIGNED";
    public static final String GROUP_BLOCKED = "BLOCKED";
    public static final String GROUP_AT_RISK = "AT_RISK";
    public static final String GROUP_HEALTHY = "HEALTHY";

    private DeliveryViewEnricher() {}

    public static Ticket enrich(Ticket source, Instant now) {
        int h = capHours(source.getTimeInState());
        String aging = toAgingBucket(h);
        Instant jiraUpd = source.getJiraUpdatedAt() != null ? source.getJiraUpdatedAt() : source.getLastUpdated();
        if (jiraUpd == null) {
            jiraUpd = now;
        }
        long hoursSinceUpd = ChronoUnit.HOURS.between(jiraUpd, now);
        if (hoursSinceUpd < 0) {
            hoursSinceUpd = 0;
        }
        String movement = hoursSinceUpd <= 24 ? MOVEMENT_ACTIVE : MOVEMENT_NO_ACTIVITY;
        String deliveryRisk = computeDeliveryRisk(aging, movement, source, h);
        String viewGroup = computeViewGroupForTicket(source.getAssignee(), aging, movement, deliveryRisk);
        return source.toBuilder()
                .timeInState(h)
                .agingBucket(aging)
                .movementStatus(movement)
                .deliveryRisk(deliveryRisk)
                .viewGroup(viewGroup)
                .timeInStatusLabel(formatTimeInStatus(h))
                .lastActivityLabel(formatLastUpdatedAgo(jiraUpd, now))
                .build();
    }

    public static List<Ticket> enrichAll(List<Ticket> tickets, Instant now) {
        if (tickets == null) {
            return List.of();
        }
        return tickets.stream().map(t -> enrich(t, now)).toList();
    }

    public static int capHours(int hours) {
        if (hours < 0) {
            return 0;
        }
        return Math.min(hours, MAX_HOURS_IN_STATUS);
    }

    static String toAgingBucket(int hoursCapped) {
        double days = hoursCapped / 24.0;
        if (days < 2) {
            return AGING_NORMAL;
        }
        if (days <= 5) {
            return AGING_WATCH;
        }
        return AGING_AT_RISK;
    }

    private static String computeDeliveryRisk(
            String aging, String movement, Ticket t, int hoursCapped) {
        boolean unassigned = isUnassigned(t.getAssignee());
        if (unassigned) {
            if (AGING_AT_RISK.equals(aging) && MOVEMENT_NO_ACTIVITY.equals(movement)) {
                return RISK_HIGH;
            }
            if (hoursCapped > 5 * 24) {
                return RISK_HIGH;
            }
            return RISK_MEDIUM;
        }
        if (AGING_AT_RISK.equals(aging) && MOVEMENT_NO_ACTIVITY.equals(movement)) {
            return RISK_HIGH;
        }
        if (t.getFlags() != null) {
            if (t.getFlags().contains(MetricsService.FLAG_CRITICAL_STUCK)) {
                return RISK_HIGH;
            }
            if (t.getFlags().contains(MetricsService.FLAG_STUCK)
                    && MOVEMENT_NO_ACTIVITY.equals(movement)) {
                return RISK_HIGH;
            }
        }
        if (AGING_AT_RISK.equals(aging) || "HIGH".equalsIgnoreCase(t.getSeverity())) {
            return RISK_HIGH;
        }
        if (AGING_WATCH.equals(aging) || "MEDIUM".equalsIgnoreCase(t.getSeverity())) {
            return RISK_MEDIUM;
        }
        return RISK_LOW;
    }

    private static String computeViewGroup(String aging, String movement, String deliveryRisk) {
        if (AGING_AT_RISK.equals(aging) && MOVEMENT_NO_ACTIVITY.equals(movement)) {
            return GROUP_BLOCKED;
        }
        if (RISK_HIGH.equals(deliveryRisk)
                && MOVEMENT_NO_ACTIVITY.equals(movement)
                && (AGING_AT_RISK.equals(aging) || AGING_WATCH.equals(aging))) {
            return GROUP_BLOCKED;
        }
        if (RISK_HIGH.equals(deliveryRisk)
                || RISK_MEDIUM.equals(deliveryRisk)
                || AGING_AT_RISK.equals(aging)
                || AGING_WATCH.equals(aging)) {
            return GROUP_AT_RISK;
        }
        return GROUP_HEALTHY;
    }

    public static String computeViewGroupForTicket(
            String assignee, String aging, String movement, String deliveryRisk) {
        if (isUnassigned(assignee)) {
            return GROUP_UNASSIGNED;
        }
        return computeViewGroup(aging, movement, deliveryRisk);
    }

    private static boolean isUnassigned(String a) {
        if (a == null || a.isBlank()) {
            return true;
        }
        return "unassigned".equalsIgnoreCase(a.trim());
    }

    public static String formatTimeInStatus(int hoursCapped) {
        if (hoursCapped < 24) {
            return hoursCapped + (hoursCapped == 1 ? " hour" : " hours");
        }
        int d = hoursCapped / 24;
        int rem = hoursCapped % 24;
        if (rem < 3) {
            return d + (d == 1 ? " day" : " days");
        }
        return d + (d == 1 ? " day" : " days") + " " + rem + "h";
    }

    public static String formatLastUpdatedAgo(Instant jiraUpd, Instant now) {
        if (jiraUpd == null) {
            return "—";
        }
        long h = ChronoUnit.HOURS.between(jiraUpd, now);
        if (h < 0) {
            return "—";
        }
        if (h < 1) {
            return "just now";
        }
        if (h < 24) {
            return h + (h == 1 ? " hour ago" : " hours ago");
        }
        long d = h / 24;
        if (d < 14) {
            return d + (d == 1 ? " day ago" : " days ago");
        }
        long w = d / 7;
        if (w < 8) {
            return w + (w == 1 ? " week ago" : " weeks ago");
        }
        return "over a month ago";
    }
}
