package com.aipmo.agent.exception;

/** Thrown when {@code ai.fail-fast=true} and a live OpenAI insight could not be produced. */
public class AiInsightGenerationException extends RuntimeException {

    public AiInsightGenerationException(String message) {
        super(message);
    }

    public AiInsightGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
