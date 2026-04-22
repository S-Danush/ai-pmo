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
import com.aipmo.agent.util.DeliveryRiskCopy;
import com.aipmo.agent.util.DeliveryViewEnricher;
import com.aipmo.agent.util.FriendlyText;
import com.aipmo.agent.util.Severity;
import com.aipmo.agent.util.TicketDisplayMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final TicketDataService ticketDataService;
    private final MetricsService metricsService;
    private final AIService aiService;
    private final NotificationService notificationService;
    private final AgentResultStore agentResultStore;
    private final ProjectSummaryService projectSummaryService;
    private final AiAnalysisCache aiAnalysisCache;
    private final RunMetricsHistory runMetricsHistory;
    private final TeamsDedupStore teamsDedupStore;
    private final EscalationTracker escalationTracker;

    public AgentService(
            TicketDataService ticketDataService,
            MetricsService metricsService,
            AIService aiService,
            NotificationService notificationService,
            AgentResultStore agentResultStore,
            ProjectSummaryService projectSummaryService,
            AiAnalysisCache aiAnalysisCache,
            RunMetricsHistory runMetricsHistory,
            TeamsDedupStore teamsDedupStore,
            EscalationTracker escalationTracker) {
        this.ticketDataService = ticketDataService;
        this.metricsService = metricsService;
        this.aiService = aiService;
        this.notificationService = notificationService;
        this.agentResultStore = agentResultStore;
        this.projectSummaryService = projectSummaryService;
        this.aiAnalysisCache = aiAnalysisCache;
        this.runMetricsHistory = runMetricsHistory;
        this.teamsDedupStore = teamsDedupStore;
        this.escalationTracker = escalationTracker;
    }

    public AgentRunResponse runAgent() {
        return runAgent(false);
    }

    public AgentRunResponse runAgent(boolean simulation) {
        long runT0 = System.currentTimeMillis();
        boolean addedRequestId = ensureRequestIdForStandaloneCall();

        try {
            PipelineMdc.stageAndAction(PipelineMdc.STAGE_PIPELINE, PipelineMdc.ACTION_START);
            log.info("Agent run started simulation={}", simulation);

            AgentRunResponse prevRun = agentResultStore.getLastRun(simulation);

            PipelineMdc.stageAndAction(PipelineMdc.STAGE_DATA_FETCH, PipelineMdc.ACTION_JIRA_GITHUB);
            if (simulation) {
                PipelineMdc.action(PipelineMdc.ACTION_SIMULATION);
            }
            log.info(
                    simulation
                            ? "Fetching tickets (simulation dataset)"
                            : "Fetching tickets from Jira/GitHub");

            TicketDataLoad dataLoad = ticketDataService.loadTicketData(simulation);
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
                    insightOutcome = aiService.generateStructuredInsight(ticket);
                    long insightMs = System.currentTimeMillis() - insightT0;
                    insight = insightOutcome.insight();
                    if (insightOutcome.usedOpenAiModel()) {
                        log.info(
                                "Insight generated successfully ticketId={} cached=false insightMs={} status=SUCCESS",
                                ticket.getId(),
                                insightMs);
                    } else if (!insightOutcome.openAiAttempted()) {
                        log.warn(
                                "Insight used metric-only fallback (OpenAI not configured) ticketId={} insightMs={} status=FAILED dependency=CONFIG",
                                ticket.getId(),
                                insightMs);
                    } else {
                        log.error(
                                "Insight NOT generated from OpenAI ticketId={} insightMs={} status=FAILED transportFailure={}",
                                ticket.getId(),
                                insightMs,
                                insightOutcome.transportFailure());
                    }

                    aiAnalysisCache.put(cacheKey, insight);
                }

                boolean aiLimited = isAiInsightLimited(insight, insightOutcome);
                TicketInsightPayload forUi = buildUiInsight(ticket, insight, aiLimited);
                String nudge = forUi.getNudge() != null ? forUi.getNudge() : "";
                String insightText = AIService.formatInsightText(forUi);
                int escalation =
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
                                .movementStatus(ticket.getMovementStatus())
                                .viewGroup(ticket.getViewGroup())
                                .timeInStatusLabel(ticket.getTimeInStatusLabel())
                                .lastActivityLabel(ticket.getLastActivityLabel())
                                .timeInState(ticket.getTimeInState())
                                .prTime(ticket.getPrTime())
                                .statusChanges(ticket.getStatusChanges())
                                .pingPongTransitions(ticket.getPingPongTransitions())
                                .flags(new ArrayList<>(ticket.getFlags()))
                                .insight(insightText)
                                .nudge(nudge)
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

                if (shouldSendTeamsAlert(updated) && teamsDedupStore.shouldSend(updated.getId())) {
                    log.info("Sending Teams notification for ticketId={}", updated.getId());
                    dispatchDeliveryRiskTeams(updated, forUi, aiLimited, escalation);
                    teamsDedupStore.markSent(updated.getId());
                } else if (shouldSendTeamsAlert(updated)) {
                    log.info("Teams alert skipped (dedup cooldown) ticketId={}", updated.getId());
                }
            }

            PipelineMdc.stageAndAction(PipelineMdc.STAGE_SUMMARY, PipelineMdc.ACTION_PROJECT_SUMMARY);
            log.info("Project summary generated: {}", summary.getStatus());

            DashboardSort.sortForManager(enriched);
            ProjectHealthDto health = ProjectHealthDto.from(enriched);

            AgentRunResponse response =
                    AgentRunResponse.builder()
                            .tickets(enriched)
                            .projectSummary(summary)
                            .projectHealth(health)
                            .simulation(simulation)
                            .build();
            agentResultStore.setLastRun(response, simulation);
            runMetricsHistory.recordCompletedRun(analyzed);

            long totalMs = System.currentTimeMillis() - runT0;
            PipelineMdc.stageAndAction(PipelineMdc.STAGE_PIPELINE, PipelineMdc.ACTION_COMPLETE);
            log.info(
                    "Agent run completed in {} ms ticketCount={} outliersProcessed={}",
                    totalMs,
                    enriched.size(),
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

    private static boolean shouldSendTeamsAlert(Ticket ticket) {
        if (Severity.HIGH.equalsIgnoreCase(ticket.getSeverity())) {
            return true;
        }
        return ticket.getFlags() != null && ticket.getFlags().size() >= 2;
    }

    /**
     * True when the live model did not produce the insight (config, transport, or cache of such a
     * result).
     */
    private static boolean isAiInsightLimited(
            TicketInsightPayload insight, AiInsightOutcome outcome) {
        if (outcome != null) {
            return !outcome.usedOpenAiModel();
        }
        return FriendlyText.looksLikeMetricFallback(insight.getRootCause());
    }

    /** Replaces raw model/fallback text with one human-readable payload for UI + Teams. */
    private static TicketInsightPayload buildUiInsight(
            Ticket ticket, TicketInsightPayload raw, boolean aiLimited) {
        if (aiLimited) {
            return TicketInsightPayload.builder()
                    .rootCause(DeliveryRiskCopy.DEFAULT_ISSUE)
                    .impact(DeliveryRiskCopy.DEFAULT_IMPACT)
                    .recommendedAction(DeliveryRiskCopy.DEFAULT_ACTION)
                    .nudge(DeliveryRiskCopy.DEFAULT_SUGGESTION)
                    .confidence("—")
                    .build();
        }
        String timePhrase = TicketDisplayMapper.toTimeInStateNarrative(ticket.getTimeInState());
        String rc = stringOrNull(FriendlyText.sanitizeForReader(raw.getRootCause()));
        if (rc == null || rc.isBlank()) {
            rc = DeliveryRiskCopy.issueTiedToTiming(timePhrase);
        }
        String im = stringOrNull(FriendlyText.sanitizeForReader(raw.getImpact()));
        if (im == null || im.isBlank()) {
            im = DeliveryRiskCopy.DEFAULT_IMPACT;
        }
        String ra = stringOrNull(FriendlyText.sanitizeForReader(raw.getRecommendedAction()));
        String nud = stringOrNull(FriendlyText.sanitizeForReader(raw.getNudge()));
        if (ra == null || ra.isBlank()) {
            ra = nud != null && !nud.isBlank()
                    ? nud
                    : "Check current status, confirm the owner, and move forward or unblock.";
        }
        if (nud == null || nud.isBlank()) {
            nud = DeliveryRiskCopy.DEFAULT_SUGGESTION;
        }
        String conf = raw.getConfidence() != null && !raw.getConfidence().isBlank()
                ? raw.getConfidence()
                : "MEDIUM";
        return TicketInsightPayload.builder()
                .rootCause(rc)
                .impact(im)
                .recommendedAction(ra)
                .nudge(nud)
                .confidence(conf)
                .build();
    }

    private static String stringOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }

    private void dispatchDeliveryRiskTeams(
            Ticket ticket,
            TicketInsightPayload forUi,
            boolean aiLimited,
            int escalation) {
        String friendlyStatus =
                ticket.getDisplayStatus() != null
                        ? ticket.getDisplayStatus()
                        : TicketDisplayMapper.toFriendlyStatus(ticket.getStatus());
        String problem = buildTeamsProblem(ticket, forUi, aiLimited, escalation);
        String suggested =
                (forUi.getRecommendedAction() != null && !forUi.getRecommendedAction().isBlank())
                        ? forUi.getRecommendedAction()
                        : DeliveryRiskCopy.SIMPLE_SUGGESTED_ACTION;
        if (suggested.length() < 20) {
            suggested = DeliveryRiskCopy.SIMPLE_SUGGESTED_ACTION;
        }
        String foot = aiLimited ? DeliveryRiskCopy.AI_LIMITED_NOTE : null;
        notificationService.sendManagerIssueAlert(ticket, friendlyStatus, problem, suggested, foot);
    }

    private static String buildTeamsProblem(
            Ticket ticket, TicketInsightPayload forUi, boolean aiLimited, int escalation) {
        if (aiLimited) {
            if (DeliveryViewEnricher.MOVEMENT_NO_ACTIVITY.equals(ticket.getMovementStatus())) {
                return "This issue has not been updated in a while and may be stuck.";
            }
            return "This work item may need a quick check-in with the team.";
        }
        String p = forUi.getRootCause() != null ? forUi.getRootCause() : "Progress may be slower than expected.";
        if (p.length() > 400) {
            p = p.substring(0, 397) + "...";
        }
        if (escalation >= 2) {
            return "This item stayed in a risky state for multiple runs. " + p;
        }
        return p;
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
                .movementStatus(ticket.getMovementStatus())
                .viewGroup(ticket.getViewGroup())
                .timeInStatusLabel(ticket.getTimeInStatusLabel())
                .lastActivityLabel(ticket.getLastActivityLabel())
                .timeInState(ticket.getTimeInState())
                .prTime(ticket.getPrTime())
                .statusChanges(ticket.getStatusChanges())
                .pingPongTransitions(ticket.getPingPongTransitions())
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
