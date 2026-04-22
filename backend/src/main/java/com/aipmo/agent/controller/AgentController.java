package com.aipmo.agent.controller;

import com.aipmo.agent.dto.AgentRunResponse;
import com.aipmo.agent.dto.ProjectHealthDto;
import com.aipmo.agent.dto.ProjectSummaryDto;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.logging.PipelineMdc;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.service.AgentResultStore;
import com.aipmo.agent.service.AgentService;
import com.aipmo.agent.service.MetricsService;
import com.aipmo.agent.service.ProjectSummaryService;
import com.aipmo.agent.service.TicketDataService;
import com.aipmo.agent.util.DashboardSort;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public AgentController(
            TicketDataService ticketDataService,
            AgentService agentService,
            AgentResultStore agentResultStore,
            MetricsService metricsService,
            ProjectSummaryService projectSummaryService) {
        this.ticketDataService = ticketDataService;
        this.agentService = agentService;
        this.agentResultStore = agentResultStore;
        this.metricsService = metricsService;
        this.projectSummaryService = projectSummaryService;
    }

    @GetMapping("/tickets")
    public List<Ticket> getTickets(
            @RequestParam(name = "simulation", defaultValue = "false") boolean simulation) {
        return metricsService.analyzeTickets(ticketDataService.loadTicketData(simulation).tickets());
    }

    @PostMapping("/run-agent")
    public AgentRunResponse runAgent(
            @RequestParam(name = "simulation", defaultValue = "false") boolean simulation) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(PipelineMdc.KEY_REQUEST_ID, requestId);
        MDC.put(PipelineMdc.KEY_SIMULATION, Boolean.toString(simulation));
        try {
            return agentService.runAgent(simulation);
        } finally {
            MDC.clear();
        }
    }

    /** Last agent snapshot for the requested mode, or live analyzed data if no run stored yet. */
    @GetMapping("/insights")
    public AgentRunResponse getInsights(
            @RequestParam(name = "simulation", defaultValue = "false") boolean simulation) {
        AgentRunResponse last = agentResultStore.getLastRun(simulation);
        if (last != null) {
            return last;
        }
        TicketDataLoad load = ticketDataService.loadTicketData(simulation);
        List<Ticket> analyzed = metricsService.analyzeTickets(load.tickets());
        DashboardSort.sortForManager(analyzed);
        ProjectSummaryDto summary = projectSummaryService.summarize(analyzed, load);
        return AgentRunResponse.builder()
                .tickets(analyzed)
                .projectSummary(summary)
                .projectHealth(ProjectHealthDto.from(analyzed))
                .simulation(simulation)
                .build();
    }
}
