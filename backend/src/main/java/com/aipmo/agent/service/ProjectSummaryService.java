package com.aipmo.agent.service;

import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.dto.ProjectStatus;
import com.aipmo.agent.dto.ProjectSummaryDto;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.Severity;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

    public ProjectSummaryService(RunMetricsHistory runMetricsHistory) {
        this.runMetricsHistory = runMetricsHistory;
    }

    public ProjectSummaryDto summarize(List<Ticket> analyzed, TicketDataLoad dataLoad) {
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

        return ProjectSummaryDto.builder()
                .totalTickets(total)
                .stuckTickets(stuck)
                .criticalTickets(critical)
                .topBottleneck(topBottleneck)
                .status(status)
                .prDelayTrendPercent(trend)
                .estimatedDelayDays(estDays > 0 ? estDays : null)
                .trendSummary(trendSummary)
                .reasonForStatus(reasonForStatus)
                .dataQuality(dataQuality)
                .prDataAvailable(prDataAvailable)
                .jiraDataAvailable(jiraDataAvailable)
                .build();
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
                            "RED: at least one HIGH-severity item or critical dwell (>%dh signals). "
                                    + "%d of %d tickets appear stuck; %d in critical bands. "
                                    + "Dominant signal: %s.",
                            48, stuck, total, critical, topBottleneck);
            case AMBER ->
                    String.format(
                            "AMBER: elevated delay risk without critical breach — %d stuck, "
                                    + "%d critical/high, %d tickets reviewed. Most common flag cluster: %s.",
                            stuck, critical, total, topBottleneck);
            case GREEN ->
                    prDataAvailable
                            ? String.format(
                                    "GREEN: no medium/high severity outliers in this batch (%d tickets). "
                                            + "Continue monitoring PR and dwell metrics proactively.",
                                    total)
                            : String.format(
                                    "GREEN: no medium/high severity outliers in this batch (%d tickets). "
                                            + "Continue monitoring dwell metrics proactively.",
                                    total);
        };
    }

    private static String s(String v) {
        return v == null ? "" : v;
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
                                                        && t.getFlags()
                                                                .contains(MetricsService.FLAG_PR_DELAY)));
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
                                        || Objects.equals(f, MetricsService.FLAG_PR_DELAY));
    }
}
