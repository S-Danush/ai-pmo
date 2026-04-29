package com.aipmo.agent.service;

import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.dto.TicketDataPath;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.TicketDisplayMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TicketDataService {

    private static final Logger log = LoggerFactory.getLogger(TicketDataService.class);

    private final SimulationDataService simulationDataService;

    public TicketDataService(SimulationDataService simulationDataService) {
        this.simulationDataService = simulationDataService;
    }

    public List<Ticket> loadTicketsForAnalysis() {
        return loadTicketData().tickets();
    }

    /** @deprecated All loads use the synthetic dataset; the parameter is ignored. */
    @Deprecated
    public List<Ticket> loadTicketsForAnalysis(boolean ignored) {
        return loadTicketsForAnalysis();
    }

    /**
     * Returns the in-memory synthetic backlog (simulation-only prototype).
     *
     * @param ignored retained for API compatibility; ignored.
     */
    public TicketDataLoad loadTicketData(boolean ignored) {
        return loadTicketData();
    }

    public TicketDataLoad loadTicketData() {
        List<Ticket> tickets = copyTickets(simulationDataService.loadTickets());
        boolean prDataAvailable = tickets.stream().anyMatch(Ticket::isPrDataAvailable);
        log.debug("ticketData path={} count={}", TicketDataPath.SIMULATION, tickets.size());
        return new TicketDataLoad(
                tickets, TicketDataPath.SIMULATION, true, prDataAvailable, DataQuality.MOCK);
    }

    private static Ticket copyOne(Ticket t) {
        DataQuality dq = t.getDataQuality() != null ? t.getDataQuality() : DataQuality.MOCK;
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
                .rootCauseAnalysis(t.getRootCauseAnalysis())
                .explainabilityFactors(
                        t.getExplainabilityFactors() != null
                                ? new ArrayList<>(t.getExplainabilityFactors())
                                : new ArrayList<>())
                .impact(t.getImpact())
                .recommendedAction(t.getRecommendedAction())
                .actionOwner(t.getActionOwner())
                .severity(t.getSeverity())
                .trendIndicator(t.getTrendIndicator())
                .confidence(t.getConfidence())
                .lastUpdated(t.getLastUpdated())
                .agingBucket(t.getAgingBucket())
                .deliveryRisk(t.getDeliveryRisk())
                .movementStatus(t.getMovementStatus())
                .viewGroup(t.getViewGroup())
                .timeInStatusLabel(t.getTimeInStatusLabel())
                .lastActivityLabel(t.getLastActivityLabel())
                .dataQuality(dq)
                .jiraDataAvailable(t.isJiraDataAvailable())
                .prDataAvailable(t.isPrDataAvailable())
                .lastNotifiedAt(t.getLastNotifiedAt())
                .build();
    }

    private static List<Ticket> copyTickets(List<Ticket> source) {
        List<Ticket> copy = new ArrayList<>(source.size());
        for (Ticket t : source) {
            copy.add(copyOne(t));
        }
        return copy;
    }
}
