package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatResponseDto {

    private String message;

    @Builder.Default
    private List<String> bullets = new ArrayList<>();

    @Builder.Default
    private List<String> referencedTicketIds = new ArrayList<>();

    private String sessionId;

    /**
     * Where the reply came from: {@code GROQ}, {@code LOCAL_HANDLER}, {@code LOCAL_RULE}, {@code SMALLTALK},
     * {@code CAPABILITIES}, {@code PROMPT} (empty input suggestions only).
     */
    private String answerSource;
}
