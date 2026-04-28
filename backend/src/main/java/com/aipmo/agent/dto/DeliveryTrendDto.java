package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Run-over-run delivery metrics for dashboard trend strip (from {@link com.aipmo.agent.service.RunMetricsHistory}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryTrendDto {

    @Builder.Default
    private List<DeliveryTrendPointDto> snapshots = new ArrayList<>();
}
