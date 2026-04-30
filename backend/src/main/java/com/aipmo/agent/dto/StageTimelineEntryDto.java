package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One row in a ticket’s SDLC timeline (delivery cards). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StageTimelineEntryDto {

    @JsonProperty("stage")
    private String stage;

    @JsonProperty("hours")
    private int hours;
}
