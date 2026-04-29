package com.aipmo.agent.service;

import com.aipmo.agent.dto.AgentRunResponse;
import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.dto.RootCauseDto;
import com.aipmo.agent.dto.ProjectHealthDto;
import com.aipmo.agent.dto.ProjectSummaryDto;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.TicketDisplayMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Holds the last successful {@link AgentService#runAgent()} snapshot for {@code GET /api/insights}.
 */
@Component
public class AgentResultStore {

    private final AtomicReference<AgentRunResponse> lastRun = new AtomicReference<>(null);

    public void setLastRun(AgentRunResponse response) {
        lastRun.set(copyResponse(response));
    }

    public AgentRunResponse getLastRun() {
        return lastRun.get();
    }

    /** Clears cached agent output (e.g. after switching simulation scenario). */
    public void clearLastRun() {
        lastRun.set(null);
    }

    private static AgentRunResponse copyResponse(AgentRunResponse in) {
        if (in == null) {
            return null;
        }
        List<Ticket> tickets =
                in.getTickets() == null
                        ? List.of()
                        : in.getTickets().stream()
                                .map(AgentResultStore::copyTicket)
                                .collect(Collectors.toCollection(ArrayList::new));
        return AgentRunResponse.builder()
                .tickets(tickets)
                .projectSummary(copySummary(in.getProjectSummary()))
                .projectHealth(copyHealth(in.getProjectHealth()))
                .simulation(in.isSimulation())
                .generatedAt(in.getGeneratedAt())
                .build();
    }

    private static ProjectHealthDto copyHealth(ProjectHealthDto h) {
        if (h == null) {
            return null;
        }
        return ProjectHealthDto.builder()
                .totalOpenTickets(h.getTotalOpenTickets())
                .highRiskCount(h.getHighRiskCount())
                .blockedCount(h.getBlockedCount())
                .atRiskCount(h.getAtRiskCount())
                .healthyCount(h.getHealthyCount())
                .unassignedCount(h.getUnassignedCount())
                .build();
    }

    private static ProjectSummaryDto copySummary(ProjectSummaryDto s) {
        if (s == null) {
            return null;
        }
        return ProjectSummaryDto.builder()
                .totalTickets(s.getTotalTickets())
                .stuckTickets(s.getStuckTickets())
                .criticalTickets(s.getCriticalTickets())
                .topBottleneck(s.getTopBottleneck())
                .status(s.getStatus())
                .prDelayTrendPercent(s.getPrDelayTrendPercent())
                .estimatedDelayDays(s.getEstimatedDelayDays())
                .trendSummary(s.getTrendSummary())
                .reasonForStatus(s.getReasonForStatus())
                .projectRiskSummary(s.getProjectRiskSummary())
                .deliveryInsight(s.getDeliveryInsight())
                .portfolioDeliveryRisk(s.getPortfolioDeliveryRisk())
                .dataQuality(s.getDataQuality() != null ? s.getDataQuality() : DataQuality.MOCK)
                .prDataAvailable(s.isPrDataAvailable())
                .jiraDataAvailable(s.isJiraDataAvailable())
                .build();
    }

    private static Ticket copyTicket(Ticket t) {
        return Ticket.builder()
                .id(t.getId())
                .summary(t.getSummary())
                .status(t.getStatus())
                .statusCategory(t.getStatusCategory())
                .createdAt(t.getCreatedAt())
                .jiraUpdatedAt(t.getJiraUpdatedAt())
                .assignee(t.getAssignee())
                .priority(t.getPriority())
                .bottleneckCategory(t.getBottleneckCategory())
                .displayStatus(
                        t.getDisplayStatus() != null
                                ? t.getDisplayStatus()
                                : TicketDisplayMapper.toFriendlyStatus(t.getStatus()))
                .progressLabel(t.getProgressLabel())
                .flagSummary(t.getFlagSummary())
                .agingBucket(t.getAgingBucket())
                .deliveryRisk(t.getDeliveryRisk())
                .movementStatus(t.getMovementStatus())
                .viewGroup(t.getViewGroup())
                .timeInStatusLabel(t.getTimeInStatusLabel())
                .lastActivityLabel(t.getLastActivityLabel())
                .timeInState(t.getTimeInState())
                .prTime(t.getPrTime())
                .statusChanges(t.getStatusChanges())
                .pingPongTransitions(t.getPingPongTransitions())
                .bounceCount(t.getBounceCount())
                .prStatus(t.getPrStatus())
                .dependency(t.getDependency())
                .correlationInsights(
                        t.getCorrelationInsights() != null
                                ? new ArrayList<>(t.getCorrelationInsights())
                                : new ArrayList<>())
                .complexity(t.getComplexity())
                .prNumber(t.getPrNumber())
                .prUrl(t.getPrUrl())
                .branchName(t.getBranchName())
                .lastCommitAt(t.getLastCommitAt())
                .prAuthor(t.getPrAuthor())
                .commitMessages(
                        t.getCommitMessages() != null
                                ? new ArrayList<>(t.getCommitMessages())
                                : new ArrayList<>())
                .prTitle(t.getPrTitle())
                .prLink(t.getPrLink())
                .commitCount(t.getCommitCount())
                .deploymentTag(t.getDeploymentTag())
                .deployed(t.isDeployed())
                .deployedAt(t.getDeployedAt())
                .deployEnvironment(t.getDeployEnvironment())
                .prAgeHours(t.getPrAgeHours())
                .reviewerDelayHours(t.getReviewerDelayHours())
                .flags(t.getFlags() != null ? new ArrayList<>(t.getFlags()) : new ArrayList<>())
                .insight(t.getInsight())
                .nudge(t.getNudge())
                .reasoning(t.getReasoning())
                .rootCause(t.getRootCause())
                .impact(t.getImpact())
                .recommendedAction(t.getRecommendedAction())
                .actionOwner(t.getActionOwner())
                .rootCauseAnalysis(t.getRootCauseAnalysis())
                .explainabilityFactors(
                        t.getExplainabilityFactors() != null
                                ? new ArrayList<>(t.getExplainabilityFactors())
                                : new ArrayList<>())
                .severity(t.getSeverity())
                .trendIndicator(t.getTrendIndicator())
                .confidence(t.getConfidence())
                .lastUpdated(t.getLastUpdated())
                .dataQuality(t.getDataQuality() != null ? t.getDataQuality() : DataQuality.MOCK)
                .jiraDataAvailable(t.isJiraDataAvailable())
                .prDataAvailable(t.isPrDataAvailable())
                .lastNotifiedAt(t.getLastNotifiedAt())
                .build();
    }
}
