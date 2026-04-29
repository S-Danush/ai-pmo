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
public class ChatHistoryResponseDto {

    private String sessionId;
    private String title;

    @Builder.Default
    private List<ChatMessageViewDto> messages = new ArrayList<>();
}
