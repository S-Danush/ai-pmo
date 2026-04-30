package com.aipmo.agent.service;

import com.aipmo.agent.dto.DeliveryTicketCardDto;
import com.aipmo.agent.dto.ProjectSummaryBundle;
import com.aipmo.agent.dto.ProjectSummaryDto;
import com.aipmo.agent.dto.StageTimelineEntryDto;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.TicketWorkflow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
/**
 * Data-driven delivery forecast from Jira/workflow state, portfolio stage TaT, and Git/PR signals.
 * Per-ticket ETAs are deterministic — no LLM on this path.
 */
@Service
public class DeliveryPredictionService {

    private static final List<String> SDLC_ORDER =
            List.of("BACKLOG", "DEV", "REVIEW", "QA", "UAT", "DONE");

    private final RunMetricsHistory runMetricsHistory;

    public DeliveryPredictionService(RunMetricsHistory runMetricsHistory) {
        this.runMetricsHistory = runMetricsHistory;
    }

    /**
     * Adds prediction strings + delivery cards to an otherwise-built summary builder context.
     */
    public ProjectSummaryBundle mergeDeliveryLayer(
            ProjectSummaryDto partial,
            List<Ticket> analyzed,
            TicketDataLoad dataLoad,
            MetricsService.StageTatStats stageTat,
            int blockedCount,
            int critical,
            int stuck,
            int prDelayCount,
            int bouncingCount) {

        int remainingOpen =
                (int) analyzed.stream().filter(t -> !TicketWorkflow.isDone(t)).count();
        int doneCount =
                (int) analyzed.stream().filter(TicketWorkflow::isDone).count();
        double velocity = resolveVelocity(remainingOpen, doneCount, analyzed.size());
        long daysCeil = daysToComplete(remainingOpen, velocity);
        String goLiveDefault = LocalDate.now(ZoneOffset.UTC).plusDays(daysCeil).toString();
        String slowestFromMetrics = pickSlowestStage(stageTat.avgStageTat());

        FallbackNumbers fb =
                new FallbackNumbers(
                        velocity,
                        daysCeil,
                        goLiveDefault,
                        computeConfidence(blockedCount, critical, stuck, prDelayCount, bouncingCount),
                        slowestFromMetrics,
                        remainingOpen,
                        doneCount);

        String velocityLabel = formatVelocity(velocity);
        String slowest = slowestFromMetrics;
        String goLive = goLiveDefault;
        String confidence = fb.confidence;
        String reason = buildFallbackReason(fb, slowest, stageTat);

        List<DeliveryTicketCardDto> cards = buildCards(analyzed, stageTat, fb, slowest);

        ProjectSummaryDto merged =
                ProjectSummaryDto.builder()
                        .totalTickets(partial.getTotalTickets())
                        .stuckTickets(partial.getStuckTickets())
                        .criticalTickets(partial.getCriticalTickets())
                        .topBottleneck(partial.getTopBottleneck())
                        .status(partial.getStatus())
                        .portfolioDeliveryRisk(partial.getPortfolioDeliveryRisk())
                        .prDelayTrendPercent(partial.getPrDelayTrendPercent())
                        .estimatedDelayDays(partial.getEstimatedDelayDays())
                        .trendSummary(partial.getTrendSummary())
                        .reasonForStatus(partial.getReasonForStatus())
                        .projectRiskSummary(partial.getProjectRiskSummary())
                        .deliveryInsight(partial.getDeliveryInsight())
                        .avgStageTat(partial.getAvgStageTat())
                        .stageInsights(partial.getStageInsights())
                        .predictedGoLiveDate(goLive)
                        .deliveryVelocity(velocityLabel)
                        .slowestStage(slowest)
                        .deliveryConfidence(confidence)
                        .predictionReason(reason)
                        .dataQuality(partial.getDataQuality())
                        .prDataAvailable(partial.isPrDataAvailable())
                        .jiraDataAvailable(partial.isJiraDataAvailable())
                        .build();

        return new ProjectSummaryBundle(merged, cards);
    }

    static int badgeRank(DeliveryTicketCardDto c) {
        String b = c.getDeliveryStatus() == null ? "" : c.getDeliveryStatus();
        if ("DELAYED".equals(b)) {
            return 0;
        }
        if ("AT_RISK".equals(b)) {
            return 1;
        }
        return 2;
    }

    // --- Cards ---

