package com.aipmo.agent.controller;

import com.aipmo.agent.dto.JiraDebugResponse;
import com.aipmo.agent.service.JiraService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/debug")
public class JiraDebugController {

    private final JiraService jiraService;

    public JiraDebugController(JiraService jiraService) {
        this.jiraService = jiraService;
    }

    @GetMapping("/jira")
    public JiraDebugResponse jira() {
        return jiraService.probeIntegration();
    }
}
