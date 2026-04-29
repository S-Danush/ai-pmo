package com.aipmo.agent.engine;

import com.aipmo.agent.dto.RootCauseDto;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.service.MetricsService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Derives structured root-cause analysis from ticket signals (flags, dwell, PR, dependency). */
@Component
public class RootCauseEngine {

    public record Analysis(RootCauseDto dto, List<String> explainabilityFactors, String summaryLine) {}

    public Analysis analyze(Ticket source, Set<String> flags, double medianPrHours, boolean prComparable) {
        List<String> reasons = new ArrayList<>();
        List<String> explain = new ArrayList<>();
        Set<String> primaryParts = new LinkedHashSet<>();

        int hours = source.getTimeInState();
        int pr = source.getPrTime();
        int bounce = Math.max(source.getBounceCount(), source.getPingPongTransitions());
        String pri = source.getPriority() != null ? source.getPriority().trim() : "Medium";
        String dep = source.getDependency() != null ? source.getDependency().trim().toUpperCase(Locale.ROOT) : "NONE";
        String ps = source.getPrStatus() != null ? source.getPrStatus().trim().toUpperCase(Locale.ROOT) : "";
        List<String> flagList = flags != null ? new ArrayList<>(flags) : List.of();

        explain.add("Time in current status: ~" + hours + " hrs");
        explain.add("Priority: " + pri);

        boolean blocked =
                flagList.contains(MetricsService.FLAG_BLOCKED)
                        || (source.getStatus() != null && "Blocked".equalsIgnoreCase(source.getStatus().trim()));
        if (blocked) {
            explain.add("Workflow: Blocked");
            reasons.add("Ticket is in Blocked — coordination or external wait is likely on the critical path");
            primaryParts.add("Blocked workflow");
        }

        if (flagList.contains(MetricsService.FLAG_PR_DELAY)
                || (prComparable && medianPrHours > 0 && pr > medianPrHours * 1.05 && pr > 0)) {
            explain.add("PR delay: Yes (review dwell above team baseline or PR_DELAY flag)");
            reasons.add("PR review taking longer than team average");
            primaryParts.add("PR review");
        } else if ("OPEN".equals(ps) && source.getPrAgeHours() != null && source.getPrAgeHours() >= 36) {
            explain.add("PR open: extended age on open pull request");
            reasons.add("Open PR aging — review queue or large diff likely gating merge");
            primaryParts.add("PR review");
        }

        if (!"NONE".equals(dep) && !dep.isBlank()) {
            explain.add("Dependency: " + prettyDep(dep));
            if (blocked || flagList.contains(MetricsService.FLAG_DEPENDENCY_RISK)) {
                reasons.add("Blocked by " + prettyDep(dep) + " dependency");
                primaryParts.add("dependency");
            } else {
                reasons.add("Tagged dependency on " + prettyDep(dep) + " — watch for external clock vs Jira dwell");
                primaryParts.add("dependency risk");
            }
        } else {
            explain.add("Dependency: None");
        }

        if (flagList.contains(MetricsService.FLAG_STUCK) || flagList.contains(MetricsService.FLAG_CRITICAL_STUCK)) {
            explain.add("Dwell: Stuck / critical dwell vs thresholds");
            if (hours >= 120) {
                reasons.add("In progress or current status for 120+ hours");
            } else if (hours >= 72) {
                reasons.add("Elevated dwell in current status (72+ hours)");
            } else {
                reasons.add("Dwell crossed internal “stuck” threshold for this cohort");
            }
            primaryParts.add("long dwell");
        } else if (hours >= 120) {
            explain.add("Dwell: 120+ hours in status");
            reasons.add("In current status for 120+ hours");
            primaryParts.add("long dwell");
        }

        if (flagList.contains(MetricsService.FLAG_BOUNCING) || bounce >= 3) {
            explain.add("Bounce / churn: high (status ping-pong)");
            reasons.add("Repeated QA ↔ dev handoffs — acceptance or scope may be unsettled");
            primaryParts.add("bouncing work");
        } else if (bounce >= 2) {
            explain.add("Bounce / churn: moderate");
            reasons.add("Multiple workflow transitions — clarify “done” for the current column");
            primaryParts.add("handoff friction");
        }

        if (flagList.contains(MetricsService.FLAG_DEV_NOT_STARTED)) {
            explain.add("Development signal: no commits yet in active lane");
            reasons.add("No material development started (no commits on linked branch)");
            primaryParts.add("dev not started");
        }
        if (flagList.contains(MetricsService.FLAG_PR_NOT_CREATED)) {
            explain.add("PR: not opened despite commits");
            reasons.add("Commits exist but no pull request opened for review");
            primaryParts.add("missing PR");
        }

        if (isHighPri(pri)) {
            explain.add("Priority band: High / Critical");
        }

        if (reasons.isEmpty()) {
            reasons.add("No elevated multi-signal pattern — delivery posture looks within normal variance");
            explain.add("Signals: within normal band for this simulation");
        }

        String confidence = pickConfidence(flagList, reasons.size(), blocked, dep, hours, bounce);
        String primary = buildPrimaryLabel(primaryParts);

        RootCauseDto dto =
                RootCauseDto.builder()
                        .reasons(reasons)
                        .primaryCause(primary)
                        .confidence(confidence)
                        .build();

        String summaryLine = primary + " — " + String.join("; ", reasons.subList(0, Math.min(2, reasons.size())));
        return new Analysis(dto, explain, summaryLine);
    }

    private static String prettyDep(String dep) {
        return switch (dep) {
            case "API" -> "external API";
            case "DESIGN" -> "design / UX";
            case "EXTERNAL_TEAM" -> "external team";
            default -> dep.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static boolean isHighPri(String p) {
        if (p == null) {
            return false;
        }
        String u = p.toLowerCase(Locale.ROOT);
        return u.contains("high") || u.contains("critical");
    }

    private static String buildPrimaryLabel(Set<String> parts) {
        if (parts.isEmpty()) {
            return "Stable execution";
        }
        List<String> ordered = new ArrayList<>(parts);
        if (ordered.size() >= 3) {
            return capitalizeWords(ordered.get(0)) + " + " + capitalizeWords(ordered.get(1)) + " bottleneck";
        }
        if (ordered.size() == 2) {
            return capitalizeWords(ordered.get(0)) + " + " + capitalizeWords(ordered.get(1)) + " bottleneck";
        }
        return capitalizeWords(ordered.get(0)) + " focus";
    }

    private static String capitalizeWords(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] bits = raw.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String b = bits[i];
            if (b.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(b.charAt(0))).append(b.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String pickConfidence(
            List<String> flags, int reasonCount, boolean blocked, String dep, int hours, int bounce) {
        int score = 0;
        if (flags.contains(MetricsService.FLAG_CRITICAL_STUCK)) {
            score += 3;
        }
        if (flags.contains(MetricsService.FLAG_STUCK)) {
            score += 2;
        }
        if (blocked) {
            score += 2;
        }
        if (!"NONE".equals(dep) && flags.contains(MetricsService.FLAG_DEPENDENCY_RISK)) {
            score += 2;
        }
        if (flags.contains(MetricsService.FLAG_PR_DELAY)) {
            score += 2;
        }
        if (flags.contains(MetricsService.FLAG_BOUNCING) || bounce >= 3) {
            score += 1;
        }
        if (hours >= 120) {
            score += 2;
        } else if (hours >= 72) {
            score += 1;
        }
        if (reasonCount >= 3) {
            score += 1;
        }
        if (score >= 6) {
            return "HIGH";
        }
        if (score >= 3) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
