package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommitsTimeSeriesPointDto {

    /** ISO date (yyyy-MM-dd). */
    private String date;
    private int commits;
}
