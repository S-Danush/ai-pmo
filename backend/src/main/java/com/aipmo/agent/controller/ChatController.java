package com.aipmo.agent.controller;

import com.aipmo.agent.dto.AgentChatResponseDto;
import com.aipmo.agent.dto.ChatHistoryResponseDto;
import com.aipmo.agent.dto.ChatMessageViewDto;
import com.aipmo.agent.dto.ChatSendMessageRequestDto;
import com.aipmo.agent.dto.ChatSessionSummaryDto;
import com.aipmo.agent.dto.NewChatSessionResponseDto;
import com.aipmo.agent.model.ChatSession;
import com.aipmo.agent.service.AgentChatService;
import com.aipmo.agent.service.ChatSessionStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatSessionStore chatSessionStore;
    private final AgentChatService agentChatService;

    public ChatController(ChatSessionStore chatSessionStore, AgentChatService agentChatService) {
        this.chatSessionStore = chatSessionStore;
        this.agentChatService = agentChatService;
    }

    @PostMapping("/session")
    public NewChatSessionResponseDto startSession() {
        ChatSession s = chatSessionStore.createSession();
        return NewChatSessionResponseDto.builder().sessionId(s.getSessionId()).build();
    }

    @GetMapping("/sessions")
    public List<ChatSessionSummaryDto> listSessions() {
        return chatSessionStore.listSessionsNewestFirst().stream()
                .map(
                        s ->
                                ChatSessionSummaryDto.builder()
                                        .sessionId(s.getSessionId())
                                        .title(s.getTitle())
                                        .createdAt(s.getCreatedAt())
                                        .updatedAt(s.getUpdatedAt())
                                        .build())
                .collect(Collectors.toList());
    }

    @GetMapping("/{sessionId}")
    public ChatHistoryResponseDto getHistory(@PathVariable String sessionId) {
        requireSession(sessionId);
        ChatSession meta = chatSessionStore.getSession(sessionId);
        List<ChatMessageViewDto> msgs =
                chatSessionStore.getMessagesOrdered(sessionId).stream()
                        .map(
                                m ->
                                        ChatMessageViewDto.builder()
                                                .role(m.getRole())
                                                .content(m.getContent())
                                                .timestamp(m.getTimestamp())
                                                .build())
                        .collect(Collectors.toList());
        return ChatHistoryResponseDto.builder()
                .sessionId(meta.getSessionId())
                .title(meta.getTitle())
                .messages(msgs)
                .build();
    }

    @PostMapping("/{sessionId}")
    public AgentChatResponseDto sendMessage(
            @PathVariable String sessionId, @RequestBody(required = false) ChatSendMessageRequestDto body) {
        requireSession(sessionId);
        String msg = body != null ? body.getMessage() : null;
        if (msg == null || msg.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        return agentChatService.sendPersistedChatMessage(sessionId, msg.trim());
    }

    private void requireSession(String sessionId) {
        if (!chatSessionStore.sessionExists(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown session");
        }
    }
}
