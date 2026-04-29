package com.aipmo.agent.controller;

import com.aipmo.agent.dto.TeamAnalyticsResponseDto;
import com.aipmo.agent.service.TeamAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/team-analytics")
public class TeamAnalyticsController {

    private final TeamAnalyticsService teamAnalyticsService;

    public TeamAnalyticsController(TeamAnalyticsService teamAnalyticsService) {
        this.teamAnalyticsService = teamAnalyticsService;
    }

    @GetMapping
    public TeamAnalyticsResponseDto getTeamAnalytics() {
        return teamAnalyticsService.buildCurrent();
    }
}
