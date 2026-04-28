package com.aipmo.agent.dto;

/** Result of a minimal Groq (OpenAI-compatible) chat.completions probe. */
public record OpenAiIntegrationHealth(
        boolean success, long durationMs, String reason, String model, boolean responseReceived) {

    public static OpenAiIntegrationHealth ok(long durationMs, String model) {
        return new OpenAiIntegrationHealth(true, durationMs, null, model, true);
    }

    public static OpenAiIntegrationHealth fail(long durationMs, String reason, String model) {
        return new OpenAiIntegrationHealth(false, durationMs, reason, model, false);
    }
}
