package com.aipmo.agent.service;

import com.aipmo.agent.config.MetricsProperties;
import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.engine.ActionEngine;
import com.aipmo.agent.engine.RootCauseEngine;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.DeliveryViewEnricher;
import com.aipmo.agent.util.Severity;
import com.aipmo.agent.util.TicketDisplayMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** Jira-style blocked column — distinct from metric “stuck” dwell. */
    public static final String FLAG_BLOCKED = "BLOCKED";
    /** External dependency (non-NONE) and dwell past priority-based threshold. */
    public static final String FLAG_DEPENDENCY_RISK = "DEPENDENCY_RISK";

    /** Synthetic Git: zero commits in active workflow lanes. */
    public static final String FLAG_DEV_NOT_STARTED = "DEV_NOT_STARTED";
    /** Commits recorded but PR not opened (simulated smart commits without PR). */
    public static final String FLAG_PR_NOT_CREATED = "PR_NOT_CREATED";
    /** Merged PR not yet visible in an environment (pipeline delay). */
    public static final String FLAG_MERGED_NOT_DEPLOYED = "MERGED_NOT_DEPLOYED";
    /** Recent commit activity on the branch. */
    public static final String FLAG_ACTIVE_DEVELOPMENT = "ACTIVE_DEVELOPMENT";

    private static final int DEPENDENCY_RISK_HOURS_HIGH_PRIORITY = 24;
    private static final int DEPENDENCY_RISK_HOURS_DEFAULT = 48;

    public static final String TREND_UP = "UP";
    public static final String TREND_DOWN = "DOWN";
    public static final String TREND_STABLE = "STABLE";

    private static final double TREND_PEER_RATIO = 1.15;

    private final MetricsProperties metricsProperties;
    private final RootCauseEngine rootCauseEngine;
    private final ActionEngine actionEngine;

    public MetricsService(
            MetricsProperties metricsProperties, RootCauseEngine rootCauseEngine, ActionEngine actionEngine) {
        this.metricsProperties = metricsProperties;
        this.rootCauseEngine = rootCauseEngine;
        this.actionEngine = actionEngine;
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

        if (source.getStatus() != null && "Blocked".equalsIgnoreCase(source.getStatus().trim())) {
            flagSet.add(FLAG_BLOCKED);
        }

        if (s.dwellComparable() && s.avgTimeInState() > 0 && hours > s.avgTimeInState() * s.anomalyRatio()) {
            flagSet.add(FLAG_TREND_SPIKE);
        }

        if (hasExternalDependency(source.getDependency())
                && hours > dependencyRiskThresholdHours(source.getPriority())) {
            flagSet.add(FLAG_DEPENDENCY_RISK);
        }

        applyGitSimulationFlags(source, flagSet, now);

        List<String> correlationInsights = buildCorrelationInsights(source, pr, flagSet);

        String severity = computeSeverity(flagSet);
        String trendIndicator =
                computeTrendIndicator(
                        hours,
                        pr,
                        s.avgTimeInState(),
                        s.medianPr(),
                        s.prComparable(),
                        s.prBatchMissing());

        Ticket probe =
                source.toBuilder().flags(new ArrayList<>(flagSet)).build();
        RootCauseEngine.Analysis rc =
                rootCauseEngine.analyze(source, flagSet, s.medianPr(), s.prComparable());
        ActionEngine.ActionPlan plan = actionEngine.decide(probe, rc.dto());

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
                        .priority(source.getPriority())
                        .bottleneckCategory(source.getBottleneckCategory())
                        .displayStatus(TicketDisplayMapper.toFriendlyStatus(source.getStatus()))
                        .progressLabel(TicketDisplayMapper.toProgressLabel(hours))
                        .flagSummary(TicketDisplayMapper.toFlagSummary(flagSet))
                        .timeInState(source.getTimeInState())
                        .prTime(source.getPrTime())
                        .statusChanges(source.getStatusChanges())
                        .pingPongTransitions(source.getPingPongTransitions())
                        .bounceCount(source.getBounceCount())
                        .prStatus(source.getPrStatus())
                        .dependency(source.getDependency())
                        .correlationInsights(new ArrayList<>(correlationInsights))
                        .complexity(source.getComplexity())
                        .prNumber(source.getPrNumber())
                        .prUrl(source.getPrUrl())
                        .branchName(source.getBranchName())
                        .lastCommitAt(source.getLastCommitAt())
                        .prAuthor(source.getPrAuthor())
                        .commitMessages(
                                source.getCommitMessages() != null
                                        ? new ArrayList<>(source.getCommitMessages())
                                        : new ArrayList<>())
                        .prTitle(source.getPrTitle())
                        .prLink(source.getPrLink())
                        .commitCount(source.getCommitCount())
                        .deploymentTag(source.getDeploymentTag())
                        .deployed(source.isDeployed())
                        .deployedAt(source.getDeployedAt())
                        .deployEnvironment(source.getDeployEnvironment())
                        .prAgeHours(source.getPrAgeHours())
                        .reviewerDelayHours(source.getReviewerDelayHours())
                        .flags(new ArrayList<>(flagSet))
                        .insight(source.getInsight())
                        .nudge(source.getNudge())
                        .reasoning(source.getReasoning())
                        .rootCause(rc.summaryLine())
                        .rootCauseAnalysis(rc.dto())
                        .explainabilityFactors(new ArrayList<>(rc.explainabilityFactors()))
                        .impact(source.getImpact())
                        .recommendedAction(plan.recommendedAction())
                        .actionOwner(plan.actionOwner())
                        .severity(severity)
                        .trendIndicator(trendIndicator)
                        .confidence(rc.dto().getConfidence())
                        .lastUpdated(source.getJiraUpdatedAt() != null ? source.getJiraUpdatedAt() : now)
                        .dataQuality(
                                source.getDataQuality() != null ? source.getDataQuality() : DataQuality.MOCK)
                        .jiraDataAvailable(source.isJiraDataAvailable())
                        .prDataAvailable(source.isPrDataAvailable())
                        .lastNotifiedAt(source.getLastNotifiedAt())
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
        boolean blocked = f.contains(FLAG_BLOCKED);
        boolean depRisk = f.contains(FLAG_DEPENDENCY_RISK);

        if (critical) {
            return Severity.HIGH;
        }
        if (depRisk && (stuck || blocked || prDelay || slowdown)) {
            return Severity.HIGH;
        }
        if (blocked && stuck) {
            return Severity.HIGH;
        }
        if (blocked && (prDelay || slowdown)) {
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
        if (blocked) {
            return Severity.MEDIUM;
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
        if (depRisk) {
            return Severity.MEDIUM;
        }
        if (f.contains(FLAG_MERGED_NOT_DEPLOYED)) {
            return Severity.MEDIUM;
        }
        if (f.contains(FLAG_PR_NOT_CREATED)) {
            return Severity.MEDIUM;
        }
        if (f.contains(FLAG_DEV_NOT_STARTED)) {
            return Severity.MEDIUM;
        }
        if (bounce) {
            return Severity.LOW;
        }
        return Severity.LOW;
    }

    private static boolean hasExternalDependency(String dependency) {
        if (dependency == null || dependency.isBlank()) {
            return false;
        }
        return !"NONE".equalsIgnoreCase(dependency.trim());
    }

    private static boolean isHighPriorityForDependency(String priority) {
        if (priority == null || priority.isBlank()) {
            return false;
        }
        String p = priority.trim();
        return "High".equalsIgnoreCase(p) || "Critical".equalsIgnoreCase(p);
    }

    private static int dependencyRiskThresholdHours(String priority) {
        return isHighPriorityForDependency(priority)
                ? DEPENDENCY_RISK_HOURS_HIGH_PRIORITY
                : DEPENDENCY_RISK_HOURS_DEFAULT;
    }

    /**
     * Jira “In Progress” (or same category) correlated with missing PR signal.
     */
    private static boolean isInProgressWorkflow(Ticket t) {
        String s = t.getStatus();
        if (s != null && "In Progress".equalsIgnoreCase(s.trim())) {
            return true;
        }
        String cat = t.getStatusCategory();
        return cat != null && "In Progress".equalsIgnoreCase(cat.trim());
    }

    private static boolean isBacklogOrDone(Ticket t) {
        String s = t.getStatus();
        if (s == null || s.isBlank()) {
            return false;
        }
        String n = s.trim();
        return "Backlog".equalsIgnoreCase(n) || "Done".equalsIgnoreCase(n);
    }

    /** Eligible lanes where zero commits implies dev-not-started (excludes backlog/done). */
    private static boolean devNotStartedWorkflowLane(Ticket t) {
        if (isBacklogOrDone(t)) {
            return false;
        }
        String s = t.getStatus();
        if (s == null || s.isBlank()) {
            return false;
        }
        String n = s.trim();
        return "In Progress".equalsIgnoreCase(n)
                || "Review".equalsIgnoreCase(n)
                || "Blocked".equalsIgnoreCase(n);
    }

    private static boolean prStatusNotCreated(String ps) {
        return ps == null || ps.isBlank() || "NOT_CREATED".equalsIgnoreCase(ps.trim());
    }

    private static boolean prStatusOpen(String ps) {
        return ps != null && "OPEN".equalsIgnoreCase(ps.trim());
    }

    private static boolean prStatusMerged(String ps) {
        return ps != null && "MERGED".equalsIgnoreCase(ps.trim());
    }

    private void applyGitSimulationFlags(Ticket source, Set<String> flagSet, Instant now) {
        int commits = Math.max(0, source.getCommitCount());
        String ps = source.getPrStatus();
        if (commits == 0 && devNotStartedWorkflowLane(source)) {
            flagSet.add(FLAG_DEV_NOT_STARTED);
        }
        if (commits > 0 && prStatusNotCreated(ps)) {
            flagSet.add(FLAG_PR_NOT_CREATED);
        }
        Double prAge = source.getPrAgeHours();
        if (prStatusOpen(ps)
                && prAge != null
                && prAge > metricsProperties.getGitPrDelayThresholdHours()) {
            flagSet.add(FLAG_PR_DELAY);
        }
        if (prStatusMerged(ps) && !source.isDeployed()) {
            flagSet.add(FLAG_MERGED_NOT_DEPLOYED);
        }
        Instant lastCommit = source.getLastCommitAt();
        if (lastCommit != null) {
            long hours = ChronoUnit.HOURS.between(lastCommit, now);
            if (hours >= 0 && hours <= metricsProperties.getGitActiveDevelopmentHours()) {
                flagSet.add(FLAG_ACTIVE_DEVELOPMENT);
            }
        }
    }

    private static List<String> buildCorrelationInsights(Ticket source, int pr, Set<String> flagSet) {
        List<String> out = new ArrayList<>();
        boolean blocked =
                flagSet.contains(FLAG_BLOCKED)
                        || (source.getStatus() != null && "Blocked".equalsIgnoreCase(source.getStatus().trim()));
        if (blocked && hasExternalDependency(source.getDependency())) {
            out.add("External dependency blocking progress.");
        }
        if (isInProgressWorkflow(source) && (pr == 0 || !source.isPrDataAvailable())) {
            out.add("Development may not have started.");
        }
        if (flagSet.contains(FLAG_PR_DELAY)) {
            if (isHighPriorityForDependency(source.getPriority())) {
                out.add("Critical review bottleneck.");
            } else {
                out.add("Code is ready but review is slowing things down.");
            }
        }
        if (flagSet.contains(FLAG_DEV_NOT_STARTED)) {
            out.add("Git shows no commits yet — development may not have started.");
        }
        if (flagSet.contains(FLAG_PR_NOT_CREATED)) {
            out.add("Commits exist but no pull request is open — work has not entered review.");
        }
        if (flagSet.contains(FLAG_MERGED_NOT_DEPLOYED)) {
            out.add("Merged change not deployed yet — release pipeline or scheduling delay.");
        }
        return out;
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

    /** Portfolio-level stage TaT averages and short bottleneck phrases for executive UI. */
    public record StageTatStats(Map<String, Double> avgStageTat, Map<String, String> stageInsights) {}

    public StageTatStats computeStageTatAggregates(List<Ticket> tickets) {
        Map<String, Double> avg = new LinkedHashMap<>();
        Map<String, String> insights = new LinkedHashMap<>();
        if (tickets == null || tickets.isEmpty()) {
            return new StageTatStats(avg, insights);
        }
        Map<String, List<Integer>> byStage = new LinkedHashMap<>();
        for (String st : SimulationDataService.SDLC_STAGES) {
            byStage.put(st, new ArrayList<>());
        }
        for (Ticket t : tickets) {
            Map<String, Integer> sd = t.getStageDurations();
            if (sd == null || sd.isEmpty()) {
                continue;
            }
            for (String st : SimulationDataService.SDLC_STAGES) {
                Integer v = sd.get(st);
                if (v != null && v > 0) {
                    byStage.get(st).add(v);
                }
            }
        }
        List<Double> stageMeans = new ArrayList<>();
        for (String st : SimulationDataService.SDLC_STAGES) {
            List<Integer> vals = byStage.get(st);
            if (vals == null || vals.isEmpty()) {
                avg.put(st, 0.0);
                continue;
            }
            double mean = vals.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            avg.put(st, Math.round(mean * 10.0) / 10.0);
            stageMeans.add(mean);
        }
        double baseline =
                stageMeans.isEmpty()
                        ? 0.0
                        : stageMeans.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (baseline <= 0) {
            baseline = 1.0;
        }
        for (String st : SimulationDataService.SDLC_STAGES) {
            double m = avg.getOrDefault(st, 0.0);
            if (m < 8) {
                continue;
            }
            double ratio = m / baseline;
            if (ratio >= 1.95 && "DEV".equals(st)) {
                insights.put(st, "DEV is taking ~" + String.format(Locale.US, "%.1f", ratio) + "× the portfolio norm — engineering dwell.");
            } else if (ratio >= 1.95 && "REVIEW".equals(st)) {
                insights.put(st, "Review cycle is unusually long vs peers.");
            } else if (ratio >= 1.85 && "QA".equals(st)) {
                insights.put(st, "QA stage is accumulating backlog relative to other stages.");
            } else if (ratio >= 1.85 && "UAT".equals(st)) {
                insights.put(st, "UAT sign-off is stretching compared with earlier stages.");
            } else if ("BACKLOG".equals(st) && ratio >= 1.8) {
                insights.put(st, "Work is aging longer in backlog before execution.");
            }
        }
        double qaM = avg.getOrDefault("QA", 0.0);
        double devM = avg.getOrDefault("DEV", 0.0);
        if (qaM > devM && qaM >= 28 && !insights.containsKey("QA")) {
            insights.put("QA", "QA stage hours exceed development — likely rework or test capacity.");
        }
        return new StageTatStats(avg, insights);
    }
}
