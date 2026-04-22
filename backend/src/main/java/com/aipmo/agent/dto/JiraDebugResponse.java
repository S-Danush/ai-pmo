package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Response for {@code GET /api/debug/jira} — connectivity and mapping smoke test. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JiraDebugResponse(
        boolean configured,
        String missingConfiguration,
        String requestUrl,
        String jqlUsed,
        Integer httpStatus,
        Long totalFromJira,
        Integer issuesReturnedInPage,
        String firstIssueKey,
        String firstIssueStatusRaw,
        String firstIssueAssignee,
        String sampleMappedTicketSummary,
        String error) {}
