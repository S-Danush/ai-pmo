package com.aipmo.agent.service;

import com.aipmo.agent.dto.AiInsightOutcome;
import com.aipmo.agent.dto.TicketInsightPayload;
import com.aipmo.agent.model.Ticket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Routes PMO ticket insights and Teams-ready copy through Groq when configured, with
 * {@link LocalAIService} as the deterministic fallback.
 */
@Service
public class GroqInsightService {

    private static final Logger log = LoggerFactory.getLogger(GroqInsightService.class);

    /** One row per teammate for batched Groq team analytics. */
    public record TeamMemberBatchRow(
            String name,
            int assigned,
            int completed,
            int inProgress,
            int underReview,
            int blocked,
            int totalCommits,
            double avgPrReviewHours,
            String performanceLevel) {}

    private static final String STRUCTURED_SYSTEM =
            "You are a senior engineering-manager PMO assistant for a loan origination (LOS) and loan"
                    + " management (LMS) platform. You write concise, actionable delivery insights for"
                    + " engineering leaders. Use only the ticket JSON; do not invent integrations or"
                    + " tools not implied by the data. Output must be valid JSON only.";

    private static final String TEAMS_SYSTEM =
            "You write short, human Microsoft Teams messages to an assignee about a single work item."
                    + " Tone: supportive manager, clear next step, no jargon walls. Use only ticket facts"
                    + " provided. No markdown headings; plain paragraphs and line breaks are fine.";

    private static final String MEMBER_SYSTEM =
            "You write one short paragraph (2–4 sentences) of manager-facing coaching for a teammate,"
                    + " based only on the numeric workload summary JSON. No bullet lists unless essential.";

    private static final String MEMBER_BATCH_SYSTEM =
            "You write manager-facing coaching blurbs for several teammates at once. Each blurb is one"
                    + " short paragraph (2–4 sentences), based only on that row's numbers. Output valid"
                    + " JSON only.";

    private final GroqAIService groqAIService;
    private final LocalAIService localAIService;
    private final ObjectMapper objectMapper;

    public GroqInsightService(
            GroqAIService groqAIService, LocalAIService localAIService, ObjectMapper objectMapper) {
        this.groqAIService = groqAIService;
        this.localAIService = localAIService;
        this.objectMapper = objectMapper;
    }

