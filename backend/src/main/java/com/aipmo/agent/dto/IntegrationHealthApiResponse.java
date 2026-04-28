package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Last integration probe outcome for debug API (SUCCESS or FAILED per system). */
public record IntegrationHealthApiResponse(
        @JsonProperty("jira") String jira,
        @JsonProperty("github") String github,
        @JsonProperty("groq") String groq,
        @JsonProperty("teams") String teams) {}
