package com.aipmo.agent.service;

import com.aipmo.agent.dto.AiInsightOutcome;
import com.aipmo.agent.dto.TicketInsightPayload;
import com.aipmo.agent.exception.AiInsightGenerationException;
import com.aipmo.agent.logging.PipelineMdc;
import com.aipmo.agent.model.Ticket;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final int DEBUG_RAW_SNIPPET_MAX = 400;
    private static final int NUDGE_MAX_LEN = 380;

    private static final Set<String> ALLOWED_CONFIDENCE = Set.of("LOW", "MEDIUM", "HIGH");

    private static final String FALLBACK_TRANSPORT =
            "AI temporarily unavailable. Please check SSL/proxy configuration.";
    private static final String FALLBACK_SSL =
            "AI temporarily unavailable: SSL/TLS trust issue. JVM truststore cannot validate the OpenAI certificate "
                    + "(fix Java cacerts or corporate proxy).";

    private final RestClient openAiClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final boolean failFast;
    private final int maxOpenAiAttempts;

    public AIService(
            ObjectMapper objectMapper,
            @Qualifier("openAiRestClient") RestClient openAiClient,
            @Value("${openai.api.key:}") String apiKey,
            @Value("${openai.model}") String model,
            @Value("${ai.fail-fast:false}") boolean failFast,
            @Value("${ai.openai.max-attempts:3}") int maxOpenAiAttempts) {
        this.objectMapper = objectMapper;
        this.openAiClient = openAiClient;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.failFast = failFast;
        this.maxOpenAiAttempts = Math.max(1, maxOpenAiAttempts);
    }

    /**
     * One OpenAI call returning structured insight plus an executive Teams nudge (JSON with all
     * fields). Deterministic repair when the model omits anchors or grounding.
     */
    public AiInsightOutcome generateStructuredInsight(Ticket ticket) {
        PipelineMdc.stageAndAction(PipelineMdc.STAGE_AI, PipelineMdc.ACTION_INSIGHT_REQUEST);
        if (apiKey.isEmpty()) {
            log.warn(
                    "OpenAI API key not set; insight will use metric-only fallback ticketId={} openAiAttempted=false",
                    ticket.getId());
            return new AiInsightOutcome(prefixDataDrivenFallback(ticket, "OpenAI API key not configured. "), false, false, false);
        }

        String userContent = buildExecutiveUserPrompt(ticket);
        long t0 = System.currentTimeMillis();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.35);
        body.put("max_tokens", 900);
        body.put("response_format", Map.of("type", "json_object"));
        body.put(
                "messages",
                List.of(
                        Map.of(
                                "role",
                                "system",
                                "content",
                                """
                                You are a senior PMO / delivery lead writing for engineering directors \
                                and EMs. Output ONE JSON object only — no markdown, no preamble, no chain-of-thought.

                                Required keys: rootCause (string), impact (string), recommendedAction (string), \
                                nudge (string), confidence (string: LOW, MEDIUM, or HIGH).

                                Style rules:
                                - Correlate multiple signals when flags list has more than one item (explain how \
                                they reinforce risk, e.g. dwell + PR_DELAY + trend).
                                - Be specific: cite the ticket id, status name, hours in state, PR hours, severity, \
                                trend, and named flags using the exact numbers/strings provided.
                                - Avoid generic platitudes ("align stakeholders", "improve communication") unless \
                                tied to those concrete facts.
                                - rootCause: 2–4 tight sentences; impact: 1–3 sentences on delivery/reputation risk; \
                                recommendedAction: imperative, measurable next step(s) that clearly address THIS ticket.
                                - nudge: 2–3 lines, Microsoft Teams tone, collaborative, includes ticket id once, \
                                references severity and top flags briefly, ends with one concrete next step. Plain text, \
                                no JSON inside nudge.
                                - recommendedAction MUST explicitly reference this ticket (id or status or the \
                                numeric dwell/PR values or at least one flag token) so it cannot apply to any random issue.
                                """),
                        Map.of("role", "user", "content", userContent)));

        try {
            ResponseEntity<String> entity = postChatCompletionsWithRetry(ticket, body);
            String raw = entity.getBody();
            debugLogRawJson("insight", ticket.getId(), raw);
            if (raw == null || raw.isBlank()) {
                long ms = System.currentTimeMillis() - t0;
                log.error(
                        "OpenAI FAILED ticketId={} durationMs={} status=FAILED reason=empty_body httpStatus={}",
                        ticket.getId(),
                        ms,
                        entity.getStatusCode().value());
                if (failFast) {
                    throw new AiInsightGenerationException(
                            "OpenAI returned an empty body for ticket " + ticket.getId());
                }
                return new AiInsightOutcome(
                        prefixDataDrivenFallback(ticket, FALLBACK_TRANSPORT + " "), false, false, true);
            }

            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                long ms = System.currentTimeMillis() - t0;
                log.error(
                        "OpenAI FAILED ticketId={} durationMs={} status=FAILED reason=empty_model_content",
                        ticket.getId(),
                        ms);
                if (failFast) {
                    throw new AiInsightGenerationException(
                            "OpenAI returned empty model content for ticket " + ticket.getId());
                }
                return new AiInsightOutcome(
                        prefixDataDrivenFallback(ticket, FALLBACK_TRANSPORT + " "), false, false, true);
            }

            JsonNode obj = objectMapper.readTree(content.asText());
            String rc = textOrEmpty(obj, "rootCause");
            String im = textOrEmpty(obj, "impact");
            String ra = textOrEmpty(obj, "recommendedAction");
            String nudge = textOrEmpty(obj, "nudge");
            String conf = normalizeConfidence(textOrEmpty(obj, "confidence"));
            if (rc.isEmpty() && im.isEmpty() && ra.isEmpty() && nudge.isEmpty()) {
                long ms = System.currentTimeMillis() - t0;
                log.error(
                        "OpenAI FAILED ticketId={} durationMs={} status=FAILED reason=empty_json_fields",
                        ticket.getId(),
                        ms);
                if (failFast) {
                    throw new AiInsightGenerationException(
                            "OpenAI returned JSON with no usable fields for ticket " + ticket.getId());
                }
                return new AiInsightOutcome(
                        prefixDataDrivenFallback(ticket, FALLBACK_TRANSPORT + " "), false, false, true);
            }
            ra = ensureRecommendedActionGrounded(ticket, ra);
            nudge = ensureNudgeGrounded(ticket, nudge, ra);
            nudge = clampNudge(nudge);

            TicketInsightPayload built =
                    TicketInsightPayload.builder()
                            .rootCause(rc)
                            .impact(im)
                            .recommendedAction(ra)
                            .nudge(nudge)
                            .confidence(conf)
                            .build();
            long ms = System.currentTimeMillis() - t0;
            log.info(
                    "OpenAI SUCCESS ticketId={} durationMs={} status=SUCCESS",
                    ticket.getId(),
                    ms);
            return new AiInsightOutcome(built, false, true, true);
        } catch (AiInsightGenerationException e) {
            throw e;
        } catch (RestClientResponseException e) {
            return handleOpenAiRestFailure(ticket, t0, e);
        } catch (ResourceAccessException e) {
            return handleOpenAiRestFailure(ticket, t0, e);
        } catch (RestClientException e) {
            return handleOpenAiRestFailure(ticket, t0, e);
        } catch (JsonProcessingException e) {
            long ms = System.currentTimeMillis() - t0;
            log.error(
                    "OpenAI FAILED ticketId={} durationMs={} status=FAILED reason=json_parse error={}",
                    ticket.getId(),
                    ms,
                    safeMessage(e.getMessage()),
                    e);
            if (failFast) {
                throw new AiInsightGenerationException(
                        "Failed to parse OpenAI JSON for ticket " + ticket.getId(), e);
            }
            return new AiInsightOutcome(
                    prefixDataDrivenFallback(ticket, FALLBACK_TRANSPORT + " "), false, false, true);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            log.error(
                    "OpenAI FAILED ticketId={} durationMs={} status=FAILED reason=unexpected error={}",
                    ticket.getId(),
                    ms,
                    safeMessage(e.getMessage()),
                    e);
            if (isSslTrustIssue(e)) {
                logPkixSslError(ticket.getId(), e);
            }
            if (failFast) {
                throw new AiInsightGenerationException(
                        "Unexpected error during OpenAI insight for ticket " + ticket.getId(), e);
            }
            boolean ssl = isSslTrustIssue(e);
            return new AiInsightOutcome(
                    buildTransportBannerFallback(ticket, ssl), true, false, true);
        }
    }

    private AiInsightOutcome handleOpenAiRestFailure(Ticket ticket, long t0, RestClientException e) {
        long ms = System.currentTimeMillis() - t0;
        if (e instanceof RestClientResponseException r) {
            log.error(
                    "OpenAI FAILED ticketId={} durationMs={} status=FAILED httpStatus={} error={}",
                    ticket.getId(),
                    ms,
                    r.getStatusCode().value(),
                    safeMessage(r.getMessage()),
                    e);
        } else {
            log.error(
                    "OpenAI FAILED ticketId={} durationMs={} status=FAILED error={}",
                    ticket.getId(),
                    ms,
                    safeMessage(e.getMessage()),
                    e);
        }
        if (isSslTrustIssue(e)) {
            logPkixSslError(ticket.getId(), e);
        }
        if (failFast) {
            throw new AiInsightGenerationException(
                    "OpenAI request failed for ticket " + ticket.getId() + " after retries", e);
        }
        boolean ssl = isSslTrustIssue(e);
        return new AiInsightOutcome(buildTransportBannerFallback(ticket, ssl), true, false, true);
    }

    private ResponseEntity<String> postChatCompletionsWithRetry(Ticket ticket, Map<String, Object> body) {
        int max = maxOpenAiAttempts;
        RestClientException last = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            long a0 = System.currentTimeMillis();
            try {
                log.info(
                        "OpenAI request method=POST uri=https://api.openai.com{} ticketId={} model={} attempt={}/{}",
                        CHAT_COMPLETIONS_PATH,
                        ticket.getId(),
                        model,
                        attempt,
                        max);
                ResponseEntity<String> entity =
                        openAiClient
                                .post()
                                .uri(CHAT_COMPLETIONS_PATH)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body)
                                .retrieve()
                                .toEntity(String.class);
                long aMs = System.currentTimeMillis() - a0;
                log.info(
                        "OpenAI attempt OK ticketId={} attempt={}/{} httpStatus={} durationMs={}",
                        ticket.getId(),
                        attempt,
                        max,
                        entity.getStatusCode().value(),
                        aMs);
                return entity;
            } catch (RestClientResponseException ex) {
                last = ex;
                long aMs = System.currentTimeMillis() - a0;
                if (isSslTrustIssue(ex)) {
                    throw ex;
                }
                log.warn(
                        "OpenAI attempt {}/{} transient HTTP failure ticketId={} durationMs={} httpStatus={} error={}",
                        attempt,
                        max,
                        ticket.getId(),
                        aMs,
                        ex.getStatusCode().value(),
                        safeMessage(ex.getMessage()));
                if (attempt >= max || !isRetryableHttpStatus(ex.getStatusCode().value())) {
                    throw ex;
                }
                backoffSleep(attempt);
            } catch (ResourceAccessException ex) {
                last = ex;
                long aMs = System.currentTimeMillis() - a0;
                if (isSslTrustIssue(ex)) {
                    throw ex;
                }
                log.warn(
                        "OpenAI attempt {}/{} network failure ticketId={} durationMs={} error={}",
                        attempt,
                        max,
                        ticket.getId(),
                        aMs,
                        safeMessage(ex.getMessage()));
                if (attempt >= max) {
                    throw ex;
                }
                backoffSleep(attempt);
            } catch (RestClientException ex) {
                last = ex;
                long aMs = System.currentTimeMillis() - a0;
                if (isSslTrustIssue(ex)) {
                    throw ex;
                }
                log.warn(
                        "OpenAI attempt {}/{} client failure ticketId={} durationMs={} error={}",
                        attempt,
                        max,
                        ticket.getId(),
                        aMs,
                        safeMessage(ex.getMessage()));
                if (attempt >= max) {
                    throw ex;
                }
                backoffSleep(attempt);
            }
        }
        throw last != null
                ? last
                : new RestClientException("OpenAI failed after " + max + " attempts for ticket " + ticket.getId());
    }

    private static void backoffSleep(int failedAttemptNumber) {
        try {
            Thread.sleep(1000L * failedAttemptNumber);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during OpenAI retry backoff", ie);
        }
    }

    private static boolean isRetryableHttpStatus(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static void logPkixSslError(String ticketId, Throwable ex) {
        if (containsPkixInChain(ex)) {
            log.error("SSL TRUST FAILURE: OpenAI certificate chain not trusted by the JVM (ticketId={})", ticketId);
            log.error(
                    "Fix: import the issuing CA, set ssl.truststore.path (+ password), and/or proxy.host/proxy.port — see README-SSL.md");
        }
        log.error(
                "SSL ERROR: TLS to OpenAI could not be established (ticketId={}). "
                        + "Review trust material, corporate proxy, and TLS inspection policies.",
                ticketId,
                ex);
    }

    private static boolean containsPkixInChain(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.contains("PKIX")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSslTrustIssue(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SSLException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null && m.contains("PKIX")) {
                return true;
            }
        }
        return false;
    }

    /** User-visible banner plus ticket-grounded metrics (not a mock: same deterministic metrics as normal fallback). */
    private TicketInsightPayload buildTransportBannerFallback(Ticket ticket, boolean sslRelated) {
        String banner = sslRelated ? FALLBACK_SSL : FALLBACK_TRANSPORT;
        return prefixDataDrivenFallback(ticket, banner + " ");
    }

    private TicketInsightPayload prefixDataDrivenFallback(Ticket ticket, String prefix) {
        TicketInsightPayload data = buildDataDrivenFallback(ticket);
        return TicketInsightPayload.builder()
                .rootCause(prefix + data.getRootCause())
                .impact(prefix + data.getImpact())
                .recommendedAction(prefix + data.getRecommendedAction())
                .nudge(prefix + data.getNudge())
                .confidence("LOW")
                .build();
    }

    private void debugLogRawJson(String kind, String ticketId, String raw) {
        if (!log.isDebugEnabled() || raw == null) {
            return;
        }
        String snippet = raw.length() <= DEBUG_RAW_SNIPPET_MAX ? raw : raw.substring(0, DEBUG_RAW_SNIPPET_MAX) + "...";
        log.debug("OpenAI raw {} response ticketId={} chars={} snippet={}", kind, ticketId, raw.length(), snippet);
    }

    private static String normalizeConfidence(String raw) {
        if (raw == null || raw.isBlank()) {
            return "MEDIUM";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_CONFIDENCE.contains(u) ? u : "MEDIUM";
    }

    private static String safeMessage(String m) {
        if (m == null) {
            return "";
        }
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }

    private static String textOrEmpty(JsonNode obj, String field) {
        JsonNode n = obj.path(field);
        return n.isMissingNode() || !n.isTextual() ? "" : n.asText("").trim();
    }

    private static String buildExecutiveUserPrompt(Ticket ticket) {
        String flags =
                ticket.getFlags() != null && !ticket.getFlags().isEmpty()
                        ? String.join(", ", ticket.getFlags())
                        : "(none)";
        String severity = ticket.getSeverity() != null ? ticket.getSeverity() : "n/a";
        String trend = ticket.getTrendIndicator() != null ? ticket.getTrendIndicator() : "STABLE";
        return """
                Produce executive PMO guidance for this single work item. Use ONLY the facts below; do not invent \
                fields, people, or dates.

                Ticket id: %s
                Current status: %s
                Time in current status (hours): %d
                PR cycle time (hours): %d
                Detected flags: %s
                Severity: %s
                Trend vs current batch: %s
                Status transitions (changelog total, if available): %d
                Status ping-pong reversals (A↔B same pair): %d

                Instructions:
                - If multiple flags are present, explain how they combine (not isolated bullet lists).
                - Quote at least one numeric fact (dwell hours and/or PR hours) in rootCause and again in recommendedAction or nudge.
                - recommendedAction must be actionable this week and unmistakably about ticket %s.
                """
                .formatted(
                        ticket.getId(),
                        ticket.getStatus(),
                        ticket.getTimeInState(),
                        ticket.getPrTime(),
                        flags,
                        severity,
                        trend,
                        ticket.getStatusChanges(),
                        ticket.getPingPongTransitions(),
                        ticket.getId());
    }

    /**
     * Ensures the recommended action cannot be mistaken for generic advice: it must reference the
     * ticket id, status, a numeric fact from the ticket, a flag token, or severity.
     */
    private static String ensureRecommendedActionGrounded(Ticket ticket, String action) {
        if (action == null) {
            action = "";
        }
        if (recommendedActionReferencesTicket(ticket, action)) {
            return action.trim();
        }
        log.debug("Repairing ungrounded recommendedAction ticketId={}", ticket.getId());
        String flags =
                ticket.getFlags() == null || ticket.getFlags().isEmpty()
                        ? "none"
                        : String.join(", ", ticket.getFlags());
        String trimmed = action.isBlank() ? "timebox an unblock." : action.trim();
        return ("For "
                        + ticket.getId()
                        + " in \""
                        + nullToPlain(ticket.getStatus())
                        + "\" ("
                        + ticket.getTimeInState()
                        + "h dwell, PR "
                        + ticket.getPrTime()
                        + "h, flags "
                        + flags
                        + "): ")
                + trimmed;
    }

    private static boolean recommendedActionReferencesTicket(Ticket t, String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        String a = action.toLowerCase(Locale.ROOT);
        if (t.getId() != null && !t.getId().isBlank() && a.contains(t.getId().toLowerCase(Locale.ROOT))) {
            return true;
        }
        String dwell = Integer.toString(t.getTimeInState());
        if (a.contains(dwell)) {
            return true;
        }
        if (t.getPrTime() > 0 && a.contains(Integer.toString(t.getPrTime()))) {
            return true;
        }
        String st = t.getStatus();
        if (st != null && st.length() >= 2 && a.contains(st.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (t.getFlags() != null) {
            for (String f : t.getFlags()) {
                if (f != null && !f.isBlank() && a.contains(f.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        if (t.getSeverity() != null && a.contains(t.getSeverity().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return false;
    }

    private static String ensureNudgeGrounded(Ticket ticket, String nudge, String recommendedAction) {
        if (nudge == null || nudge.isBlank()) {
            return buildNudgeFromRecommendation(ticket, recommendedAction);
        }
        if (ticket.getId() != null && nudge.contains(ticket.getId())) {
            return nudge.trim();
        }
        return ("[" + ticket.getId() + "] " + nudge).trim();
    }

    private static String buildNudgeFromRecommendation(Ticket ticket, String recommendedAction) {
        String sev = ticket.getSeverity() != null ? ticket.getSeverity() : "n/a";
        String flags =
                ticket.getFlags() != null && !ticket.getFlags().isEmpty()
                        ? String.join(", ", ticket.getFlags())
                        : "none";
        return ("["
                        + ticket.getId()
                        + "] "
                        + ticket.getTimeInState()
                        + "h in \""
                        + nullToPlain(ticket.getStatus())
                        + "\" (severity "
                        + sev
                        + ", flags "
                        + flags
                        + ").\nNext: "
                        + (recommendedAction == null || recommendedAction.isBlank()
                                ? "Owner + reviewer: 15m sync today with a single exit criterion."
                                : recommendedAction));
    }

    private static String clampNudge(String nudge) {
        if (nudge == null) {
            return "";
        }
        if (nudge.length() <= NUDGE_MAX_LEN) {
            return nudge;
        }
        return nudge.substring(0, NUDGE_MAX_LEN - 3) + "...";
    }

    /** Fallback when the model or API is unavailable: every sentence tied to ticket metrics. */
    public TicketInsightPayload buildDataDrivenFallback(Ticket ticket) {
        String id = ticket.getId() != null ? ticket.getId() : "ticket";
        String status = nullToPlain(ticket.getStatus());
        int dwell = ticket.getTimeInState();
        int pr = ticket.getPrTime();
        String flags =
                ticket.getFlags() != null && !ticket.getFlags().isEmpty()
                        ? String.join(", ", ticket.getFlags())
                        : "none";
        String sev = ticket.getSeverity() != null ? ticket.getSeverity() : "n/a";
        String trend = ticket.getTrendIndicator() != null ? ticket.getTrendIndicator() : "STABLE";
        int pp = ticket.getPingPongTransitions();

        StringBuilder rc = new StringBuilder();
        rc.append(id)
                .append(" has spent ")
                .append(dwell)
                .append("h in \"")
                .append(status)
                .append("\" with PR cycle at ")
                .append(pr)
                .append("h (batch trend ")
                .append(trend)
                .append("). ");
        if (!"none".equals(flags)) {
            rc.append("Signals [").append(flags).append("] together indicate delivery friction beyond normal variance.");
        } else {
            rc.append("No delay flags fired; keep monitoring against sprint commitments.");
        }
        if (pp > 0) {
            rc.append(" Changelog shows ").append(pp).append(" status ping-pong reversal(s) on the same pair of states.");
        }

        String impact =
                "At "
                        + dwell
                        + "h dwell"
                        + (pr > 0 ? (" and " + pr + "h PR time") : "")
                        + ", slip risk rises for dependent work and forecast accuracy for this train weakens unless the item clears inside the next few days.";

        String ra =
                "For "
                        + id
                        + ": within 24h, run a 20-minute triage with issue owner + status owner; write down one exit criterion for \""
                        + status
                        + "\" and assign a single DRI to execute it, explicitly addressing flags ["
                        + flags
                        + "] and the "
                        + dwell
                        + "h clock.";

        String nudge =
                ("["
                        + id
                        + "] "
                        + sev
                        + " — "
                        + dwell
                        + "h in \""
                        + status
                        + "\", PR "
                        + pr
                        + "h, flags ["
                        + flags
                        + "].\nPlease confirm today's unblock step and who owns the next transition out of \""
                        + status
                        + "\".");

        return TicketInsightPayload.builder()
                .rootCause(rc.toString())
                .impact(impact)
                .recommendedAction(ra)
                .nudge(clampNudge(nudge))
                .confidence("MEDIUM")
                .build();
    }

    /** @deprecated Use {@link #buildDataDrivenFallback(Ticket)}; retained for any external callers. */
    @Deprecated
    public TicketInsightPayload fallbackInsight(Ticket ticket) {
        return buildDataDrivenFallback(ticket);
    }

    /** @deprecated Nudge is included in {@link #generateStructuredInsight(Ticket)} payload. */
    @Deprecated
    public String fallbackNudge(Ticket ticket, TicketInsightPayload insight) {
        if (insight != null && insight.getNudge() != null && !insight.getNudge().isBlank()) {
            return insight.getNudge();
        }
        return buildDataDrivenFallback(ticket).getNudge();
    }

    public static String formatInsightText(TicketInsightPayload p) {
        String c = p.getConfidence() != null && !p.getConfidence().isBlank() ? p.getConfidence() : "MEDIUM";
        return "Root Cause:\n"
                + nullToDash(p.getRootCause())
                + "\n\nImpact:\n"
                + nullToDash(p.getImpact())
                + "\n\nRecommended Action:\n"
                + nullToDash(p.getRecommendedAction())
                + "\n\nConfidence:\n"
                + c;
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String nullToPlain(String s) {
        return s == null || s.isBlank() ? "Unknown" : s;
    }
}