    private List<DeliveryTicketCardDto> buildCards(
            List<Ticket> analyzed,
            MetricsService.StageTatStats stageTat,
            FallbackNumbers fb,
            String portfolioSlowest) {

        Map<String, Double> avg = stageTat.avgStageTat() != null ? stageTat.avgStageTat() : Map.of();
        List<DeliveryTicketCardDto> out = new ArrayList<>();
        double sumOpenTat =
                analyzed.stream()
                        .filter(t -> !TicketWorkflow.isDone(t))
                        .mapToInt(Ticket::getTotalTat)
                        .sum();

        for (Ticket t : analyzed) {
            List<StageTimelineEntryDto> timeline = timelineFor(t);
            String stage = inferSdlcStage(t);
            String eta = etaForTicket(t, fb, sumOpenTat, avg);
            String badge = badgeFor(t);
            String warn = resolveSlowHint(t, avg, portfolioSlowest);

            out.add(
                    DeliveryTicketCardDto.builder()
                            .ticketId(t.getId())
                            .title(t.getSummary() != null ? t.getSummary() : t.getId())
                            .assignee(t.getAssignee() != null ? t.getAssignee() : "Unassigned")
                            .currentStage(stage)
                            .totalHours(Math.max(0, t.getTotalTat()))
                            .estimatedCompletion(eta)
                            .taskComplexity(formatTaskComplexity(t))
                            .timelineNote(buildTimelineNote(t, stage))
                            .deliveryStatus(badge)
                            .stageTimeline(timeline)
                            .slowStageWarning(warn)
                            .build());
        }
        out.sort(
                Comparator.comparingInt(DeliveryPredictionService::badgeRank)
                        .thenComparing(DeliveryTicketCardDto::getTicketId));
        return out;
    }

