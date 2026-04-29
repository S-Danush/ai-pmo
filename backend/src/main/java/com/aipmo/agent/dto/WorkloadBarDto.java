package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkloadBarDto {

    private String assigneeName;
    /** Active (non-done) tickets for this assignee. */
    private int activeTicketCount;
    /** NONE | OVERLOADED | UNDERUTILIZED */
    private String highlight;
}
