package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Card payload for the Delivery tab — timeline + data-driven ETA + status. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeliveryTicketCardDto {

    @JsonProperty("ticketId")
    private String ticketId;

    @JsonProperty("title")
    private String title;

    @JsonProperty("assignee")
    private String assignee;

    /** SDLC lane label e.g. DEV, REVIEW, QA, UAT, BACKLOG, DONE, BLOCKED */
    @JsonProperty("currentStage")
    private String currentStage;

    /** Historical TaT sum from simulation (hours). */
    @JsonProperty("totalHours")
    private int totalHours;

    /** Human ETA e.g. "~12 days" — from Jira/Git/stage model (no LLM). */
    @JsonProperty("estimatedCompletion")
    private String estimatedCompletion;

    /** SIMPLE | MEDIUM | COMPLEX | UNKNOWN — from ticket sizing when present. */
    @JsonProperty("taskComplexity")
    private String taskComplexity;

    /**
     * Short rationale tying Jira lane, complexity, PR age, review delay, and Git activity into one
     * line.
     */
    @JsonProperty("timelineNote")
    private String timelineNote;

    /** ON_TRACK | AT_RISK | DELAYED */
    @JsonProperty("deliveryStatus")
    private String deliveryStatus;

    @Builder.Default
    @JsonProperty("stageTimeline")
    private List<StageTimelineEntryDto> stageTimeline = new ArrayList<>();

    /** e.g. “REVIEW taking longer than portfolio average” — UX hint only */
    @JsonProperty("slowStageWarning")
    private String slowStageWarning;
}
