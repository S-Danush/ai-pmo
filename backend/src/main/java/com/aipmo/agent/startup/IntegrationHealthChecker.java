package com.aipmo.agent.startup;

import com.aipmo.agent.dto.IntegrationHealthApiResponse;
import com.aipmo.agent.service.GroqAIService;
import com.aipmo.agent.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records integration posture for the debug API. External Jira/GitHub are disabled in simulation;
 * Groq is reported as activated when {@code groq.api.key} is set; Teams is live when
 * {@code teams.webhook.url} is configured.
 */
@Component
public class IntegrationHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(IntegrationHealthChecker.class);

    private volatile IntegrationHealthApiResponse lastSnapshot =
            new IntegrationHealthApiResponse("DISABLED", "DISABLED", "SIMULATED (local only)", "SIMULATED");

    private final NotificationService notificationService;
    private final GroqAIService groqAIService;

    public IntegrationHealthChecker(NotificationService notificationService, GroqAIService groqAIService) {
        this.notificationService = notificationService;
        this.groqAIService = groqAIService;
    }

    public IntegrationHealthApiResponse getLastSnapshot() {
        return lastSnapshot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        boolean groqEnabled = groqAIService.isEnabled();
        String groqApi =
                groqEnabled
                        ? "ACTIVATED (GROQ_API_KEY set - Groq for eligible insights & chat)"
                        : "SIMULATED (no API key - local insight engine only)";
        String teams =
                notificationService.isWebhookEnabled()
                        ? "ACTIVE (webhook enabled)"
                        : "MANUAL ONLY (no webhook URL)";
        lastSnapshot = new IntegrationHealthApiResponse("DISABLED", "DISABLED", groqApi, teams);
        log.info(
                """
                ================ INTEGRATION HEALTH =================
                Jira     : [DISABLED] SIMULATION MODE
                GitHub   : [DISABLED] SIMULATION MODE
                AI (LLM): [{}]
                Teams    : [{}]
                Notifications: manual POST /api/notify/<ticketId> only
                =====================================================""",
                groqApi,
                teams);
    }
}
