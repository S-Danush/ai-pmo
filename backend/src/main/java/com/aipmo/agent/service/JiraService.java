package com.aipmo.agent.service;

import com.aipmo.agent.config.JiraProperties;
import com.aipmo.agent.dto.JiraDebugResponse;
import com.aipmo.agent.exception.JiraIntegrationException;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.DeliveryViewEnricher;
import com.aipmo.agent.util.JiraTime;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class JiraService {

    private static final Logger log = LoggerFactory.getLogger(JiraService.class);

    private final RestClient jiraRestClient;
    private final JiraProperties jiraProperties;

    public JiraService(@Qualifier("jiraRestClient") RestClient jiraRestClient, JiraProperties jiraProperties) {
        this.jiraRestClient = jiraRestClient;
        this.jiraProperties = jiraProperties;
    }

    /**
     * Fetches and maps Jira issues. Requires complete Jira configuration.
     *
     * @throws JiraIntegrationException on HTTP errors, parse errors, empty issues for JQL, or unexpected body
     */
    public List<Ticket> fetchTicketsRequired() {
        if (!jiraProperties.isComplete()) {
            throw new IllegalStateException(
                    "fetchTicketsRequired called with incomplete Jira config: " + jiraProperties.describeConfigurationGaps());
        }
        int max = clampMax(jiraProperties.resolvedSearchMax());
        String jql = jiraProperties.resolvedJql();
        log.info("Final JQL used: {}", jql);
        String requestSummary = buildSearchRequestSummary(jql, max);
        log.info("Jira search about to call {} jql={}", requestSummary, jql);

        JsonNode root = executeSearchJson(jql, max, requestSummary);
        JsonNode issues = root.get("issues");
        long total = root.path("total").asLong(-1);
        int rawCount = issues.size();
        log.info("Jira search response httpStatus=200 issuesReturnedInPage={} totalFromJira={}", rawCount, total);

        log.info(
                "Filtering issue types: {} (excluding Sub-tasks)",
                String.join(", ", jiraProperties.resolvedAllowedIssueTypeNames()));
        List<JsonNode> filtered = filterIssuesByAllowedTypes(issues, jql);
        log.info("Jira issue-type safety filter: rawCount={} keptCount={}", rawCount, filtered.size());
        if (filtered.isEmpty()) {
            throw new JiraIntegrationException(
                    "Jira returned "
                            + rawCount
                            + " issues but none matched allowed issue types "
                            + jiraProperties.resolvedAllowedIssueTypeNames()
                            + ". Check jira.issue.types and JQL.",
                    200);
        }

        List<Ticket> out = new ArrayList<>(filtered.size());
        boolean loggedSample = false;
        for (JsonNode issue : filtered) {
            Ticket t = mapIssue(issue);
            if (!loggedSample) {
                log.info(
                        "Sample mapped ticket: id={} status={} assignee={} timeInStateHours={}",
                        t.getId(),
                        t.getStatus(),
                        t.getAssignee(),
                        t.getTimeInState());
                loggedSample = true;
            }
            out.add(t);
        }
        log.info("Fetched {} tickets from Jira (OPEN only)", out.size());
        return out;
    }

    /** Debug smoke test; avoids throwing so the HTTP response body stays JSON. */
    public JiraDebugResponse probeIntegration() {
        if (!jiraProperties.isComplete()) {
            return new JiraDebugResponse(
                    false,
                    jiraProperties.describeConfigurationGaps(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Incomplete Jira configuration");
        }
        int max = clampMax(jiraProperties.resolvedSearchMax());
        String jql = jiraProperties.resolvedJql();
        log.info("Final JQL used: {}", jql);
        String requestSummary = buildSearchRequestSummary(jql, max);
        try {
            log.info("Jira debug probe {} jql={}", requestSummary, jql);
            JsonNode root = executeSearchJson(jql, max, requestSummary);
            JsonNode issues = root.get("issues");
            long total = root.path("total").asLong(-1);
            log.info(
                    "Filtering issue types: {} (excluding Sub-tasks)",
                    String.join(", ", jiraProperties.resolvedAllowedIssueTypeNames()));
            List<JsonNode> filtered = filterIssuesByAllowedTypes(issues, jql);
            int n = filtered.size();
            String firstKey = null;
            String firstStatus = null;
            String firstIssueAssignee = null;
            String sampleMapped = null;
            String filterNote = null;
            if (n == 0 && issues.size() > 0) {
                filterNote =
                        "All "
                                + issues.size()
                                + " issues were removed by issue-type filter; allowed types are "
                                + jiraProperties.resolvedAllowedIssueTypeNames();
            }
            if (n > 0) {
                JsonNode first = filtered.get(0);
                firstKey = first.path("key").asText("");
                firstStatus = first.path("fields").path("status").path("name").asText("");
                Ticket mapped = mapIssue(first);
                firstIssueAssignee = mapped.getAssignee();
                sampleMapped = "id="
                        + mapped.getId()
                        + " status="
                        + mapped.getStatus()
                        + " assignee="
                        + mapped.getAssignee();
            }
            return new JiraDebugResponse(
                    true,
                    null,
                    requestSummary,
                    jql,
                    200,
                    total,
                    n,
                    firstKey,
                    firstStatus,
                    firstIssueAssignee,
                    sampleMapped,
                    filterNote);
        } catch (JiraIntegrationException e) {
            return new JiraDebugResponse(
                    true,
                    null,
                    requestSummary,
                    jql,
                    e.getHttpStatus(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    e.getMessage());
        } catch (RestClientResponseException e) {
            return new JiraDebugResponse(
                    true,
                    null,
                    requestSummary,
                    jql,
                    e.getStatusCode().value(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    safeJiraMessage(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            return new JiraDebugResponse(
                    true,
                    null,
                    requestSummary,
                    jql,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    safeJiraMessage(e.getMessage()));
        }
    }

    private JsonNode executeSearchJson(String jql, int max, String requestSummaryForLog) {
        long t0 = System.currentTimeMillis();
        log.info("Jira API search started maxResults={} endpoint=POST /rest/api/3/search/jql", max);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("jql", jql);
        requestBody.put("maxResults", max);
        requestBody.put("expand", "changelog");
        // POST /rest/api/3/search/jql may omit navigable fields unless requested; issuetype is required for filtering.
        requestBody.put("fields", jiraSearchFieldNames());
        JsonNode root;
        try {
            root = jiraRestClient
                    .post()
                    .uri("/rest/api/3/search/jql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            long ms = System.currentTimeMillis() - t0;
            int sc = e.getStatusCode().value();
            log.error(
                    "FALLBACK TRIGGERED: Jira HTTP error (mock disabled). httpStatus={} durationMs={} bodySnippet={}",
                    sc,
                    ms,
                    safeJiraMessage(e.getResponseBodyAsString()));
            throw new JiraIntegrationException(
                    "Jira API error HTTP " + sc + ": " + safeJiraMessage(e.getResponseBodyAsString()), sc, e);
        } catch (RestClientException e) {
            long ms = System.currentTimeMillis() - t0;
            log.error(
                    "FALLBACK TRIGGERED: Jira request failed (mock disabled). durationMs={} message={}",
                    ms,
                    safeJiraMessage(e.getMessage()));
            throw new JiraIntegrationException("Jira API request failed: " + safeJiraMessage(e.getMessage()), null, e);
        }
        long ms = System.currentTimeMillis() - t0;

        if (root == null || !root.has("issues")) {
            log.error(
                    "FALLBACK TRIGGERED: Jira unexpected JSON body, missing issues key (mock disabled). durationMs={} url={}",
                    ms,
                    requestSummaryForLog);
            throw new JiraIntegrationException("Jira search returned unexpected body (missing issues).", 200);
        }
        JsonNode issues = root.get("issues");
        if (!issues.isArray()) {
            log.error(
                    "FALLBACK TRIGGERED: Jira issues field is not an array (mock disabled). durationMs={}",
                    ms);
            throw new JiraIntegrationException("Jira search returned invalid issues payload.", 200);
        }
        if (issues.isEmpty()) {
            log.warn("Jira returned 0 issues for JQL");
            log.error(
                    "FALLBACK TRIGGERED: Jira returned zero issues (mock disabled). request={}",
                    requestSummaryForLog);
            throw new JiraIntegrationException(
                    "Jira returned 0 issues for JQL. Check jira.jql / jira.project. Request was: "
                            + requestSummaryForLog,
                    200);
        }

        log.info("Jira API search completed issueCount={} durationMs={}", issues.size(), ms);
        if (log.isDebugEnabled()) {
            String snippet = root.toString();
            int cap = 500;
            if (snippet.length() > cap) {
                snippet = snippet.substring(0, cap) + "...";
            }
            log.debug("Jira search response (trimmed) chars={} snippet={}", root.toString().length(), snippet);
        }
        return root;
    }

    /** Log/debug line for JQL search (POST /rest/api/3/search/jql; see Atlassian changelog CHANGE-2046). */
    private String buildSearchRequestSummary(String jql, int max) {
        String base = trimTrailingSlash(jiraProperties.resolvedBaseUrl());
        return base + "/rest/api/3/search/jql (POST, maxResults=" + max + ", expand=changelog)";
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static int clampMax(int max) {
        return Math.max(1, Math.min(max, 100));
    }

    /** Field ids for Jira search/jql POST body — must include {@code issuetype} for client-side type filtering. */
    private static List<String> jiraSearchFieldNames() {
        return List.of("summary", "status", "issuetype", "created", "updated", "assignee");
    }

    private static String issueTypeNameFromJson(JsonNode issue) {
        JsonNode name = issue.path("fields").path("issuetype").path("name");
        if (name != null && name.isTextual() && !name.asText().isBlank()) {
            return name.asText().trim();
        }
        return "";
    }

    /**
     * Drops issues whose {@code issuetype.name} is not in {@link JiraProperties#resolvedAllowedIssueTypeNames()}
     * (case-insensitive), so Sub-tasks never reach the dashboard even if JQL is misconfigured.
     * If Jira omits {@code issuetype.name} but {@code jqlUsedForSearch} already contains an {@code issuetype}
     * constraint, issues are kept (Jira enforced types server-side).
     */
    private List<JsonNode> filterIssuesByAllowedTypes(JsonNode issues, String jqlUsedForSearch) {
        if (issues == null || !issues.isArray()) {
            return List.of();
        }
        List<String> allowed = jiraProperties.resolvedAllowedIssueTypeNames();
        boolean jqlHasIssueType =
                jqlUsedForSearch != null && jqlUsedForSearch.toLowerCase().contains("issuetype");
        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode issue : issues) {
            String typeName = issueTypeNameFromJson(issue);
            if (typeName.isEmpty() && jqlHasIssueType) {
                log.warn(
                        "Jira issue {} has no fields.issuetype.name in search response; keeping because JQL constrains issuetype",
                        issue.path("key").asText(""));
                kept.add(issue);
                continue;
            }
            boolean match = allowed.stream().anyMatch(t -> t.equalsIgnoreCase(typeName));
            if (match) {
                kept.add(issue);
            } else if (!typeName.isEmpty()) {
                log.debug("Filtered out Jira issue key={} issuetype={}", issue.path("key").asText(""), typeName);
            }
        }
        return kept;
    }

    private static String safeJiraMessage(String m) {
        if (m == null) {
            return "";
        }
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }

    private Ticket mapIssue(JsonNode issue) {
        String key = issue.path("key").asText("");
        JsonNode fields = issue.path("fields");
        String summary = fields.path("summary").asText("").strip();
        String statusName = fields.path("status").path("name").asText("Unknown");
        String statusCategory = fields.path("status").path("statusCategory").path("name").asText("");
        Instant createdAt = JiraTime.parse(nodeText(fields, "created")).orElse(null);
        Instant jiraUpdatedAt = JiraTime.parse(nodeText(fields, "updated")).orElse(null);
        JsonNode assigneeNode = fields.get("assignee");
        String assigneeName = "Unassigned";
        if (assigneeNode != null && !assigneeNode.isNull()) {
            JsonNode displayName = assigneeNode.get("displayName");
            if (displayName != null && !displayName.isNull() && !displayName.asText().isBlank()) {
                assigneeName = displayName.asText();
            }
        }
        JsonNode changelog = issue.path("changelog");
        if (!changelog.has("histories") || changelog.get("histories").isEmpty()) {
            changelog = fetchChangelogForIssue(key);
        }
        int statusChanges = countStatusChanges(changelog);
        int pingPong = countPingPongTransitions(changelog);
        Instant now = Instant.now();
        Instant enteredCurrent = lastEnteredStatusAt(changelog, statusName, fields);
        if (createdAt != null && enteredCurrent.isBefore(createdAt)) {
            enteredCurrent = createdAt;
        }
        long rawHours = ChronoUnit.HOURS.between(enteredCurrent, now);
        if (rawHours < 0) {
            rawHours = 0;
        }
        if (rawHours > DeliveryViewEnricher.MAX_HOURS_IN_STATUS) {
            log.warn(
                    "Capping time-in-status for key={} (was {}h, cap {}h); check changelog/created dates",
                    key,
                    rawHours,
                    DeliveryViewEnricher.MAX_HOURS_IN_STATUS);
        }
        int hoursInState = DeliveryViewEnricher.capHours((int) rawHours);
        Ticket ticket = Ticket.builder()
                .id(key)
                .summary(summary.isEmpty() ? "(no summary)" : summary)
                .status(statusName)
                .statusCategory(statusCategory)
                .createdAt(createdAt)
                .jiraUpdatedAt(jiraUpdatedAt != null ? jiraUpdatedAt : createdAt)
                .assignee(assigneeName)
                .timeInState(hoursInState)
                .prTime(0)
                .statusChanges(statusChanges)
                .pingPongTransitions(pingPong)
                .flags(new ArrayList<>())
                .jiraDataAvailable(true)
                .prDataAvailable(false)
                .build();
        log.info(
                "Mapped ticket: id={} status={} assignee={}",
                ticket.getId(),
                ticket.getStatus(),
                ticket.getAssignee());
        return ticket;
    }

    private static String nodeText(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        return n.asText();
    }

    private JsonNode fetchChangelogForIssue(String key) {
        if (key.isEmpty()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            JsonNode issue = jiraRestClient
                    .get()
                    .uri("/rest/api/3/issue/{key}?expand=changelog", key)
                    .retrieve()
                    .body(JsonNode.class);
            if (issue != null && issue.has("changelog")) {
                return issue.get("changelog");
            }
        } catch (RestClientException e) {
            log.warn("Could not load changelog for {}: {}", key, e.getMessage());
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static int countStatusChanges(JsonNode changelog) {
        JsonNode histories = changelog.path("histories");
        if (!histories.isArray()) {
            return 0;
        }
        int n = 0;
        for (JsonNode history : histories) {
            for (JsonNode item : history.path("items")) {
                if (isStatusItem(item)) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Counts A→B followed by B→A reversals (same two statuses), ignoring unrelated transitions.
     */
    private static int countPingPongTransitions(JsonNode changelog) {
        List<StatusEdge> edges = extractOrderedStatusEdges(changelog);
        if (edges.size() < 2) {
            return 0;
        }
        int pings = 0;
        for (int i = 1; i < edges.size(); i++) {
            StatusEdge prev = edges.get(i - 1);
            StatusEdge cur = edges.get(i);
            if (prev.from().equals(cur.to()) && prev.to().equals(cur.from())) {
                pings++;
            }
        }
        return pings;
    }

    private record StatusEdge(String from, String to) {}

    /** Chronological status transitions from Jira changelog (one edge per status item). */
    private static List<StatusEdge> extractOrderedStatusEdges(JsonNode changelog) {
        JsonNode histories = changelog.path("histories");
        if (!histories.isArray()) {
            return List.of();
        }
        List<JsonNode> sorted = new ArrayList<>();
        histories.forEach(sorted::add);
        sorted.sort(Comparator.comparing(h -> jiraTime(h.path("created")).orElse(Instant.EPOCH)));
        List<StatusEdge> out = new ArrayList<>();
        for (JsonNode history : sorted) {
            for (JsonNode item : history.path("items")) {
                if (!isStatusItem(item)) {
                    continue;
                }
                String from = item.path("fromString").asText("");
                String to = item.path("toString").asText("");
                out.add(new StatusEdge(from, to));
            }
        }
        return out;
    }

    private static boolean isStatusItem(JsonNode item) {
        String field = item.path("field").asText("");
        String fieldId = item.path("fieldId").asText("");
        return "status".equalsIgnoreCase(field) || "status".equalsIgnoreCase(fieldId);
    }

    /**
     * Latest time the issue transitioned into {@code currentStatusName} (from changelog).
     */
    private static Instant lastEnteredStatusAt(JsonNode changelog, String currentStatusName, JsonNode fields) {
        JsonNode histories = changelog.path("histories");
        if (!histories.isArray() || histories.isEmpty()) {
            return JiraTime.parse(nodeText(fields, "updated"))
                    .or(() -> JiraTime.parse(nodeText(fields, "created")))
                    .orElseGet(() -> Instant.now().minus(1, ChronoUnit.HOURS));
        }
        List<JsonNode> sorted = new ArrayList<>();
        histories.forEach(sorted::add);
        sorted.sort(Comparator.comparing(
                h -> jiraTime(h.path("created")).orElse(Instant.EPOCH)));

        Instant lastMatch = null;
        for (JsonNode history : sorted) {
            for (JsonNode item : history.path("items")) {
                if (!isStatusItem(item)) {
                    continue;
                }
                String to = item.path("toString").asText("");
                if (to.equals(currentStatusName)) {
                    lastMatch = jiraTime(history.path("created")).orElse(lastMatch);
                }
            }
        }
        if (lastMatch != null) {
            return lastMatch;
        }
        return JiraTime.parse(nodeText(fields, "updated"))
                .or(() -> JiraTime.parse(nodeText(fields, "created")))
                .orElseGet(() -> Instant.now().minus(1, ChronoUnit.HOURS));
    }

    private static Optional<Instant> jiraTime(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return Optional.empty();
        }
        return JiraTime.parse(node.asText());
    }
}
