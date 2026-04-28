package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Proactive UI hint for tickets that merit manager attention (no auto-send). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketSuggestionDto(
        @JsonProperty("ticketId") String ticketId,
        @JsonProperty("reason") String reason,
        @JsonProperty("recommendedAction") String recommendedAction) {}
