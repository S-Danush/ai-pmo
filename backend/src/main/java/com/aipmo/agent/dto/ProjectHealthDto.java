package com.aipmo.agent.dto;

import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.DeliveryViewEnricher;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Top-of-dashboard counts for project delivery health. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectHealthDto {
    private int totalOpenTickets;
    private int highRiskCount;
    private int blockedCount;
    private int atRiskCount;
    private int healthyCount;
    private int unassignedCount;

    public static ProjectHealthDto from(List<Ticket> tickets) {
        if (tickets == null) {
            return empty();
        }
        int n = 0, hi = 0, blocked = 0, risk = 0, healthy = 0, unass = 0;
        for (Ticket t : tickets) {
            n++;
            if (DeliveryViewEnricher.RISK_HIGH.equals(t.getDeliveryRisk())) {
                hi++;
            }
            String g = t.getViewGroup();
            if (g == null) {
                continue;
            }
            switch (g) {
                case DeliveryViewEnricher.GROUP_UNASSIGNED:
                    unass++;
                    break;
                case DeliveryViewEnricher.GROUP_BLOCKED:
                    blocked++;
                    break;
                case DeliveryViewEnricher.GROUP_AT_RISK:
                    risk++;
                    break;
                case DeliveryViewEnricher.GROUP_HEALTHY:
                    healthy++;
                    break;
                default:
                    break;
            }
        }
        return ProjectHealthDto.builder()
                .totalOpenTickets(n)
                .highRiskCount(hi)
                .blockedCount(blocked)
                .atRiskCount(risk)
                .healthyCount(healthy)
                .unassignedCount(unass)
                .build();
    }

    public static ProjectHealthDto empty() {
        return ProjectHealthDto.builder()
                .totalOpenTickets(0)
                .highRiskCount(0)
                .blockedCount(0)
                .atRiskCount(0)
                .healthyCount(0)
                .unassignedCount(0)
                .build();
    }
}
