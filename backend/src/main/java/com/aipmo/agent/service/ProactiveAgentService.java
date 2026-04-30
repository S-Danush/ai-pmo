package com.aipmo.agent.service;

import com.aipmo.agent.config.MetricsProperties;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.TicketWorkflow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Identifies tickets that warrant proactive manager attention (subset of portfolio; avoids
 * notification spam).
 */
@Service
public class ProactiveAgentService {

    private static final int IN_PROGRESS_NO_PR_HOURS = 40;

    private final MetricsProperties metricsProperties;

    public ProactiveAgentService(MetricsProperties metricsProperties) {
        this.metricsProperties = metricsProperties;
    }

    public List<Ticket> identifyOutliers(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return List.of();
        }
        int stuckH = Math.max(1, metricsProperties.getStuckHours());
        int bounceMin = Math.max(2, metricsProperties.getBouncePingPongMin());
        Set<String> seen = new LinkedHashSet<>();
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets) {
            if (t.getId() == null || t.getId().isBlank() || seen.contains(t.getId())) {
                continue;
            }
            if (qualifies(t, stuckH, bounceMin)) {
                seen.add(t.getId());
                out.add(t);
            }
        }
        return out;
    }

    private boolean qualifies(Ticket t, int stuckH, int bounceMin) {
        List<String> f = t.getFlags() != null ? t.getFlags() : List.of();
        boolean highPri = isHighOrCritical(t.getPriority());
        boolean stuckish =
                f.contains(MetricsService.FLAG_STUCK) || f.contains(MetricsService.FLAG_CRITICAL_STUCK);
        if (highPri && (stuckish || t.getTimeInState() >= stuckH)) {
            return true;
        }
        if (isBlocked(t) && hasExternalDependency(t)) {
            return true;
        }
        if (f.contains(MetricsService.FLAG_PR_DELAY) && highPri) {
            return true;
        }
        if (f.contains(MetricsService.FLAG_BOUNCING) && t.getBounceCount() >= bounceMin) {
            return true;
        }
        if (f.contains(MetricsService.FLAG_BOUNCING) && t.getPingPongTransitions() >= bounceMin) {
            return true;
        }
        if (inProgress(t)
                && t.getTimeInState() >= IN_PROGRESS_NO_PR_HOURS
                && (f.contains(MetricsService.FLAG_PR_NOT_CREATED)
                        || f.contains(MetricsService.FLAG_DEV_NOT_STARTED))) {
            return true;
        }
        return false;
    }

    private static boolean isBlocked(Ticket t) {
        return t.getStatus() != null && "Blocked".equalsIgnoreCase(t.getStatus().trim());
    }

    private static boolean hasExternalDependency(Ticket t) {
        String d = t.getDependency();
        return d != null && !d.isBlank() && !"NONE".equalsIgnoreCase(d.trim());
    }

    private static boolean isHighOrCritical(String priority) {
        if (priority == null || priority.isBlank()) {
            return false;
        }
        String p = priority.trim().toLowerCase(Locale.ROOT);
        return "high".equals(p) || "critical".equals(p);
    }

    private static boolean inProgress(Ticket t) {
        if (TicketWorkflow.isDone(t)) {
            return false;
        }
        String s = t.getStatus();
        return s != null && "In Progress".equalsIgnoreCase(s.trim());
    }
}
