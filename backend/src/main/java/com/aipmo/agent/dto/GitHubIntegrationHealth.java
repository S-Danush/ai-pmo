package com.aipmo.agent.dto;

/** Result of a lightweight GitHub API check. */
public record GitHubIntegrationHealth(
        boolean success, long durationMs, String reason, String repoName, int samplePrCount, boolean prDataAvailable) {

    public static GitHubIntegrationHealth ok(
            long durationMs, String repoName, int samplePrCount, boolean prDataAvailable) {
        return new GitHubIntegrationHealth(true, durationMs, null, repoName, samplePrCount, prDataAvailable);
    }

    public static GitHubIntegrationHealth fail(long durationMs, String reason, String repoName) {
        return new GitHubIntegrationHealth(false, durationMs, reason, repoName, 0, false);
    }
}
