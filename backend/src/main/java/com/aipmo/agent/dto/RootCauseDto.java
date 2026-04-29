package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Structured multi-signal root-cause read for a ticket (demo / PMO intelligence layer). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RootCauseDto {

    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    private String primaryCause;
    /** One of LOW, MEDIUM, HIGH. */
    private String confidence;
}
