package com.aipmo.agent.service;

import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.dto.ProjectStatus;
import com.aipmo.agent.dto.ProjectSummaryBundle;
import com.aipmo.agent.dto.ProjectSummaryDto;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.Severity;
import com.aipmo.agent.util.TicketWorkflow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class ProjectSummaryService {

    /** Demo baseline PR hours when no prior run exists. */
    private static final double DEMO_BASELINE_AVG_PR_HOURS = 12.0;
    /** Demo baseline average dwell (hours) when no prior run exists. */
    private static final double DEMO_BASELINE_AVG_DWELL_HOURS = 18.0;

    private final RunMetricsHistory runMetricsHistory;
    private final MetricsService metricsService;
    private final DeliveryPredictionService deliveryPredictionService;

    public ProjectSummaryService(
            RunMetricsHistory runMetricsHistory,
            MetricsService metricsService,
            DeliveryPredictionService deliveryPredictionService) {
        this.runMetricsHistory = runMetricsHistory;
        this.metricsService = metricsService;
        this.deliveryPredictionService = deliveryPredictionService;
    }

    public ProjectSummaryDto summarize(List<Ticket> analyzed, TicketDataLoad dataLoad) {
        return summarizeWithDelivery(analyzed, dataLoad).getSummary();
    }

    public ProjectSummaryBundle summarizeWithDelivery(List<Ticket> analyzed, TicketDataLoad dataLoad) {
        int total = analyzed.size();
        int stuck =
                (int)
                        analyzed.stream()
                                .filter(
                                        t ->
                                                t.getFlags() != null
                                                        && (t.getFlags().contains(MetricsService.FLAG_STUCK)
                                                                || t.getFlags()
                                                                        .contains(
                                                                                MetricsService
                                                                                        .FLAG_CRITICAL_STUCK)))
                                .count();
        int critical =
                (int)
                        analyzed.stream()
                                .filter(
                                        t ->
                                                Severity.HIGH.equalsIgnoreCase(s(t.getSeverity()))
                                                        || (t.getFlags() != null
                                                                && t.getFlags()
                                                                        .contains(
                                                                                MetricsService
                                                                                        .FLAG_CRITICAL_STUCK)))
                                .count();

        String topBottleneck = computeTopBottleneck(analyzed);
        ProjectStatus status = computeStatus(analyzed);
        String portfolioDeliveryRisk = portfolioRiskFromStatus(status);

        double batchAvgPr =
                analyzed.isEmpty()
                        ? 0.0
                        : analyzed.stream().mapToInt(Ticket::getPrTime).average().orElse(0.0);

        boolean prDataAvailable = resolvePrDataAvailable(analyzed, dataLoad);
        boolean jiraDataAvailable = resolveJiraDataAvailable(analyzed, dataLoad);
        DataQuality dataQuality = resolveDataQuality(analyzed, dataLoad);

        boolean prDataMissing = !prDataAvailable || batchAvgPr == 0.0;
        Double trend = null;
        if (!prDataMissing && DEMO_BASELINE_AVG_PR_HOURS > 0) {
            trend = ((batchAvgPr - DEMO_BASELINE_AVG_PR_HOURS) / DEMO_BASELINE_AVG_PR_HOURS) * 100.0;
        }

        double batchAvgDwell =
                analyzed.isEmpty()
                        ? 0.0
                        : analyzed.stream().mapToInt(Ticket::getTimeInState).average().orElse(0.0);

        int maxHours = analyzed.stream().mapToInt(Ticket::getTimeInState).max().orElse(0);
        double estDays = Math.max(0, (maxHours - 24) / 24.0);
        if (estDays > 0 && estDays < 0.25) {
            estDays = 0.25;
        }

        String trendSummary =
                buildTrendSummary(
                        runMetricsHistory.getLastRunSnapshot(),
                        batchAvgPr,
                        batchAvgDwell,
                        total,
                        prDataMissing);
        String reasonForStatus =
                buildReasonForStatus(status, total, stuck, critical, topBottleneck, prDataAvailable);

        int highPriorityCount = countHighPriority(analyzed);
        int highPriorityStuckCount = countHighPriorityStuck(analyzed);
        int prDelayCount = countFlag(analyzed, MetricsService.FLAG_PR_DELAY);
        int bouncingCount = countFlag(analyzed, MetricsService.FLAG_BOUNCING);
        int dependencyRiskCount = countFlag(analyzed, MetricsService.FLAG_DEPENDENCY_RISK);
        String projectRiskSummary =
                buildProjectRiskSummary(
                        status,
                        total,
                        highPriorityCount,
                        highPriorityStuckCount,
                        stuck,
                        prDelayCount,
                        bouncingCount,
                        dependencyRiskCount,
                        prDataAvailable,
                        trend);
        String deliveryInsight = buildDeliveryInsight(analyzed, total);

        MetricsService.StageTatStats stageTat = metricsService.computeStageTatAggregates(analyzed);

        int blockedCount =
                (int)
                        analyzed.stream()
                                .filter(
                                        t ->
                                                t.getStatus() != null
                                                        && "Blocked"
                                                                .equalsIgnoreCase(
                                                                        t.getStatus().trim()))
                                .count();

        ProjectSummaryDto partial =
                ProjectSummaryDto.builder()
                        .totalTickets(total)
                        .stuckTickets(stuck)
                        .criticalTickets(critical)
                        .topBottleneck(topBottleneck)
                        .status(status)
                        .portfolioDeliveryRisk(portfolioDeliveryRisk)
                        .prDelayTrendPercent(trend)
                        .estimatedDelayDays(estDays > 0 ? estDays : null)
                        .trendSummary(trendSummary)
                        .reasonForStatus(reasonForStatus)
                        .projectRiskSummary(projectRiskSummary)
                        .deliveryInsight(deliveryInsight)
                        .avgStageTat(stageTat.avgStageTat())
                        .stageInsights(stageTat.stageInsights())
                        .dataQuality(dataQuality)
                        .prDataAvailable(prDataAvailable)
                        .jiraDataAvailable(jiraDataAvailable)
                        .build();

        return deliveryPredictionService.mergeDeliveryLayer(
                partial,
                analyzed,
                dataLoad,
                stageTat,
                blockedCount,
                critical,
                stuck,
                prDelayCount,
                bouncingCount);
    }

    private static String computeDeliveryConfidence(
            int blockedCount,
            int criticalCount,
            int stuckTickets,
            int prDelayCount,
            int bouncingCount) {
        if (blockedCount >= 6 || criticalCount >= 8) {
            return "LOW";
        }
        if (stuckTickets >= 14 || prDelayCount >= 9 || bouncingCount >= 7) {
            return "LOW";
        }
        if (blockedCount >= 3 || prDelayCount >= 5 || bouncingCount >= 4 || stuckTickets >= 8) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    private static int countHighPriority(List<Ticket> tickets) {
        return (int) tickets.stream().filter(ProjectSummaryService::isHighOrCriticalPriority).count();
    }

    private static boolean isHighOrCriticalPriority(Ticket t) {
        String p = s(t.getPriority());
        if (p.isEmpty()) {
            return false;
        }
        String pl = p.toLowerCase(Locale.ROOT);
        return "high".equals(pl) || "critical".equals(pl);
    }

    private static int countHighPriorityStuck(List<Ticket> tickets) {
        return (int)
                tickets.stream()
                        .filter(
                                t ->
                                        isHighOrCriticalPriority(t)
                                                && t.getFlags() != null
                                                && (t.getFlags().contains(MetricsService.FLAG_STUCK)
                                                        || t.getFlags()
                                                                .contains(
                                                                        MetricsService
                                                                                .FLAG_CRITICAL_STUCK)))
                        .count();
    }

    private static int countFlag(List<Ticket> tickets, String flag) {
        return (int)
                tickets.stream()
                        .filter(
                                t ->
                                        t.getFlags() != null && t.getFlags().contains(flag))
                        .count();
    }

    private static String buildProjectRiskSummary(
            ProjectStatus status,
            int total,
            int highPriorityCount,
            int highPriorityStuckCount,
            int stuckTickets,
            int prDelayCount,
            int bouncingCount,
            int dependencyRiskCount,
            boolean prDataAvailable,
            Double prTrendPercent) {
        if (total == 0) {
            return "No tickets in this batch yet — nothing to call out.";
        }
        List<String> bullets = new ArrayList<>();

        if (highPriorityStuckCount > 0) {
            bullets.add(
                    highPriorityStuckCount == 1
                            ? "One high-priority ticket looks stuck in place longer than we'd like"
                            : highPriorityStuckCount
                                    + " high-priority tickets look stuck in place longer than we'd like");
        } else if (stuckTickets > 0) {
            bullets.add(
                    stuckTickets == 1
                            ? "One ticket shows unusual dwell in its current state"
                            : stuckTickets + " tickets show unusual dwell in their current state");
        }

        if (prDelayCount >= 2) {
            bullets.add("PR review or merge is running slower than usual across several items");
        } else if (prDelayCount == 1) {
            bullets.add("At least one item is waiting longer than peers on review or merge");
        } else if (prDataAvailable && prTrendPercent != null && prTrendPercent > 12.0) {
            bullets.add("PR cycle time is trending higher than usual for the batch");
        }

        if (bouncingCount >= 2) {
            bullets.add("Several tickets are bouncing between stages — usually a sign handoffs need tightening");
        } else if (bouncingCount == 1) {
            bullets.add("A ticket shows status churn that may mean scope or owners aren't quite settled");
        }

        if (dependencyRiskCount >= 2) {
            bullets.add(
                    "Several items have been open a while with an external dependency in the mix — worth naming owners on both sides");
        } else if (dependencyRiskCount == 1) {
            bullets.add(
                    "One ticket has been sitting with an external dependency flagged — easy to misread as “just dev slow”");
        }

        if (highPriorityStuckCount == 0
                && stuckTickets == 0
                && prDelayCount == 0
                && bouncingCount == 0
                && highPriorityCount >= 4) {
            bullets.add(
                    highPriorityCount
                            + " tickets are marked high or critical — worth confirming none are quietly waiting");
        }

        if (bullets.isEmpty()) {
            return "From this snapshot, nothing is clustering in a worrying way — keep the usual rhythm on reviews and dates.";
        }

        String leadIn =
                switch (status) {
                    case RED ->
                            "A few things are stacking up in ways that can quietly pull dates if we don't lean in:";
                    case AMBER ->
                            "Here's what stands out if you're scanning the portfolio this week:";
                    default ->
                            "Overall things look steady; a few spots still deserve a quick leadership read:";
                };

        StringBuilder sb = new StringBuilder(leadIn.length() + bullets.size() * 80);
        sb.append(leadIn).append('\n');
        for (String b : bullets) {
            sb.append("- ").append(b).append('\n');
        }
        return sb.toString().trim();
    }

    private static String buildDeliveryInsight(List<Ticket> analyzed, int total) {
        if (total == 0) {
            return null;
        }
        int longDwell =
                (int) analyzed.stream().filter(t -> t.getTimeInState() >= 48).count();
        int threshold = Math.max(5, (int) Math.ceil(total * 0.22));
        if (longDwell >= threshold) {
            return "Delivery timelines may slip if these are not resolved soon.";
        }
        return null;
    }

    private static boolean resolvePrDataAvailable(List<Ticket> analyzed, TicketDataLoad dataLoad) {
        if (dataLoad != null) {
            return dataLoad.prDataAvailable();
        }
        if (analyzed.isEmpty()) {
            return false;
        }
        return analyzed.get(0).isPrDataAvailable();
    }

    private static boolean resolveJiraDataAvailable(List<Ticket> analyzed, TicketDataLoad dataLoad) {
        if (dataLoad != null) {
            return dataLoad.jiraDataAvailable();
        }
        if (analyzed.isEmpty()) {
            return false;
        }
        return analyzed.get(0).isJiraDataAvailable();
    }

    private static DataQuality resolveDataQuality(List<Ticket> analyzed, TicketDataLoad dataLoad) {
        if (dataLoad != null) {
            return dataLoad.dataQuality();
        }
        if (analyzed.isEmpty()) {
            return DataQuality.MOCK;
        }
        DataQuality q = analyzed.get(0).getDataQuality();
        return q != null ? q : DataQuality.MOCK;
    }

    private static String buildTrendSummary(
            Optional<RunMetricsHistory.ProjectSnapshot> prior,
            double curAvgPr,
            double curAvgDwell,
            int ticketCount,
            boolean prDataMissing) {
        if (ticketCount == 0) {
            return "No tickets in this view.";
        }
        String prMissingLead = prDataMissing ? "PR data not available for this run. " : "";
        if (prior.isPresent()) {
            RunMetricsHistory.ProjectSnapshot p = prior.get();
            StringBuilder sb = new StringBuilder();
            if (!prDataMissing && p.avgPrHours() > 0.5 && curAvgPr > 0) {
                double pct = (curAvgPr - p.avgPrHours()) / p.avgPrHours() * 100.0;
                sb.append(
                        String.format(
                                "Average PR cycle time vs last agent run is %s by %.0f%%. ",
                                pct >= 0 ? "up" : "down",
                                Math.abs(pct)));
            }
            if (p.avgDwellHours() > 0.5 && curAvgDwell > 0) {
                double pctD = (curAvgDwell - p.avgDwellHours()) / p.avgDwellHours() * 100.0;
                sb.append(
                        String.format(
                                "Average time-in-status vs last run is %s by %.0f%%.",
                                pctD >= 0 ? "up" : "down",
                                Math.abs(pctD)));
            }
            if (sb.length() > 0) {
                return (prMissingLead + sb.toString().trim()).trim();
            }
        }
        if (prDataMissing) {
            double dwellPct =
                    DEMO_BASELINE_AVG_DWELL_HOURS > 0
                            ? ((curAvgDwell - DEMO_BASELINE_AVG_DWELL_HOURS)
                                    / DEMO_BASELINE_AVG_DWELL_HOURS
                                    * 100.0)
                            : 0.0;
            String dwellPart =
                    String.format(
                            "Average dwell vs reference baseline is %s by %.0f%%.",
                            dwellPct >= 0 ? "higher" : "lower",
                            Math.abs(dwellPct));
            return (prMissingLead + dwellPart).trim();
        }
        String prPart = "";
        if (DEMO_BASELINE_AVG_PR_HOURS > 0) {
            double prPct =
                    ((curAvgPr - DEMO_BASELINE_AVG_PR_HOURS) / DEMO_BASELINE_AVG_PR_HOURS) * 100.0;
            prPart =
                    String.format(
                            "PR cycle vs reference baseline is %s by %.0f%%. ",
                            prPct >= 0 ? "slower" : "faster",
                            Math.abs(prPct));
        }
        double dwellPct =
                DEMO_BASELINE_AVG_DWELL_HOURS > 0
                        ? ((curAvgDwell - DEMO_BASELINE_AVG_DWELL_HOURS)
                                / DEMO_BASELINE_AVG_DWELL_HOURS
                                * 100.0)
                        : 0.0;
        String dwellPart =
                String.format(
                        "Average dwell vs reference baseline is %s by %.0f%%.",
                        dwellPct >= 0 ? "higher" : "lower",
                        Math.abs(dwellPct));
        return (prPart + dwellPart).trim();
    }

    private static String buildReasonForStatus(
            ProjectStatus status,
            int total,
            int stuck,
            int critical,
            String topBottleneck,
            boolean prDataAvailable) {
        return switch (status) {
            case RED ->
                    String.format(
                            "Right now the board needs air cover — at least one high-severity item or very long "
                                    + "dwell (>%dh signals). About %d of %d tickets look stuck; %d sit in critical bands. "
                                    + "The loudest pattern in the flags: %s.",
                            48, stuck, total, critical, topBottleneck);
            case AMBER ->
                    String.format(
                            "We're in a watch posture — not a full red flag, but worth attention: %d stuck, "
                                    + "%d critical/high across %d tickets. Most common flag theme: %s.",
                            stuck, critical, total, topBottleneck);
            case GREEN ->
                    prDataAvailable
                            ? String.format(
                                    "Things look steady in this batch (%d tickets) — no medium/high severity "
                                            + "outliers jumped out. Still worth a light touch on PR and dwell as the week moves.",
                                    total)
                            : String.format(
                                    "Things look steady in this batch (%d tickets) — no medium/high severity "
                                            + "outliers jumped out. Keep an occasional eye on dwell as work lands.",
                                    total);
        };
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }

    private static String portfolioRiskFromStatus(ProjectStatus status) {
        return switch (status) {
            case RED -> "HIGH";
            case AMBER -> "MEDIUM";
            case GREEN -> "LOW";
        };
    }

    private static ProjectStatus computeStatus(List<Ticket> tickets) {
        boolean red =
                tickets.stream()
                        .anyMatch(
                                t ->
                                        Severity.HIGH.equalsIgnoreCase(s(t.getSeverity()))
                                                || (t.getFlags() != null
                                                        && t.getFlags()
                                                                .contains(
                                                                        MetricsService
                                                                                .FLAG_CRITICAL_STUCK)));
        if (red) {
            return ProjectStatus.RED;
        }
        boolean amber =
                tickets.stream()
                        .anyMatch(
                                t ->
                                        Severity.MEDIUM.equalsIgnoreCase(s(t.getSeverity()))
                                                || (t.getFlags() != null
                                                        && (t.getFlags().contains(MetricsService.FLAG_PR_DELAY)
                                                                || t.getFlags()
                                                                        .contains(
                                                                                MetricsService
                                                                                        .FLAG_DEPENDENCY_RISK))));
        if (amber) {
            return ProjectStatus.AMBER;
        }
        return ProjectStatus.GREEN;
    }

    private static String computeTopBottleneck(List<Ticket> tickets) {
        Map<String, Long> counts = new HashMap<>();
        for (Ticket t : tickets) {
            if (t.getFlags() == null || t.getFlags().isEmpty()) {
                continue;
            }
            for (String f : t.getFlags()) {
                counts.merge(f, 1L, Long::sum);
            }
        }
        if (counts.isEmpty()) {
            return "None";
        }
        return counts.entrySet().stream()
                .max(
                        Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                                .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse("None");
    }

    /** Flags considered for "delay" clustering messaging (optional use). */
    public static Stream<String> delayFlags(Ticket t) {
        if (t.getFlags() == null) {
            return Stream.empty();
        }
        return t.getFlags().stream()
                .filter(
                        f ->
                                Objects.equals(f, MetricsService.FLAG_STUCK)
                                        || Objects.equals(f, MetricsService.FLAG_CRITICAL_STUCK)
                                        || Objects.equals(f, MetricsService.FLAG_PR_DELAY)
                                        || Objects.equals(f, MetricsService.FLAG_DEPENDENCY_RISK));
    }
}
