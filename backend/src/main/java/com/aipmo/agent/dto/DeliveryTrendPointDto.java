package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One completed agent-run snapshot for delivery trend UI. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryTrendPointDto {

    /** ISO-8601 instant when the run completed. */
    private String recordedAt;

    private double avgPrHours;
    private double avgDwellHours;
    private int ticketCount;
}
