package com.aipmo.agent.service;

import com.aipmo.agent.dto.AiInsightOutcome;
import com.aipmo.agent.dto.AgentRunResponse;
import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.dto.ProjectHealthDto;
import com.aipmo.agent.dto.ProjectSummaryDto;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.dto.TicketInsightPayload;
import com.aipmo.agent.logging.PipelineMdc;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.DashboardSort;
import com.aipmo.agent.util.InsightFormatter;
import com.aipmo.agent.util.InsightUiFactory;
import com.aipmo.agent.util.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final TicketDataService ticketDataService;
    private final MetricsService metricsService;
    private final LocalAIService localAIService;
    private final AgentResultStore agentResultStore;
    private final ProjectSummaryService projectSummaryService;
    private final AiAnalysisCache aiAnalysisCache;
    private final RunMetricsHistory runMetricsHistory;
    private final EscalationTracker escalationTracker;
    private final NotifyStateStore notifyStateStore;

    public AgentService(
            TicketDataService ticketDataService,
            MetricsService metricsService,
            LocalAIService localAIService,
            AgentResultStore agentResultStore,
            ProjectSummaryService projectSummaryService,
            AiAnalysisCache aiAnalysisCache,
            RunMetricsHistory runMetricsHistory,
            EscalationTracker escalationTracker,
            NotifyStateStore notifyStateStore) {
        this.ticketDataService = ticketDataService;
        this.metricsService = metricsService;
        this.localAIService = localAIService;
        this.agentResultStore = agentResultStore;
        this.projectSummaryService = projectSummaryService;
        this.aiAnalysisCache = aiAnalysisCache;
        this.runMetricsHistory = runMetricsHistory;
        this.escalationTracker = escalationTracker;
        this.notifyStateStore = notifyStateStore;
    }

    public AgentRunResponse runAgent() {
        long runT0 = System.currentTimeMillis();
        boolean addedRequestId = ensureRequestIdForStandaloneCall();

        try {
            PipelineMdc.stageAndAction(PipelineMdc.STAGE_PIPELINE, PipelineMdc.ACTION_START);
            log.info("Agent run started (simulation-only)");

            AgentRunResponse prevRun = agentResultStore.getLastRun();

            PipelineMdc.stageAndAction(PipelineMdc.STAGE_DATA_FETCH, PipelineMdc.ACTION_SIMULATION);
            PipelineMdc.action(PipelineMdc.ACTION_SIMULATION);
            log.info("Loading synthetic ticket dataset");

            TicketDataLoad dataLoad = ticketDataService.loadTicketData();
            List<Ticket> rawTickets = dataLoad.tickets();
            log.info(
                    "Fetched ticketCount={} path={}",
                    rawTickets.size(),
                    dataLoad.path());

            PipelineMdc.stageAndAction(PipelineMdc.STAGE_METRICS, PipelineMdc.ACTION_ANALYZE);
            log.info("Running metrics analysis");
            List<Ticket> analyzed = metricsService.analyzeTickets(rawTickets);
            int outlierCount = countOutliers(analyzed);
            log.info("Flagged outlierCount={} tickets as outliers (HIGH or MEDIUM severity)", outlierCount);

            ProjectSummaryDto summary = projectSummaryService.summarize(analyzed, dataLoad);

            for (Ticket t : analyzed) {
                if (!isOutlier(t)) {
                    escalationTracker.recordAndGetLevel(t.getId(), false, prevRun);
                }
            }

            List<Ticket> enriched = new ArrayList<>();
            int aiOutliersProcessed = 0;
            for (Ticket ticket : analyzed) {
                PipelineMdc.clearStageAction();
                if (!isOutlier(ticket)) {
                    enriched.add(stripAiFields(ticket));
                    continue;
                }
                aiOutliersProcessed++;
                String cacheKey =
                        aiAnalysisCache.buildKey(
                                ticket.getId(),
                                ticket.getFlags(),
                                ticket.getSeverity(),
                                ticket.getTimeInState(),
                                ticket.getPrTime(),
                                ticket.getTrendIndicator(),
                                ticket.getPingPongTransitions());
                AiAnalysisCache.CachedEntry cached = aiAnalysisCache.getIfFresh(cacheKey);
                TicketInsightPayload insight;
                AiInsightOutcome insightOutcome = null;
                if (cached != null) {
                    PipelineMdc.stageAndAction(PipelineMdc.STAGE_AI, PipelineMdc.ACTION_INSIGHT_CACHE);
                    log.info("Generating AI insight for ticketId={} (cache hit)", ticket.getId());
                    insight = cached.insight();
                    log.info(
                            "Insight generated successfully ticketId={} cached=true insightMs=0 status=CACHED",
                            ticket.getId());
                } else {
                    log.info("Generating AI insight for ticketId={}", ticket.getId());
                    long insightT0 = System.currentTimeMillis();
                    insightOutcome = localAIService.generateStructuredInsight(ticket);
                    long insightMs = System.currentTimeMillis() - insightT0;
                    insight = insightOutcome.insight();
                    log.info(
                            "Insight generated (local engine) ticketId={} cached=false insightMs={} status=SUCCESS",
                            ticket.getId(),
                            insightMs);

                    aiAnalysisCache.put(cacheKey, insight);
                }

                boolean aiLimited = InsightUiFactory.isAiInsightLimited(insight, insightOutcome);
                TicketInsightPayload forUi = InsightUiFactory.buildUiInsight(ticket, insight, aiLimited);
                String nudge = forUi.getNudge() != null ? forUi.getNudge() : "";
                String insightText = InsightFormatter.formatInsightText(forUi);
                escalationTracker.recordAndGetLevel(
                        ticket.getId(),
                        Severity.HIGH.equalsIgnoreCase(ticket.getSeverity()),
                        prevRun);
                Ticket updated =
                        Ticket.builder()
                                .id(ticket.getId())
                                .summary(ticket.getSummary())
                                .status(ticket.getStatus())
                                .statusCategory(ticket.getStatusCategory())
                                .createdAt(ticket.getCreatedAt())
                                .jiraUpdatedAt(ticket.getJiraUpdatedAt())
                                .assignee(ticket.getAssignee())
                                .displayStatus(ticket.getDisplayStatus())
                                .progressLabel(ticket.getProgressLabel())
                                .flagSummary(ticket.getFlagSummary())
                                .agingBucket(ticket.getAgingBucket())
                                .deliveryRisk(ticket.getDeliveryRisk())
                                .priority(ticket.getPriority())
                                .bottleneckCategory(ticket.getBottleneckCategory())
                                .movementStatus(ticket.getMovementStatus())
                                .viewGroup(ticket.getViewGroup())
                                .timeInStatusLabel(ticket.getTimeInStatusLabel())
                                .lastActivityLabel(ticket.getLastActivityLabel())
                                .timeInState(ticket.getTimeInState())
                                .prTime(ticket.getPrTime())
                                .statusChanges(ticket.getStatusChanges())
                                .pingPongTransitions(ticket.getPingPongTransitions())
                                .bounceCount(ticket.getBounceCount())
                                .prStatus(ticket.getPrStatus())
                                .dependency(ticket.getDependency())
                                .correlationInsights(
                                        ticket.getCorrelationInsights() != null
                                                ? new ArrayList<>(ticket.getCorrelationInsights())
                                                : new ArrayList<>())
                                .complexity(ticket.getComplexity())
                                .prNumber(ticket.getPrNumber())
                                .prUrl(ticket.getPrUrl())
                                .branchName(ticket.getBranchName())
                                .lastCommitAt(ticket.getLastCommitAt())
                                .prAuthor(ticket.getPrAuthor())
                                .flags(new ArrayList<>(ticket.getFlags()))
                                .insight(insightText)
                                .nudge(nudge)
                                .reasoning(forUi.getReasoning())
                                .rootCause(forUi.getRootCause())
                                .impact(forUi.getImpact())
                                .recommendedAction(forUi.getRecommendedAction())
                                .severity(ticket.getSeverity())
                                .trendIndicator(ticket.getTrendIndicator())
                                .confidence(forUi.getConfidence())
                                .lastUpdated(ticket.getLastUpdated())
                                .dataQuality(
                                        ticket.getDataQuality() != null
                                                ? ticket.getDataQuality()
                                                : DataQuality.MOCK)
                                .jiraDataAvailable(ticket.isJiraDataAvailable())
                                .prDataAvailable(ticket.isPrDataAvailable())
                                .build();
                enriched.add(updated);
            }

            PipelineMdc.stageAndAction(PipelineMdc.STAGE_SUMMARY, PipelineMdc.ACTION_PROJECT_SUMMARY);
            log.info("Project summary generated: {}", summary.getStatus());

            DashboardSort.sortForManager(enriched);
            List<Ticket> mergedNotify = notifyStateStore.mergeAll(enriched);
            ProjectHealthDto health = ProjectHealthDto.from(mergedNotify);

            AgentRunResponse response =
                    AgentRunResponse.builder()
                            .tickets(mergedNotify)
                            .projectSummary(summary)
                            .projectHealth(health)
                            .simulation(true)
                            .generatedAt(Instant.now().toString())
                            .build();
            agentResultStore.setLastRun(response);
            runMetricsHistory.recordCompletedRun(analyzed);

            long totalMs = System.currentTimeMillis() - runT0;
            PipelineMdc.stageAndAction(PipelineMdc.STAGE_PIPELINE, PipelineMdc.ACTION_COMPLETE);
            log.info(
                    "Agent run completed in {} ms ticketCount={} outliersProcessed={}",
                    totalMs,
                    mergedNotify.size(),
                    aiOutliersProcessed);
            return response;
        } finally {
            PipelineMdc.clearStageAction();
            if (addedRequestId) {
                MDC.remove(PipelineMdc.KEY_REQUEST_ID);
            }
        }
    }

    private static boolean ensureRequestIdForStandaloneCall() {
        String existing = MDC.get(PipelineMdc.KEY_REQUEST_ID);
        if (existing == null || existing.isBlank()) {
            MDC.put(PipelineMdc.KEY_REQUEST_ID, UUID.randomUUID().toString());
            return true;
        }
        return false;
    }

    private static int countOutliers(List<Ticket> tickets) {
        int n = 0;
        for (Ticket t : tickets) {
            if (isOutlier(t)) {
                n++;
            }
        }
        return n;
    }

    private static boolean isOutlier(Ticket ticket) {
        String s = ticket.getSeverity();
        return Severity.HIGH.equalsIgnoreCase(s) || Severity.MEDIUM.equalsIgnoreCase(s);
    }

    private static Ticket stripAiFields(Ticket ticket) {
        return Ticket.builder()
                .id(ticket.getId())
                .summary(ticket.getSummary())
                .status(ticket.getStatus())
                .statusCategory(ticket.getStatusCategory())
                .createdAt(ticket.getCreatedAt())
                .jiraUpdatedAt(ticket.getJiraUpdatedAt())
                .assignee(ticket.getAssignee())
                .displayStatus(ticket.getDisplayStatus())
                .progressLabel(ticket.getProgressLabel())
                .flagSummary(ticket.getFlagSummary())
                .agingBucket(ticket.getAgingBucket())
                .deliveryRisk(ticket.getDeliveryRisk())
                .priority(ticket.getPriority())
                .bottleneckCategory(ticket.getBottleneckCategory())
                .movementStatus(ticket.getMovementStatus())
                .viewGroup(ticket.getViewGroup())
                .timeInStatusLabel(ticket.getTimeInStatusLabel())
                .lastActivityLabel(ticket.getLastActivityLabel())
                .timeInState(ticket.getTimeInState())
                .prTime(ticket.getPrTime())
                .statusChanges(ticket.getStatusChanges())
                .pingPongTransitions(ticket.getPingPongTransitions())
                .bounceCount(ticket.getBounceCount())
                .prStatus(ticket.getPrStatus())
                .dependency(ticket.getDependency())
                .correlationInsights(
                        ticket.getCorrelationInsights() != null
                                ? new ArrayList<>(ticket.getCorrelationInsights())
                                : new ArrayList<>())
                .complexity(ticket.getComplexity())
                .prNumber(ticket.getPrNumber())
                .prUrl(ticket.getPrUrl())
                .branchName(ticket.getBranchName())
                .lastCommitAt(ticket.getLastCommitAt())
                .prAuthor(ticket.getPrAuthor())
                .flags(ticket.getFlags() != null ? new ArrayList<>(ticket.getFlags()) : new ArrayList<>())
                .severity(ticket.getSeverity())
                .trendIndicator(ticket.getTrendIndicator())
                .lastUpdated(ticket.getLastUpdated())
                .dataQuality(ticket.getDataQuality() != null ? ticket.getDataQuality() : DataQuality.MOCK)
                .jiraDataAvailable(ticket.isJiraDataAvailable())
                .prDataAvailable(ticket.isPrDataAvailable())
                .build();
    }
}
