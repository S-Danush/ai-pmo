package com.aipmo.agent.service;

import com.aipmo.agent.dto.TeamsTextMessage;
import com.aipmo.agent.logging.PipelineMdc;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.DeliveryViewEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final RestClient restClient = RestClient.builder().build();
    private final String webhookUrl;

    public NotificationService(@Value("${teams.webhook.url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    public void sendToTeams(String message) {
        if (webhookUrl.isEmpty() || "YOUR_URL".equalsIgnoreCase(webhookUrl)) {
            log.info("Teams webhook not configured; skip notification payloadChars={}", charLen(message));
            return;
        }
        long t0 = System.currentTimeMillis();
        log.info("Teams webhook call started payloadChars={}", charLen(message));
        try {
            restClient
                    .post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TeamsTextMessage(message))
                    .retrieve()
                    .toBodilessEntity();
            long ms = System.currentTimeMillis() - t0;
            log.info("Teams webhook succeeded durationMs={}", ms);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            log.error(
                    "Teams webhook failed durationMs={} message={}",
                    ms,
                    safeMessage(e.getMessage()));
        }
    }

    /**
     * Manager-friendly Teams message: ticket id, summary, assignee, status, one problem line, and
     * suggested actions. No raw flags or huge hour numbers.
     */
    public void sendManagerIssueAlert(
            Ticket ticket,
            String displayStatus,
            String problem,
            String suggestedAction,
            String footnote) {
        PipelineMdc.stageAndAction(PipelineMdc.STAGE_NOTIFY, PipelineMdc.ACTION_TEAMS_WEBHOOK);
        if (webhookUrl.isEmpty() || "YOUR_URL".equalsIgnoreCase(webhookUrl)) {
            log.info("Teams webhook not configured; skip manager alert ticketId={}", ticket.getId());
            return;
        }
        String summ = safeSummary(ticket.getSummary());
        String sub = highRiskTitle(ticket);
        String message = sub
                + "\n\n"
                + "Ticket: "
                + ticket.getId()
                + "\n"
                + "Summary: "
                + summ
                + "\n"
                + "Assignee: "
                + formatAssignee(ticket.getAssignee())
                + "\n"
                + "Status: "
                + (displayStatus != null && !displayStatus.isBlank() ? displayStatus : "—")
                + "\n"
                + "Time in status: "
                + (ticket.getTimeInStatusLabel() != null ? ticket.getTimeInStatusLabel() : "—")
                + "\n"
                + "Last updated: "
                + (ticket.getLastActivityLabel() != null ? ticket.getLastActivityLabel() : "—")
                + "\n\n"
                + "Problem:\n"
                + (problem != null && !problem.isBlank() ? problem.trim() : "—")
                + "\n\n"
                + "Suggested Action:\n"
                + (suggestedAction != null && !suggestedAction.isBlank() ? suggestedAction.trim() : "—");
        if (footnote != null && !footnote.isBlank()) {
            message = message + "\n\n" + footnote.trim();
        }
        log.info(
                "Teams webhook dispatch started ticketId={} payloadChars={}",
                ticket.getId(),
                message.length());
        long t0 = System.currentTimeMillis();
        try {
            restClient
                    .post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TeamsTextMessage(message))
                    .retrieve()
                    .toBodilessEntity();
            long ms = System.currentTimeMillis() - t0;
            log.info("Teams notification sent ticketId={} durationMs={}", ticket.getId(), ms);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            log.error(
                    "Teams notification failed ticketId={} durationMs={} message={}",
                    ticket.getId(),
                    ms,
                    safeMessage(e.getMessage()));
        }
    }

    private static String highRiskTitle(Ticket t) {
        if (t.getDeliveryRisk() != null
                && DeliveryViewEnricher.RISK_HIGH.equals(t.getDeliveryRisk())) {
            return "⚠️ High Risk Issue";
        }
        return "⚠️ Delivery risk";
    }

    private static String safeSummary(String s) {
        if (s == null || s.isBlank() || "(no summary)".equals(s)) {
            return "—";
        }
        return s.trim();
    }

    private static String formatAssignee(String assignee) {
        if (assignee == null
                || assignee.isBlank()
                || "Unassigned".equalsIgnoreCase(assignee.trim())) {
            return "Unassigned ⚠️";
        }
        return assignee.trim();
    }

    private static int charLen(String s) {
        return s != null ? s.length() : 0;
    }

    private static String safeMessage(String m) {
        if (m == null) {
            return "";
        }
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }
}
