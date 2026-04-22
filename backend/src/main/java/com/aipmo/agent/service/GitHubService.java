package com.aipmo.agent.service;

import com.aipmo.agent.config.GitHubProperties;
import com.aipmo.agent.dto.GitHubPrLoadResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    /** Typical Jira issue key at start of title or branch. */
    private static final Pattern TICKET_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b");

    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 3;

    private final RestClient githubRestClient;
    private final GitHubProperties gitHubProperties;

    public GitHubService(@Qualifier("githubRestClient") RestClient githubRestClient, GitHubProperties gitHubProperties) {
        this.githubRestClient = githubRestClient;
        this.gitHubProperties = gitHubProperties;
    }

    /**
     * Maps Jira-style ticket keys to merged PR duration (hours), keeping the maximum when multiple PRs match.
     * {@link GitHubPrLoadResult#prDataAvailable()} is false when config is incomplete, the API fails, or no PR
     * hours could be linked to any ticket key.
     */
    public GitHubPrLoadResult buildTicketKeyToPrHours() {
        Map<String, Integer> map = new HashMap<>();
        if (!gitHubProperties.isComplete()) {
            log.debug("GitHub integration skipped: incomplete configuration");
            return new GitHubPrLoadResult(map, false);
        }
        String owner = gitHubProperties.owner();
        String repo = gitHubProperties.repoName();
        long t0 = System.currentTimeMillis();
        log.info("GitHub PR fetch started owner={} repo={}", owner, repo);
        int pagesFetched = 0;
        int prsProcessed = 0;
        boolean scanSucceeded = true;
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonNode arr =
                        githubRestClient
                                .get()
                                .uri(
                                        "/repos/{owner}/{repo}/pulls?state=closed&per_page={perPage}&page={page}",
                                        owner,
                                        repo,
                                        PER_PAGE,
                                        page)
                                .retrieve()
                                .body(JsonNode.class);
                if (arr == null || !arr.isArray() || arr.isEmpty()) {
                    break;
                }
                pagesFetched++;
                prsProcessed += arr.size();
                for (JsonNode pr : arr) {
                    ingestPullRequest(pr, map);
                }
                if (log.isDebugEnabled()) {
                    String raw = arr.toString();
                    int cap = 400;
                    String snippet = raw.length() <= cap ? raw : raw.substring(0, cap) + "...";
                    log.debug(
                            "GitHub PR page response page={} prArraySize={} chars={} snippet={}",
                            page,
                            arr.size(),
                            raw.length(),
                            snippet);
                }
                if (arr.size() < PER_PAGE) {
                    break;
                }
            }
        } catch (RestClientException e) {
            scanSucceeded = false;
            long ms = System.currentTimeMillis() - t0;
            log.error(
                    "GitHub API request failed durationMs={} message={}",
                    ms,
                    safeGithubMessage(e.getMessage()));
        } catch (RuntimeException e) {
            scanSucceeded = false;
            long ms = System.currentTimeMillis() - t0;
            log.error(
                    "GitHub response parsing failed durationMs={} message={}",
                    ms,
                    safeGithubMessage(e.getMessage()));
        }
        long totalMs = System.currentTimeMillis() - t0;
        boolean prDataAvailable = scanSucceeded && !map.isEmpty();
        log.info(
                "GitHub PR scan completed pagesFetched={} prsProcessed={} distinctTicketKeys={} durationMs={} prDataAvailable={}",
                pagesFetched,
                prsProcessed,
                map.size(),
                totalMs,
                prDataAvailable);
        return new GitHubPrLoadResult(map, prDataAvailable);
    }

    private void ingestPullRequest(JsonNode pr, Map<String, Integer> map) {
        JsonNode mergedNode = pr.get("merged_at");
        if (mergedNode == null || mergedNode.isNull() || !mergedNode.isTextual()) {
            return;
        }
        JsonNode createdNode = pr.get("created_at");
        if (createdNode == null || !createdNode.isTextual()) {
            return;
        }
        Instant mergedAt;
        Instant createdAt;
        try {
            mergedAt = Instant.parse(mergedNode.asText());
            createdAt = Instant.parse(createdNode.asText());
        } catch (Exception e) {
            return;
        }
        int hours = (int) Math.max(0, ChronoUnit.HOURS.between(createdAt, mergedAt));
        String key = extractTicketKey(pr.path("title").asText(""), pr.path("head").path("ref").asText(""));
        if (key == null) {
            return;
        }
        map.merge(key, hours, Math::max);
    }

    private static String extractTicketKey(String title, String headRef) {
        String key = firstTicketKey(title);
        if (key != null) {
            return key;
        }
        return firstTicketKey(headRef);
    }

    private static String safeGithubMessage(String m) {
        if (m == null) {
            return "";
        }
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }

    private static String firstTicketKey(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = TICKET_KEY.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
