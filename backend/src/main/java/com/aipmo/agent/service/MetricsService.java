package com.aipmo.agent.service;

import com.aipmo.agent.config.MetricsProperties;
import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.DeliveryViewEnricher;
import com.aipmo.agent.util.Severity;
import com.aipmo.agent.util.TicketDisplayMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    public static final String FLAG_STUCK = "STUCK";
    public static final String FLAG_CRITICAL_STUCK = "CRITICAL_STUCK";
    public static final String FLAG_PR_DELAY = "PR_DELAY";
    public static final String FLAG_BOUNCING = "BOUNCING";
    public static final String FLAG_TREND_SPIKE = "TREND_SPIKE";
    public static final String FLAG_SLOWDOWN = "SLOWDOWN";
    public static final String FLAG_PR_DATA_MISSING = "PR_DATA_MISSING";
    public static final String FLAG_DATA_INSUFFICIENT = "DATA_INSUFFICIENT";

    public static final String TREND_UP = "UP";
    public static final String TREND_DOWN = "DOWN";
    public static final String TREND_STABLE = "STABLE";

    private static final double TREND_PEER_RATIO = 1.15;

    private final MetricsProperties metricsProperties;

    public MetricsService(MetricsProperties metricsProperties) {
        this.metricsProperties = metricsProperties;
    }

    public List<Ticket> analyzeTickets(List<Ticket> tickets) {
        if (tickets.isEmpty()) {
            return List.of();
        }
        int batchSize = tickets.size();
        int minPr = Math.max(1, metricsProperties.getMinPrSamples());
        int minDwell = Math.max(1, metricsProperties.getMinDwellSamples());

        List<Integer> prPositiveSorted =
                tickets.stream().mapToInt(Ticket::getPrTime).filter(v -> v > 0).boxed().sorted().toList();
        int prSampleCount = prPositiveSorted.size();
        boolean prBatchMissing = prSampleCount == 0;
        double medianPr = medianSortedInts(prPositiveSorted);
        boolean prComparable = prSampleCount >= minPr;
        boolean prInsufficient = !prBatchMissing && prSampleCount < minPr;

        double avgPr = tickets.stream().mapToInt(Ticket::getPrTime).average().orElse(0.0);
        double avgTimeInState =
                tickets.stream().mapToInt(Ticket::getTimeInState).average().orElse(0.0);

        boolean dwellComparable = batchSize >= minDwell;
        boolean dwellInsufficient = !dwellComparable;
        boolean dataInsufficient = prInsufficient || dwellInsufficient;

        int stuckH = Math.max(1, metricsProperties.getStuckHours());
        int criticalH = Math.max(stuckH + 1, metricsProperties.getCriticalHours());
        double prDelayRatio = Math.max(1.0, metricsProperties.getPrDelayRatio());
        double anomalyRatio = Math.max(1.0, metricsProperties.getAnomalyRatio());
        int bounceMin = Math.max(1, metricsProperties.getBouncePingPongMin());

        if (log.isDebugEnabled()) {
            log.debug(
                    "metrics batch ticketCount={} avgPr={} medianPr={} prSampleCount={} prComparable={} "
                            + "avgDwell={} dwellComparable={} dataInsufficient={} prBatchMissing={}",
                    batchSize,
                    avgPr,
                    medianPr,
                    prSampleCount,
                    prComparable,
                    avgTimeInState,
                    dwellComparable,
                    dataInsufficient,
                    prBatchMissing);
        }

        Instant now = Instant.now();
        BatchStats stats =
                new BatchStats(
                        avgPr,
                        avgTimeInState,
                        medianPr,
                        prComparable,
                        prBatchMissing,
                        dwellComparable,
                        dataInsufficient,
                        prInsufficient,
                        stuckH,
                        criticalH,
                        prDelayRatio,
                        anomalyRatio,
                        bounceMin);

        return tickets.stream()
                .map(t -> analyzeOne(t, stats, now))
                .map(t -> DeliveryViewEnricher.enrich(t, now))
                .collect(Collectors.toList());
    }

    private record BatchStats(
            double avgPr,
            double avgTimeInState,
            double medianPr,
            boolean prComparable,
            boolean prBatchMissing,
            boolean dwellComparable,
            boolean dataInsufficient,
            boolean prInsufficient,
            int stuckHours,
            int criticalHours,
            double prDelayRatio,
            double anomalyRatio,
            int bouncePingPongMin) {}

    private Ticket analyzeOne(Ticket source, BatchStats s, Instant now) {
        Set<String> flagSet = new LinkedHashSet<>();
        int hours = source.getTimeInState();
        int pr = source.getPrTime();

        if (s.prBatchMissing()) {
            flagSet.add(FLAG_PR_DATA_MISSING);
        }
        if (s.dataInsufficient()) {
            flagSet.add(FLAG_DATA_INSUFFICIENT);
        }

        if (hours > s.stuckHours()) {
            flagSet.add(FLAG_STUCK);
        }
        if (hours > s.criticalHours()) {
            flagSet.add(FLAG_CRITICAL_STUCK);
        }

        if (s.prComparable() && pr > 0 && s.medianPr() > 0 && pr > s.medianPr() * s.prDelayRatio()) {
            flagSet.add(FLAG_PR_DELAY);
        }
        if (s.prComparable() && pr > 0 && s.medianPr() > 0 && pr > s.medianPr() * s.anomalyRatio()) {
            flagSet.add(FLAG_SLOWDOWN);
        }

        if (source.getPingPongTransitions() >= s.bouncePingPongMin()) {
            flagSet.add(FLAG_BOUNCING);
        }

        if (s.dwellComparable() && s.avgTimeInState() > 0 && hours > s.avgTimeInState() * s.anomalyRatio()) {
            flagSet.add(FLAG_TREND_SPIKE);
        }

        String severity = computeSeverity(flagSet);
        String trendIndicator =
                computeTrendIndicator(
                        hours,
                        pr,
                        s.avgTimeInState(),
                        s.medianPr(),
                        s.prComparable(),
                        s.prBatchMissing());

        if (log.isDebugEnabled()) {
            log.debug(
                    "metrics ticketId={} timeInStateH={} prTimeH={} pingPong={} flags={} severity={} trend={}",
                    source.getId(),
                    hours,
                    pr,
                    source.getPingPongTransitions(),
                    flagSet,
                    severity,
                    trendIndicator);
        }

        Ticket t =
                Ticket.builder()
                        .id(source.getId())
                        .summary(source.getSummary())
                        .status(source.getStatus())
                        .statusCategory(source.getStatusCategory())
                        .createdAt(source.getCreatedAt())
                        .jiraUpdatedAt(source.getJiraUpdatedAt())
                        .assignee(source.getAssignee())
                        .displayStatus(TicketDisplayMapper.toFriendlyStatus(source.getStatus()))
                        .progressLabel(TicketDisplayMapper.toProgressLabel(hours))
                        .flagSummary(TicketDisplayMapper.toFlagSummary(flagSet))
                        .timeInState(source.getTimeInState())
                        .prTime(source.getPrTime())
                        .statusChanges(source.getStatusChanges())
                        .pingPongTransitions(source.getPingPongTransitions())
                        .flags(new ArrayList<>(flagSet))
                        .insight(source.getInsight())
                        .nudge(source.getNudge())
                        .rootCause(source.getRootCause())
                        .impact(source.getImpact())
                        .recommendedAction(source.getRecommendedAction())
                        .severity(severity)
                        .trendIndicator(trendIndicator)
                        .confidence(source.getConfidence())
                        .lastUpdated(source.getJiraUpdatedAt() != null ? source.getJiraUpdatedAt() : now)
                        .dataQuality(
                                source.getDataQuality() != null ? source.getDataQuality() : DataQuality.MOCK)
                        .jiraDataAvailable(source.isJiraDataAvailable())
                        .prDataAvailable(source.isPrDataAvailable())
                        .build();
        return t;
    }

    /**
     * UP / DOWN vs cohort: dwell uses batch average; PR uses median when {@code prComparable}, else
     * PR branch is ignored.
     */
    private static String computeTrendIndicator(
            int timeInState,
            int prTime,
            double avgTimeInState,
            double medianPr,
            boolean prComparable,
            boolean prBatchMissing) {
        boolean prBranch = !prBatchMissing && prComparable && medianPr >= 1.0;
        boolean up =
                (avgTimeInState >= 1.0 && timeInState > avgTimeInState * TREND_PEER_RATIO)
                        || (prBranch && prTime > medianPr * TREND_PEER_RATIO);
        if (up) {
            return TREND_UP;
        }
        boolean down =
                avgTimeInState >= 1.0
                        && prBranch
                        && timeInState < avgTimeInState / TREND_PEER_RATIO
                        && prTime < medianPr / TREND_PEER_RATIO;
        if (down) {
            return TREND_DOWN;
        }
        return TREND_STABLE;
    }

    private String computeSeverity(Set<String> flags) {
        Set<String> f = new LinkedHashSet<>(flags);
        f.remove(FLAG_PR_DATA_MISSING);
        f.remove(FLAG_DATA_INSUFFICIENT);
        if (f.isEmpty()) {
            return Severity.LOW;
        }

        boolean critical = f.contains(FLAG_CRITICAL_STUCK);
        boolean stuck = f.contains(FLAG_STUCK);
        boolean prDelay = f.contains(FLAG_PR_DELAY);
        boolean slowdown = f.contains(FLAG_SLOWDOWN);
        boolean spike = f.contains(FLAG_TREND_SPIKE);
        boolean bounce = f.contains(FLAG_BOUNCING);

        if (critical) {
            return Severity.HIGH;
        }
        if (stuck && (prDelay || slowdown)) {
            return Severity.HIGH;
        }
        int coreDelay = 0;
        if (stuck) {
            coreDelay++;
        }
        if (prDelay) {
            coreDelay++;
        }
        if (slowdown) {
            coreDelay++;
        }
        if (coreDelay >= 2) {
            return Severity.HIGH;
        }
        if (f.size() >= 2) {
            return Severity.MEDIUM;
        }
        if (stuck || prDelay || slowdown) {
            return Severity.MEDIUM;
        }
        if (spike) {
            return Severity.MEDIUM;
        }
        if (bounce) {
            return Severity.LOW;
        }
        return Severity.LOW;
    }

    private static double medianSortedInts(List<Integer> sorted) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int n = sorted.size();
        int mid = n / 2;
        if (n % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }
}
