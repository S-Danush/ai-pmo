package com.aipmo.agent.startup;

import com.aipmo.agent.service.GroqAIService;
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
    private final GroqAIService groqAIService;

    public SimulationStartupRunner(
            SimulationDataService simulationDataService,
            NotificationService notificationService,
            GroqAIService groqAIService) {
        this.simulationDataService = simulationDataService;
        this.notificationService = notificationService;
        this.groqAIService = groqAIService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = simulationDataService.ticketCount();
        String teamsLine =
                notificationService.isWebhookEnabled()
                        ? "Teams      : ACTIVE (webhook enabled)"
                        : "Teams      : NO WEBHOOK (set teams.webhook.url for live posts)";
        String groqLine =
                groqAIService.isEnabled()
                        ? "AI (Groq)  : ACTIVATED (GROQ_API_KEY - insights & chat when eligible)"
                        : "AI (Groq)  : SIMULATED (no API key - local insight engine only)";
        log.info(
                """
                ----------------------------------------
                SYSTEM MODE: SIMULATION
                ----------------------------------------
                Tickets loaded: {}
                Jira       : DISABLED
                GitHub     : DISABLED
                {}
                {}
                Notifications: MANUAL ONLY
                ----------------------------------------""",
                n,
                groqLine,
                teamsLine);
    }
}
