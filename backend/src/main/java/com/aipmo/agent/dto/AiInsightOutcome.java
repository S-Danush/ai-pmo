package com.aipmo.agent.dto;

/**
 * @param transportFailure true when a remote AI transport would have failed (unused in simulation mode)
 * @param usedOpenAiModel retained for logs; always false when external LLM is removed
 * @param openAiAttempted false when no remote model call was made
 * @param fromLocalHeuristicEngine true when {@link com.aipmo.agent.service.LocalAIService} produced the insight
 */
public record AiInsightOutcome(
        TicketInsightPayload insight,
        boolean transportFailure,
        boolean usedOpenAiModel,
        boolean openAiAttempted,
        boolean fromLocalHeuristicEngine) {}