    private static String formatTaskComplexity(Ticket t) {
        String c = t.getComplexity();
        if (c == null || c.isBlank()) {
            return "—";
        }
        String u = c.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "SIMPLE" -> "Simple";
            case "MEDIUM" -> "Medium";
            case "COMPLEX" -> "Complex";
            default -> {
                String lower = u.toLowerCase(Locale.ROOT);
                yield lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
            }
        };
    }

    /** One-line explanation for the Delivery card — Jira + Git factors only. */
    private static String buildTimelineNote(Ticket t, String sdlcStage) {
        List<String> parts = new ArrayList<>();
        if (sdlcStage != null && !sdlcStage.isBlank() && !"DONE".equals(sdlcStage)) {
            parts.add("Lane " + sdlcStage);
        }
        String comp = t.getComplexity();
        if (comp != null && !comp.isBlank()) {
            parts.add(comp.trim().toUpperCase(Locale.ROOT) + " scope");
        }
        if (t.isPrDataAvailable()) {
            Double prAge = t.getPrAgeHours();
            if (prAge != null && prAge > 6) {
                parts.add("PR ~" + Math.round(prAge) + "h open");
            }
            Double rev = t.getReviewerDelayHours();
            if (rev != null && rev > 4) {
                parts.add("review queue ~" + Math.round(rev) + "h");
            }
            if (t.getCommitCount() > 0) {
                parts.add(t.getCommitCount() + " commits");
            }
        }
        if (t.getTimeInState() > 0) {
            parts.add("Jira dwell " + t.getTimeInState() + "h");
        }
        if (parts.isEmpty()) {
            return "Estimate from workflow lane and portfolio stage averages.";
        }
        return String.join(" · ", parts);
    }

    private String etaForTicket(
            Ticket t,
            FallbackNumbers fb,
            double sumOpenTat,
            Map<String, Double> avgStageTat) {
        if (TicketWorkflow.isDone(t)) {
            return "Complete";
        }
        int tat = Math.max(1, t.getTotalTat());
        if (sumOpenTat > 0) {
            double share = tat / sumOpenTat;
            double est = Math.max(0.5, share * fb.daysCeil);
            return "~" + round1(est) + " days (spread)";
        }
        // No historical TaT on open tickets — portfolio-wide days would be identical for every card.
        double hours = estimatedRemainingHoursFromLane(t, avgStageTat);
        double daysFromModel = hours / 8.0;
        if (t.getFlags() != null && t.getFlags().contains(MetricsService.FLAG_BLOCKED)) {
            daysFromModel *= 1.25;
        }
        if (t.getFlags() != null && t.getFlags().contains(MetricsService.FLAG_STUCK)) {
            daysFromModel *= 1.15;
        }
        // `timeInState` is hours in current status — adds differentiation when stage model matches.
        daysFromModel += Math.min(14.0, Math.max(0, t.getTimeInState()) / 40.0);
        daysFromModel = Math.max(0.5, daysFromModel);
        if (hours >= 4.0) {
            return "~" + round1(daysFromModel) + " days (stage model)";
        }
        double queueShare = (double) fb.daysCeil / Math.max(1, fb.remainingOpen);
        return "~" + round1(Math.max(0.5, queueShare)) + " days (queue share)";
    }

    /**
     * Expected remaining wall-clock hours from current SDLC lane through UAT, using portfolio
     * stage averages when present and sane defaults when the cohort has no TaT history yet.
     */
    private static double estimatedRemainingHoursFromLane(
            Ticket t, Map<String, Double> avgStageTat) {
        String lane = inferSdlcStage(t);
        if ("DONE".equals(lane)) {
            return 0;
        }
        int start = pathStartIndex(lane);
        if (start < 0) {
            start = SDLC_ORDER.indexOf("DEV");
        }
        double sum = 0;
        List<String> path = SDLC_ORDER.subList(0, SDLC_ORDER.size() - 1); // exclude DONE
        for (int i = start; i < path.size(); i++) {
            String st = path.get(i);
            sum += effectiveAvgHours(st, avgStageTat);
        }
        return sum;
    }

    private static int pathStartIndex(String lane) {
        if (lane == null || lane.isBlank()) {
            return SDLC_ORDER.indexOf("DEV");
        }
        if ("BLOCKED".equals(lane)) {
            // Pipeline still ahead after unblock — count from DEV onward.
            return SDLC_ORDER.indexOf("DEV");
        }
        int idx = SDLC_ORDER.indexOf(lane.toUpperCase(Locale.ROOT));
        if (idx >= 0 && idx < SDLC_ORDER.size() - 1) {
            return idx;
        }
        return SDLC_ORDER.indexOf("DEV");
    }

    private static double effectiveAvgHours(String stage, Map<String, Double> avgStageTat) {
        double m = 0;
        if (avgStageTat != null && stage != null) {
            Double v = avgStageTat.get(stage);
            if (v != null && v >= 1.0) {
                m = v;
            }
        }
        if (m >= 1.0) {
            return m;
        }
        return defaultHoursForStage(stage);
    }

    private static double defaultHoursForStage(String stage) {
        if (stage == null) {
            return 32;
        }
        return switch (stage.toUpperCase(Locale.ROOT)) {
            case "BACKLOG" -> 28;
            case "DEV" -> 52;
            case "REVIEW" -> 22;
            case "QA" -> 36;
            case "UAT" -> 28;
            default -> 32;
        };
    }

    private String resolveSlowHint(
            Ticket t, Map<String, Double> avg, String portfolioSlowest) {
        return slowWarning(t, avg, portfolioSlowest);
    }

    private String badgeFor(Ticket t) {
        if (TicketWorkflow.isDone(t)) {
            return "ON_TRACK";
        }
        String risk = t.getDeliveryRisk() != null ? t.getDeliveryRisk().toUpperCase(Locale.ROOT) : "";
        if (risk.contains("HIGH") || (t.getFlags() != null && t.getFlags().contains(MetricsService.FLAG_BLOCKED))) {
            return "DELAYED";
        }
        if (t.getFlags() != null
                && (t.getFlags().contains(MetricsService.FLAG_STUCK)
                        || t.getFlags().contains(MetricsService.FLAG_PR_DELAY))) {
            return "AT_RISK";
        }
        if (t.getTimeInState() >= 72) {
            return "AT_RISK";
        }
        return "ON_TRACK";
    }

    private String slowWarning(Ticket t, Map<String, Double> avg, String portfolioSlowest) {
        if (t.getStageDurations() == null || t.getStageDurations().isEmpty()) {
            return null;
        }
        String worst = null;
        double worstRatio = 0;
        for (Map.Entry<String, Integer> e : t.getStageDurations().entrySet()) {
            if (e.getKey() == null || e.getKey().equalsIgnoreCase("DONE")) {
                continue;
            }
            int hours = e.getValue() == null ? 0 : e.getValue();
            if (hours <= 0) {
                continue;
            }
            Double mean = avg.get(e.getKey());
            if (mean == null || mean < 1e-6) {
                continue;
            }
            double ratio = hours / mean;
            if (ratio > worstRatio && ratio >= 1.35) {
                worstRatio = ratio;
                worst = e.getKey();
            }
        }
        if (worst != null) {
            return "⚠ " + worst + " taking longer than portfolio average";
        }
        if (portfolioSlowest != null
                && !portfolioSlowest.isBlank()
                && !"NONE".equalsIgnoreCase(portfolioSlowest)) {
            String cur = inferSdlcStage(t);
            if (cur.contains(portfolioSlowest) || portfolioSlowest.contains(cur)) {
                return "⚠ Portfolio bottleneck stage: " + portfolioSlowest;
            }
        }
        return null;
    }

    private List<StageTimelineEntryDto> timelineFor(Ticket t) {
        Map<String, Integer> m =
                t.getStageDurations() != null ? t.getStageDurations() : Map.of();
        List<StageTimelineEntryDto> rows = new ArrayList<>();
        for (String key : SDLC_ORDER) {
            Integer v = m.get(key);
            if (v != null && v > 0) {
                rows.add(StageTimelineEntryDto.builder().stage(key).hours(v).build());
            }
        }
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (SDLC_ORDER.contains(e.getKey())) {
                continue;
            }
            if (e.getValue() != null && e.getValue() > 0) {
                rows.add(
                        StageTimelineEntryDto.builder()
                                .stage(e.getKey())
                                .hours(e.getValue())
                                .build());
            }
        }
        return rows;
    }

    static String inferSdlcStage(Ticket t) {
        if (TicketWorkflow.isDone(t)) {
            return "DONE";
        }
        String s = t.getStatus() == null ? "" : t.getStatus().trim();
        if (s.equalsIgnoreCase("Backlog") || s.equalsIgnoreCase("To Do")) {
            return "BACKLOG";
        }
        if (s.equalsIgnoreCase("Review")) {
            return "REVIEW";
        }
        if (s.equalsIgnoreCase("Blocked")) {
            return "BLOCKED";
        }
        if (s.equalsIgnoreCase("In Progress")) {
            String pr = t.getPrStatus() == null ? "" : t.getPrStatus().toUpperCase(Locale.ROOT);
            if (pr.contains("IN_REVIEW") || pr.contains("REVIEW")) {
                return "REVIEW";
            }
            if (t.getBounceCount() > 2) {
                return "QA";
            }
            return "DEV";
        }
        return "DEV";
    }

    static String pickSlowestStage(Map<String, Double> avgStageTat) {
        if (avgStageTat == null || avgStageTat.isEmpty()) {
            return "DEV";
        }
        Optional<Map.Entry<String, Double>> best =
                avgStageTat.entrySet().stream()
                        .filter(e -> e.getKey() != null && !e.getKey().equalsIgnoreCase("DONE"))
                        .max(Comparator.comparingDouble(e -> e.getValue() != null ? e.getValue() : 0));
        return best.map(Map.Entry::getKey).orElse("DEV");
    }

    private double resolveVelocity(int remainingOpen, int doneCount, int total) {
        Optional<RunMetricsHistory.ProjectSnapshot> snap = runMetricsHistory.getLastRunSnapshot();
        double v =
                snap.map(RunMetricsHistory.ProjectSnapshot::velocityTicketsPerDay)
                        .orElse(Math.max(0.2, (total - remainingOpen) / 40.0));
        v = Math.max(0.12, v);
        return v;
    }

    private static long daysToComplete(int remainingOpen, double velocity) {
        double est = remainingOpen / Math.max(velocity, 0.1);
        return (long) Math.ceil(Math.max(0.0, est));
    }

    private static String formatVelocity(double v) {
        return String.format(Locale.US, "≈%.1f tickets / day", v);
    }

    private static double round1(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    private static String computeConfidence(
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

    private static String buildFallbackReason(
            FallbackNumbers fb, String slowest, MetricsService.StageTatStats stageTat) {
        String stageHint =
                stageTat.stageInsights() != null && stageTat.stageInsights().containsKey(slowest)
                        ? stageTat.stageInsights().get(slowest)
                        : "";
        return String.format(
                Locale.US,
                "About %d ticket(s) still open at ~%.1f tickets/day → ~%d day(s) to clear. Slowest average stage: %s.%s%s",
                fb.remainingOpen,
                fb.velocity,
                fb.daysCeil,
                slowest,
                stageHint.isEmpty() ? "" : " " + stageHint,
                "");
    }

    private record FallbackNumbers(
            double velocity,
            long daysCeil,
            String goLiveDefault,
            String confidence,
            String slowestStage,
            int remainingOpen,
            int doneCount) {}
}
