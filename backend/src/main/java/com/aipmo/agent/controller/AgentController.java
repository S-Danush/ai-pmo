package com.aipmo.agent.controller;

import com.aipmo.agent.dto.AgentRunResponse;
import com.aipmo.agent.dto.DeliveryTrendDto;
import com.aipmo.agent.dto.DeliveryTrendPointDto;
import com.aipmo.agent.dto.ProjectHealthDto;
import com.aipmo.agent.dto.ProjectSummaryDto;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.dto.TicketSuggestionDto;
import com.aipmo.agent.logging.PipelineMdc;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.service.AgentResultStore;
import com.aipmo.agent.service.AgentService;
import com.aipmo.agent.service.MetricsService;
import com.aipmo.agent.service.NotifyStateStore;
import com.aipmo.agent.service.ProjectSummaryService;
import com.aipmo.agent.service.RunMetricsHistory;
import com.aipmo.agent.service.SuggestionService;
import com.aipmo.agent.service.TicketDataService;
import com.aipmo.agent.util.DashboardSort;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final TicketDataService ticketDataService;
    private final AgentService agentService;
    private final AgentResultStore agentResultStore;
    private final MetricsService metricsService;
    private final ProjectSummaryService projectSummaryService;
    private final NotifyStateStore notifyStateStore;
    private final SuggestionService suggestionService;
    private final RunMetricsHistory runMetricsHistory;

    public AgentController(
            TicketDataService ticketDataService,
            AgentService agentService,
            AgentResultStore agentResultStore,
            MetricsService metricsService,
            ProjectSummaryService projectSummaryService,
            NotifyStateStore notifyStateStore,
            SuggestionService suggestionService,
            RunMetricsHistory runMetricsHistory) {
        this.ticketDataService = ticketDataService;
        this.agentService = agentService;
        this.agentResultStore = agentResultStore;
        this.metricsService = metricsService;
        this.projectSummaryService = projectSummaryService;
        this.notifyStateStore = notifyStateStore;
        this.suggestionService = suggestionService;
        this.runMetricsHistory = runMetricsHistory;
    }

    @GetMapping("/tickets")
    public List<Ticket> getTickets() {
        List<Ticket> analyzed =
                metricsService.analyzeTickets(ticketDataService.loadTicketData().tickets());
        return notifyStateStore.mergeAll(analyzed);
    }

    @PostMapping("/run-agent")
    public AgentRunResponse runAgent() {
        String requestId = UUID.randomUUID().toString();
        MDC.put(PipelineMdc.KEY_REQUEST_ID, requestId);
        MDC.put(PipelineMdc.KEY_SIMULATION, "true");
        try {
            return agentService.runAgent();
        } finally {
            MDC.clear();
        }
    }

    /** Last agent snapshot, or live analyzed synthetic data if no run stored yet. */
    @GetMapping("/insights")
    public AgentRunResponse getInsights() {
        return resolveMergedInsights();
    }

    /**
     * Proactive manager hints from the same snapshot as {@link #getInsights()} — does not send
     * Teams messages.
     */
    @GetMapping("/suggestions")
    public List<TicketSuggestionDto> getSuggestions() {
        return suggestionService.buildSuggestions(resolveMergedInsights().getTickets());
    }

    /** Run-over-run averages for dashboard trend strip (populated after agent runs). */
    @GetMapping("/delivery-trend")
    public DeliveryTrendDto getDeliveryTrend() {
        List<DeliveryTrendPointDto> points = new ArrayList<>();
        for (RunMetricsHistory.ProjectSnapshot s : runMetricsHistory.getRecentSnapshots()) {
            points.add(
                    DeliveryTrendPointDto.builder()
                            .recordedAt(s.recordedAt().toString())
                            .avgPrHours(s.avgPrHours())
                            .avgDwellHours(s.avgDwellHours())
                            .ticketCount(s.ticketCount())
                            .build());
        }
        return DeliveryTrendDto.builder().snapshots(points).build();
    }

    private AgentRunResponse resolveMergedInsights() {
        AgentRunResponse last = agentResultStore.getLastRun();
        AgentRunResponse base;
        if (last != null) {
            base = last;
        } else {
            TicketDataLoad load = ticketDataService.loadTicketData();
            List<Ticket> analyzed = metricsService.analyzeTickets(load.tickets());
            DashboardSort.sortForManager(analyzed);
            ProjectSummaryDto summary = projectSummaryService.summarize(analyzed, load);
            base =
                    AgentRunResponse.builder()
                            .tickets(analyzed)
                            .projectSummary(summary)
                            .projectHealth(ProjectHealthDto.from(analyzed))
                            .simulation(true)
                            .generatedAt(Instant.now().toString())
                            .build();
        }
        return mergeLastNotified(base);
    }

    private AgentRunResponse mergeLastNotified(AgentRunResponse r) {
        List<Ticket> merged = notifyStateStore.mergeAll(r.getTickets());
        return AgentRunResponse.builder()
                .tickets(merged)
                .projectSummary(r.getProjectSummary())
                .projectHealth(r.getProjectHealth())
                .simulation(r.isSimulation())
                .generatedAt(
                        r.getGeneratedAt() != null && !r.getGeneratedAt().isBlank()
                                ? r.getGeneratedAt()
                                : Instant.now().toString())
                .build();
    }
}
