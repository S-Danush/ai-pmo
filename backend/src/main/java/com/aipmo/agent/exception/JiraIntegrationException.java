package com.aipmo.agent.exception;

/** Thrown when Jira must be used but the API response is unusable or empty (non-simulation flows). */
public final class JiraIntegrationException extends RuntimeException {

    private final Integer httpStatus;

    public JiraIntegrationException(String message) {
        this(message, null, null);
    }

    public JiraIntegrationException(String message, Integer httpStatus) {
        this(message, httpStatus, null);
    }

    public JiraIntegrationException(String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
