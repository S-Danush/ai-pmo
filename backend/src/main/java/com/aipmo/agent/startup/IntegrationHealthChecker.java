package com.aipmo.agent.startup;

import com.aipmo.agent.dto.IntegrationHealthApiResponse;
import com.aipmo.agent.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records integration posture for the debug API. External Jira/GitHub/OpenAI are disabled; Teams
 * is live when {@code teams.webhook.url} is configured.
 */
@Component
public class IntegrationHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(IntegrationHealthChecker.class);

    private volatile IntegrationHealthApiResponse lastSnapshot =
            new IntegrationHealthApiResponse("DISABLED", "DISABLED", "SIMULATED", "SIMULATED");

    private final NotificationService notificationService;

    public IntegrationHealthChecker(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public IntegrationHealthApiResponse getLastSnapshot() {
        return lastSnapshot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String teams =
                notificationService.isWebhookEnabled()
                        ? "ACTIVE (webhook enabled)"
                        : "MANUAL ONLY (no webhook URL)";
        lastSnapshot = new IntegrationHealthApiResponse("DISABLED", "DISABLED", "SIMULATED", teams);
        log.info(
                """
                ================ INTEGRATION HEALTH =================
                Jira     : [DISABLED] SIMULATION MODE
                GitHub   : [DISABLED] SIMULATION MODE
                AI (LLM): [SIMULATED] local insight engine
                Teams    : [{}]
                Notifications: manual POST /api/notify/<ticketId> only
                =====================================================""",
                teams);
    }
}
