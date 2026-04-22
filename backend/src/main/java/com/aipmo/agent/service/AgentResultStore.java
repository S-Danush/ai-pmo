package com.aipmo.agent.service;

import com.aipmo.agent.dto.AgentRunResponse;
import com.aipmo.agent.dto.DataQuality;
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
 * Holds the last successful {@link AgentService#runAgent(boolean)} snapshot per mode (simulation
 * vs integration) for {@code GET /api/insights}.
 */
@Component
public class AgentResultStore {

    private final AtomicReference<AgentRunResponse> lastRunIntegration = new AtomicReference<>(null);
    private final AtomicReference<AgentRunResponse> lastRunSimulation = new AtomicReference<>(null);

    public void setLastRun(AgentRunResponse response, boolean simulation) {
        AtomicReference<AgentRunResponse> slot = simulation ? lastRunSimulation : lastRunIntegration;
        slot.set(copyResponse(response, simulation));
    }

    public AgentRunResponse getLastRun(boolean simulation) {
        return simulation ? lastRunSimulation.get() : lastRunIntegration.get();
    }

    private static AgentRunResponse copyResponse(AgentRunResponse in, boolean simulation) {
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
                .simulation(simulation)
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
                .flags(t.getFlags() != null ? new ArrayList<>(t.getFlags()) : new ArrayList<>())
                .insight(t.getInsight())
                .nudge(t.getNudge())
                .rootCause(t.getRootCause())
                .impact(t.getImpact())
                .recommendedAction(t.getRecommendedAction())
                .severity(t.getSeverity())
                .trendIndicator(t.getTrendIndicator())
                .confidence(t.getConfidence())
                .lastUpdated(t.getLastUpdated())
                .dataQuality(t.getDataQuality() != null ? t.getDataQuality() : DataQuality.MOCK)
                .jiraDataAvailable(t.isJiraDataAvailable())
                .prDataAvailable(t.isPrDataAvailable())
                .build();
    }
}
