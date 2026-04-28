package com.aipmo.agent.dto;

/** Result of a lightweight Jira connectivity check (startup / diagnostics). */
public record JiraIntegrationHealth(
        boolean success,
        long durationMs,
        String reason,
        String projectKey,
        String sampleIssueKey,
        String sampleStatus) {

    public static JiraIntegrationHealth ok(
            long durationMs, String projectKey, String sampleIssueKey, String sampleStatus) {
        return new JiraIntegrationHealth(true, durationMs, null, projectKey, sampleIssueKey, sampleStatus);
    }

    public static JiraIntegrationHealth fail(long durationMs, String reason, String projectKey) {
        return new JiraIntegrationHealth(false, durationMs, reason, projectKey, null, null);
    }
}
