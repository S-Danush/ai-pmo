package com.aipmo.agent.service;

import com.aipmo.agent.dto.TicketSuggestionDto;
import com.aipmo.agent.model.Ticket;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SuggestionService {

    private static final int MAX_ACTION_LEN = 220;
    private static final int MAX_REASON_LEN = 160;

    public List<TicketSuggestionDto> buildSuggestions(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return List.of();
        }
        Map<String, Ticket> byId = new LinkedHashMap<>();
        for (Ticket t : tickets) {
            if (t.getId() != null && !t.getId().isBlank()) {
                byId.putIfAbsent(t.getId(), t);
            }
        }
        List<Ticket> picked = new ArrayList<>();
        for (Ticket t : byId.values()) {
            if (qualifies(t)) {
                picked.add(t);
            }
        }
        picked.sort(
                Comparator.comparingInt(SuggestionService::severityRank)
                        .thenComparing(Ticket::getId, String.CASE_INSENSITIVE_ORDER));

        List<TicketSuggestionDto> out = new ArrayList<>(picked.size());
        for (Ticket t : picked) {
            out.add(
                    new TicketSuggestionDto(
                            t.getId(), shorten(buildReason(t), MAX_REASON_LEN), pickRecommendedAction(t)));
        }
        return out;
    }

    private static int severityRank(Ticket t) {
        String s = t.getSeverity();
        if (s == null) {
            return 2;
        }
        if ("HIGH".equalsIgnoreCase(s)) {
            return 0;
        }
        if ("MEDIUM".equalsIgnoreCase(s)) {
            return 1;
        }
        return 2;
    }

    private static boolean qualifies(Ticket t) {
        String sev = t.getSeverity();
        if (sev != null && ("HIGH".equalsIgnoreCase(sev) || "MEDIUM".equalsIgnoreCase(sev))) {
            return true;
        }
        List<String> flags = t.getFlags();
        if (flags == null) {
            return false;
        }
        return flags.contains(MetricsService.FLAG_STUCK)
                || flags.contains(MetricsService.FLAG_PR_DELAY)
                || flags.contains(MetricsService.FLAG_DEPENDENCY_RISK)
                || flags.contains(MetricsService.FLAG_BOUNCING);
    }

    private static String pickRecommendedAction(Ticket t) {
        String a = t.getRecommendedAction();
        if (a != null && !a.isBlank()) {
            return shorten(a.trim(), MAX_ACTION_LEN);
        }
        String fs = t.getFlagSummary();
        if (fs != null && !fs.isBlank()) {
            return shorten(fs.trim(), MAX_ACTION_LEN);
        }
        return "Needs attention";
    }

    private static String buildReason(Ticket t) {
        List<String> parts = new ArrayList<>();
        String priority = t.getPriority() != null ? t.getPriority().trim() : "";
        String display = friendlyStatus(t);
        int hours = t.getTimeInState();

        boolean highPri = isHighPriority(priority);
        boolean inProg = display.toLowerCase(Locale.ROOT).contains("progress");

        if (highPri && inProg && hours >= 48) {
            parts.add("High priority + long time in progress");
        } else if (highPri && inProg) {
            parts.add("High priority work in progress");
        } else if ("HIGH".equalsIgnoreCase(t.getSeverity())) {
            parts.add("High severity on delivery metrics");
        } else if ("MEDIUM".equalsIgnoreCase(t.getSeverity())) {
            parts.add("Medium severity — worth a proactive check");
        }

        List<String> flags = t.getFlags() != null ? t.getFlags() : List.of();
        if (flags.contains(MetricsService.FLAG_STUCK)) {
            parts.add("long or unusual dwell in current status");
        }
        if (flags.contains(MetricsService.FLAG_PR_DELAY)) {
            parts.add("PR review slower than peers");
        }
        if (flags.contains(MetricsService.FLAG_DEPENDENCY_RISK)) {
            parts.add("dependency / external wait risk");
        }
        if (flags.contains(MetricsService.FLAG_BOUNCING)) {
            parts.add("status churn (back-and-forth)");
        }

        if (parts.isEmpty()) {
            return "Metrics flagged this ticket for manager review";
        }
        return String.join(" · ", parts);
    }

    private static String friendlyStatus(Ticket t) {
        if (t.getDisplayStatus() != null && !t.getDisplayStatus().isBlank()) {
            return t.getDisplayStatus().trim();
        }
        return t.getStatus() != null ? t.getStatus().trim() : "";
    }

    private static boolean isHighPriority(String priority) {
        if (priority.isEmpty()) {
            return false;
        }
        String p = priority.toLowerCase(Locale.ROOT);
        return "high".equals(p) || "critical".equals(p);
    }

    private static String shorten(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }
}
