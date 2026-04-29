package com.aipmo.agent.service;

import com.aipmo.agent.dto.AiInsightOutcome;
import com.aipmo.agent.dto.ManualNotifyResponse;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.dto.TicketInsightPayload;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.InsightUiFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class TicketNotifyService {

    private final TicketDataService ticketDataService;
    private final MetricsService metricsService;
    private final GroqInsightService groqInsightService;
    private final AiAnalysisCache aiAnalysisCache;
    private final NotificationService notificationService;
    private final NotifyStateStore notifyStateStore;

    public TicketNotifyService(
            TicketDataService ticketDataService,
            MetricsService metricsService,
            GroqInsightService groqInsightService,
            AiAnalysisCache aiAnalysisCache,
            NotificationService notificationService,
            NotifyStateStore notifyStateStore) {
        this.ticketDataService = ticketDataService;
        this.metricsService = metricsService;
        this.groqInsightService = groqInsightService;
        this.aiAnalysisCache = aiAnalysisCache;
        this.notificationService = notificationService;
        this.notifyStateStore = notifyStateStore;
    }

    public ManualNotifyResponse notifyTicket(String ticketId) {
        if (!notificationService.isWebhookEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Teams webhook is not configured. Set teams.webhook.url in application.properties or the environment.");
        }
        String id = ticketId == null ? "" : ticketId.trim();
        if (id.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketId is required");
        }

        TicketDataLoad load = ticketDataService.loadTicketData();
        List<Ticket> analyzed = metricsService.analyzeTickets(load.tickets());
        Ticket ticket =
                analyzed.stream()
                        .filter(t -> id.equalsIgnoreCase(t.getId()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Unknown ticket: " + ticketId));

        Ticket merged = notifyStateStore.merge(ticket);
        Instant lastNotified = merged.getLastNotifiedAt();
        if (lastNotified != null) {
            long minutesSince = ChronoUnit.MINUTES.between(lastNotified, Instant.now());
            if (minutesSince >= 0 && minutesSince < 30) {
                String assignee = formatAssigneeForResponse(merged.getAssignee());
                return new ManualNotifyResponse(
                        "skipped",
                        merged.getId(),
                        assignee,
                        null,
                        lastNotified,
                        "Recently notified");
            }
        }

        String cacheKey =
                aiAnalysisCache.buildKey(
                        merged.getId(),
                        merged.getFlags(),
                        merged.getSeverity(),
                        merged.getTimeInState(),
                        merged.getPrTime(),
                        merged.getTrendIndicator(),
                        merged.getPingPongTransitions());

        AiAnalysisCache.CachedEntry cached = aiAnalysisCache.getIfFresh(cacheKey);
        TicketInsightPayload raw;
        AiInsightOutcome outcome = null;
        if (cached != null) {
            raw = cached.insight();
        } else {
            outcome = groqInsightService.generateStructuredInsight(merged);
            raw = outcome.insight();
            aiAnalysisCache.put(cacheKey, raw);
        }

        boolean aiLimited = InsightUiFactory.isAiInsightLimited(raw, outcome);
        InsightUiFactory.buildUiInsight(merged, raw, aiLimited);

        String postedBody;
        try {
            postedBody = notificationService.sendManagerStyleMessage(merged);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        } catch (org.springframework.web.client.RestClientException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Teams webhook request failed: " + e.getMessage(), e);
        }

        Instant sentAt = Instant.now();
        notifyStateStore.recordSent(merged.getId(), sentAt);

        String assignee = formatAssigneeForResponse(merged.getAssignee());
        return new ManualNotifyResponse(
                "sent", merged.getId(), assignee, buildMessagePreview(postedBody), sentAt, null);
    }

    private static String formatAssigneeForResponse(String assignee) {
        if (assignee == null
                || assignee.isBlank()
                || "Unassigned".equalsIgnoreCase(assignee.trim())) {
            return "Unassigned";
        }
        return assignee.trim();
    }

    private static String buildMessagePreview(String issue) {
        String oneLine = issue.replace('\n', ' ').trim();
        if (oneLine.length() <= 180) {
            return oneLine;
        }
        return oneLine.substring(0, 177) + "...";
    }
}
