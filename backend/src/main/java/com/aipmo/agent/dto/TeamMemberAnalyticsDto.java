package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamMemberAnalyticsDto {

    private String name;
    /** Role / seniority for LOS/LMS delivery teams (simulation). */
    private String experience;
    /** Stable hue for dummy avatar (0–360). */
    private int avatarHue;
    private int totalTicketsAssigned;
    private int completedTickets;
    private int inProgress;
    private int underReview;
    private int blocked;
    /** HEALTHY | MODERATE | AT_RISK */
    private String performanceLevel;
    private String performanceLabel;
    private String aiInsight;
}
