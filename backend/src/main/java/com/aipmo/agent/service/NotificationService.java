package com.aipmo.agent.service;

import com.aipmo.agent.dto.TeamsIntegrationHealth;
import com.aipmo.agent.dto.TeamsTextMessage;
import com.aipmo.agent.logging.PipelineMdc;
import com.aipmo.agent.model.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sends formatted alerts to a Microsoft Teams incoming webhook when {@code teams.webhook.url} is
 * set; otherwise logs only (no network).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final int MAX_BUFFER = 200;

    private final RestTemplate restTemplate;
    private final LocalAIService localAIService;
    private final Optional<String> webhookUrl;
    private final ConcurrentLinkedDeque<String> recentLogLines = new ConcurrentLinkedDeque<>();

    public NotificationService(
            RestTemplate restTemplate,
            LocalAIService localAIService,
            @Value("${teams.webhook.url:}") String teamsWebhookUrl) {
        this.restTemplate = restTemplate;
        this.localAIService = localAIService;
        this.webhookUrl =
                Optional.ofNullable(teamsWebhookUrl).map(String::trim).filter(s -> !s.isBlank());
    }

    public boolean isWebhookEnabled() {
        return webhookUrl.isPresent();
    }

    /** Probes webhook when configured; otherwise reports not reachable. */
    public TeamsIntegrationHealth verifyWebhookHealth() {
        if (webhookUrl.isEmpty()) {
            return TeamsIntegrationHealth.fail(0L, "teams.webhook.url not set", false);
        }
        long t0 = System.currentTimeMillis();
        try {
            postJson(new TeamsTextMessage("AI PMO Agent — webhook connectivity check (safe to delete)."));
            return TeamsIntegrationHealth.ok(System.currentTimeMillis() - t0);
        } catch (RestClientException ex) {
            return TeamsIntegrationHealth.fail(
                    System.currentTimeMillis() - t0, ex.getMessage(), false);
        }
    }

    public List<String> getSimulatedMessagesSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(recentLogLines));
    }

    /**
     * Manual demo path: posts a natural manager-style message (from {@link
     * LocalAIService#generateManagerStyleMessage(Ticket)}) to Teams. Requires {@link
     * #isWebhookEnabled()}.
     *
     * @return the exact text posted (for client previews and logs)
     * @throws IllegalStateException when URL not configured
     * @throws RestClientException on HTTP errors from Teams
     */
    public String sendManagerStyleMessage(Ticket ticket) {
        if (webhookUrl.isEmpty()) {
            recordLine("[TEAMS DISABLED] " + ticket.getId() + " — configure teams.webhook.url");
            throw new IllegalStateException("Teams webhook URL is not configured");
        }
        PipelineMdc.stageAndAction(PipelineMdc.STAGE_NOTIFY, PipelineMdc.ACTION_TEAMS_WEBHOOK);
        String body = localAIService.generateManagerStyleMessage(ticket);
        postJson(new TeamsTextMessage(body));
        recordLine("[TEAMS SENT] ticket=" + ticket.getId());
        log.info("Teams webhook delivered for ticketId={}", ticket.getId());
        return body;
    }

    private void postJson(TeamsTextMessage message) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TeamsTextMessage> entity = new HttpEntity<>(message, headers);
        restTemplate.postForEntity(webhookUrl.get(), entity, String.class);
    }

    private void recordLine(String line) {
        log.info(line);
        recentLogLines.addFirst(line);
        while (recentLogLines.size() > MAX_BUFFER) {
            recentLogLines.removeLast();
        }
    }

}
