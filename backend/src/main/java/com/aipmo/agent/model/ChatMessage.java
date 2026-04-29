package com.aipmo.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One turn in a chat thread (stored in memory for Phase 1). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private String sessionId;
    /** {@code user} or {@code assistant} */
    private String role;
    private String content;
    private LocalDateTime timestamp;
}
