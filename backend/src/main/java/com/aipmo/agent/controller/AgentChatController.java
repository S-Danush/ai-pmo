package com.aipmo.agent.controller;

import com.aipmo.agent.dto.AgentChatRequestDto;
import com.aipmo.agent.dto.AgentChatResponseDto;
import com.aipmo.agent.service.AgentChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent-chat")
public class AgentChatController {

    private final AgentChatService agentChatService;

    public AgentChatController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    @PostMapping
    public AgentChatResponseDto chat(@RequestBody AgentChatRequestDto body) {
        String q = body != null ? body.getQuery() : null;
        String sid = body != null ? body.getSessionId() : null;
        return agentChatService.processUserQuery(q, sid);
    }
}
