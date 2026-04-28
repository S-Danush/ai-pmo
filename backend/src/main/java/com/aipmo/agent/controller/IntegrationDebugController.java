package com.aipmo.agent.controller;

import com.aipmo.agent.dto.IntegrationHealthApiResponse;
import com.aipmo.agent.startup.IntegrationHealthChecker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/debug")
public class IntegrationDebugController {

    private final IntegrationHealthChecker integrationHealthChecker;

    public IntegrationDebugController(IntegrationHealthChecker integrationHealthChecker) {
        this.integrationHealthChecker = integrationHealthChecker;
    }

    @GetMapping("/integrations")
    public IntegrationHealthApiResponse integrations() {
        return integrationHealthChecker.getLastSnapshot();
    }
}
