package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManualNotifyResponse(
        @JsonProperty("status") String status,
        @JsonProperty("ticketId") String ticketId,
        @JsonProperty("assignee") String assignee,
        @JsonProperty("messagePreview") String messagePreview,
        @JsonProperty("lastNotifiedAt") Instant lastNotifiedAt,
        /** Present when {@code status} is {@code skipped} (e.g. cooldown). */
        @JsonProperty("reason") String reason) {}
