package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamAnalyticsOverviewDto {

    private int totalTeamMembers;
    private int totalActiveTickets;
    private int completedTickets;
    private int ticketsUnderReview;
    /** Active (non-done) tickets with an assignee, divided by team size. */
    private double avgTicketsPerDeveloper;
}
