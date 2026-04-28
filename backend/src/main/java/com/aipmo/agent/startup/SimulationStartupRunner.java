package com.aipmo.agent.startup;

import com.aipmo.agent.service.NotificationService;
import com.aipmo.agent.service.SimulationDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class SimulationStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationStartupRunner.class);

    private final SimulationDataService simulationDataService;
    private final NotificationService notificationService;

    public SimulationStartupRunner(
            SimulationDataService simulationDataService, NotificationService notificationService) {
        this.simulationDataService = simulationDataService;
        this.notificationService = notificationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = simulationDataService.ticketCount();
        String teamsLine =
                notificationService.isWebhookEnabled()
                        ? "Teams      : ACTIVE (webhook enabled)"
                        : "Teams      : NO WEBHOOK (set teams.webhook.url for live posts)";
        log.info(
                """
                ----------------------------------------
                SYSTEM MODE: SIMULATION
                ----------------------------------------
                Tickets loaded: {}
                Jira       : DISABLED
                GitHub     : DISABLED
                OpenAI     : SIMULATED
                {}
                Notifications: MANUAL ONLY
                ----------------------------------------""",
                n,
                teamsLine);
    }
}
