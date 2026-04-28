package com.aipmo.agent.dto;

/** Result of Teams incoming webhook validation / probe. */
public record TeamsIntegrationHealth(boolean success, long durationMs, String reason, boolean webhookReachable) {

    public static TeamsIntegrationHealth ok(long durationMs) {
        return new TeamsIntegrationHealth(true, durationMs, null, true);
    }

    public static TeamsIntegrationHealth fail(long durationMs, String reason, boolean webhookReachable) {
        return new TeamsIntegrationHealth(false, durationMs, reason, webhookReachable);
    }
}