    /**
     * One Groq round-trip for all team members. Returns {@code null} when Groq is off, on failure, or
     * when the response does not cover every name.
     */
    public Map<String, String> tryMemberInsightsBatch(List<TeamMemberBatchRow> rows) {
        if (!groqAIService.isEnabled() || rows == null || rows.isEmpty()) {
            return null;
        }
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (TeamMemberBatchRow r : rows) {
                ObjectNode o = objectMapper.createObjectNode();
                o.put("name", r.name() != null ? r.name() : "");
                o.put("assigned", r.assigned());
                o.put("completed", r.completed());
                o.put("inProgress", r.inProgress());
                o.put("underReview", r.underReview());
                o.put("blocked", r.blocked());
                o.put("totalCommits", r.totalCommits());
                o.put("avgPrReviewHours", r.avgPrReviewHours());
                o.put("performanceLevel", r.performanceLevel() != null ? r.performanceLevel() : "");
                arr.add(o);
            }
            String user =
                    "Teammate workload rows (JSON array, preserve order):\n"
                            + objectMapper.writeValueAsString(arr)
                            + "\n\nReturn ONLY a JSON object with key \"items\" whose value is a JSON array."
                            + " Each element must be {\"name\": string, \"insight\": string} matching the"
                            + " same people and order as the input rows. Each insight is non-empty prose.";
            String raw = groqAIService.complete(MEMBER_BATCH_SYSTEM, user, true);
            List<String> insights = parseMemberBatchInsights(raw);
            if (insights.size() != rows.size()) {
                throw new IllegalStateException(
                        "batch size mismatch expected=" + rows.size() + " got=" + insights.size());
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                String text = insights.get(i);
                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("empty insight at index " + i);
                }
                String name = rows.get(i).name() != null ? rows.get(i).name().trim() : "";
                out.put(name, text);
            }
            log.info("Groq team member batch ok count={}", rows.size());
            return out;
        } catch (Exception e) {
            log.warn("Groq team member batch failed; will fall back per member: {}", e.getMessage());
            return null;
        }
    }

    private List<String> parseMemberBatchInsights(String raw) throws Exception {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1).trim();
            }
            int fence = s.lastIndexOf("```");
            if (fence > 0) {
                s = s.substring(0, fence).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("no JSON object");
        }
        s = s.substring(start, end + 1);
        JsonNode root = objectMapper.readTree(s);
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            throw new IllegalStateException("no items array");
        }
        List<String> list = new ArrayList<>();
        for (JsonNode el : items) {
            list.add(el.path("insight").asText("").trim());
        }
        return list;
    }

    public AiInsightOutcome generateStructuredInsight(Ticket ticket) {
        if (!groqAIService.isEnabled()) {
            return localAIService.generateStructuredInsight(ticket);
        }
        try {
            String facts = ticketToJson(ticket);
            String user =
                    "Ticket JSON:\n"
                            + facts
                            + "\n\nReturn ONLY a JSON object (no markdown fences) with exactly these"
                            + " string keys: reasoning, rootCause, impact, recommendedAction, nudge,"
                            + " confidence. All values must be non-null strings. confidence must be one"
                            + " of: LOW, MEDIUM, HIGH. Keep each field under 600 characters.";
            String raw = groqAIService.complete(STRUCTURED_SYSTEM, user, true);
            TicketInsightPayload payload = parseInsightPayload(raw);
            log.info("Groq structured insight ok ticketId={}", ticket.getId());
            return new AiInsightOutcome(payload, false, true, true, false);
        } catch (Exception e) {
            log.warn(
                    "Groq structured insight failed ticketId={}; using local engine: {}",
                    ticket.getId(),
                    e.getMessage());
            return localAIService.generateStructuredInsight(ticket);
        }
    }

    public String generateManagerStyleMessage(Ticket ticket) {
        if (!groqAIService.isEnabled()) {
            return localAIService.generateManagerStyleMessage(ticket);
        }
        try {
            String facts = ticketToJson(ticket);
            String user =
                    "Compose the full Teams message body (no JSON). Include a brief greeting if there is"
                            + " an assignee name, a short observation tied to status and dwell/PR if"
                            + " relevant, why it matters, one concrete next step, and a one-line impact if"
                            + " natural. Ticket JSON:\n"
                            + facts;
            String body = groqAIService.complete(TEAMS_SYSTEM, user, false);
            log.info("Groq manager-style Teams body ok ticketId={}", ticket.getId());
            return body.trim();
        } catch (Exception e) {
            log.warn(
                    "Groq Teams message failed ticketId={}; using local template: {}",
                    ticket.getId(),
                    e.getMessage());
            return localAIService.generateManagerStyleMessage(ticket);
        }
    }

    public String generateTeamMemberPerformanceInsight(
            String name,
            int assigned,
            int completed,
            int inProgress,
            int underReview,
            int blocked,
            int totalCommits,
            double avgPrReviewHours,
            String performanceLevel) {
        if (!groqAIService.isEnabled()) {
            return localAIService.generateTeamMemberPerformanceInsight(
                    name,
                    assigned,
                    completed,
                    inProgress,
                    underReview,
                    blocked,
                    totalCommits,
                    avgPrReviewHours,
                    performanceLevel);
        }
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("name", name != null ? name : "");
            n.put("assigned", assigned);
            n.put("completed", completed);
            n.put("inProgress", inProgress);
            n.put("underReview", underReview);
            n.put("blocked", blocked);
            n.put("totalCommits", totalCommits);
            n.put("avgPrReviewHours", avgPrReviewHours);
            n.put("performanceLevel", performanceLevel != null ? performanceLevel : "");
            String user =
                    "Teammate summary JSON:\n"
                            + n
                            + "\n\nWrite one coaching paragraph for an engineering manager dashboard.";
            String text = groqAIService.complete(MEMBER_SYSTEM, user, false).trim();
            if (text.isBlank()) {
                throw new IllegalStateException("empty Groq member insight");
            }
            log.info("Groq team member insight ok name={}", name);
            return text;
        } catch (Exception e) {
            log.warn(
                    "Groq team member insight failed name={}; using local rules: {}",
                    name,
                    e.getMessage());
            return localAIService.generateTeamMemberPerformanceInsight(
                    name,
                    assigned,
                    completed,
                    inProgress,
                    underReview,
                    blocked,
                    totalCommits,
                    avgPrReviewHours,
                    performanceLevel);
        }
    }

    private TicketInsightPayload parseInsightPayload(String raw) throws Exception {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1).trim();
            }
            int fence = s.lastIndexOf("```");
            if (fence > 0) {
                s = s.substring(0, fence).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("no JSON object in model output");
        }
        s = s.substring(start, end + 1);
        JsonNode root = objectMapper.readTree(s);
        return TicketInsightPayload.builder()
                .reasoning(textOrEmpty(root, "reasoning"))
                .rootCause(textOrEmpty(root, "rootCause"))
                .impact(textOrEmpty(root, "impact"))
                .recommendedAction(textOrEmpty(root, "recommendedAction"))
                .nudge(textOrEmpty(root, "nudge"))
                .confidence(normalizeConfidence(textOrEmpty(root, "confidence")))
                .build();
    }

    private static String textOrEmpty(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return "";
        }
        return n.asText("").trim();
    }

    private static String normalizeConfidence(String c) {
        if (c == null || c.isBlank()) {
            return "MEDIUM";
        }
        String u = c.trim().toUpperCase(Locale.ROOT);
        if ("LOW".equals(u) || "MEDIUM".equals(u) || "HIGH".equals(u)) {
            return u;
        }
        return "MEDIUM";
    }

    private String ticketToJson(Ticket t) throws Exception {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", nullToEmpty(t.getId()));
        n.put("summary", nullToEmpty(t.getSummary()));
        n.put("status", nullToEmpty(t.getStatus()));
        n.put("displayStatus", nullToEmpty(t.getDisplayStatus()));
        n.put("assignee", nullToEmpty(t.getAssignee()));
        n.put("priority", nullToEmpty(t.getPriority()));
        n.put("severity", nullToEmpty(t.getSeverity()));
        n.put("bottleneckCategory", nullToEmpty(t.getBottleneckCategory()));
        n.put("timeInStateHours", t.getTimeInState());
        n.put("prTimeHours", t.getPrTime());
        n.put("prStatus", nullToEmpty(t.getPrStatus()));
        n.put("dependency", nullToEmpty(t.getDependency()));
        n.put("bounceCount", t.getBounceCount());
        n.put("pingPongTransitions", t.getPingPongTransitions());
        n.put("complexity", nullToEmpty(t.getComplexity()));
        n.put("agingBucket", nullToEmpty(t.getAgingBucket()));
        n.put("deliveryRisk", nullToEmpty(t.getDeliveryRisk()));
        n.put("trendIndicator", nullToEmpty(t.getTrendIndicator()));
        n.put("commitCount", t.getCommitCount());
        n.put("prNumber", t.getPrNumber() != null ? t.getPrNumber() : 0);
        n.put("insight", nullToEmpty(t.getInsight()));
        ArrayNode flags = n.putArray("flags");
        if (t.getFlags() != null) {
            for (String f : t.getFlags()) {
                if (f != null && !f.isBlank()) {
                    flags.add(f);
                }
            }
        }
        ArrayNode corr = n.putArray("correlationInsights");
        if (t.getCorrelationInsights() != null) {
            for (String c : t.getCorrelationInsights()) {
                if (c != null && !c.isBlank()) {
                    corr.add(c);
                }
            }
        }
        if (t.getRootCauseAnalysis() != null) {
            ObjectNode rc = n.putObject("rootCauseAnalysis");
            rc.put("primaryCause", nullToEmpty(t.getRootCauseAnalysis().getPrimaryCause()));
            rc.put("confidence", nullToEmpty(t.getRootCauseAnalysis().getConfidence()));
            ArrayNode rs = rc.putArray("reasons");
            if (t.getRootCauseAnalysis().getReasons() != null) {
                for (String r : t.getRootCauseAnalysis().getReasons()) {
                    if (r != null && !r.isBlank()) {
                        rs.add(r);
                    }
                }
            }
        }
        n.put("recommendedAction", nullToEmpty(t.getRecommendedAction()));
        n.put("actionOwner", nullToEmpty(t.getActionOwner()));
        ArrayNode ex = n.putArray("explainabilityFactors");
        if (t.getExplainabilityFactors() != null) {
            for (String e : t.getExplainabilityFactors()) {
                if (e != null && !e.isBlank()) {
                    ex.add(e);
                }
            }
        }
        return objectMapper.writeValueAsString(n);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
