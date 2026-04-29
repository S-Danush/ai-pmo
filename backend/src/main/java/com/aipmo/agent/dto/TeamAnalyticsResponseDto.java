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
public class TeamAnalyticsResponseDto {

    private TeamAnalyticsOverviewDto overview;

    @Builder.Default
    private List<TeamMemberAnalyticsDto> members = new ArrayList<>();

    @Builder.Default
    private List<GitActivityPerMemberDto> gitActivityByMember = new ArrayList<>();

    @Builder.Default
    private List<CommitsTimeSeriesPointDto> commitsOverTime = new ArrayList<>();

    @Builder.Default
    private List<WorkloadBarDto> workloadByAssignee = new ArrayList<>();

    private String generatedAt;
}
