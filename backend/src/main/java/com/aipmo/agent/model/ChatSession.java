package com.aipmo.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** In-memory chat thread metadata (Phase 1: not JPA-persisted). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    private String sessionId;
    /** Display title; auto-set from first user message when still default. */
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
