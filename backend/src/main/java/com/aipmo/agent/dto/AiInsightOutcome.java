package com.aipmo.agent.dto;

/**
 * @param transportFailure true when the HTTP layer failed after retries (timeouts, connection, HTTP 5xx, etc.)
 * @param usedOpenAiModel true only when a valid structured JSON response was parsed from a live OpenAI call
 * @param openAiAttempted false when the API key was missing and no request was sent
 */
public record AiInsightOutcome(
        TicketInsightPayload insight,
        boolean transportFailure,
        boolean usedOpenAiModel,
        boolean openAiAttempted) {}
