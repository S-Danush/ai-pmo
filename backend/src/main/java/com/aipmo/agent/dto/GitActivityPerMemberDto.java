package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitActivityPerMemberDto {

    private String assigneeName;
    private double commitsPerDay;
    private double commitsPerWeek;
    private double commitsPerMonth;
    private int prsCreated;
    private int prsMerged;
    /** Mean synthetic review delay in hours (0 when no PR samples). */
    private double avgPrReviewTimeHours;
    private int totalCommits;
}
