package com.aipmo.agent.util;

import com.aipmo.agent.model.Ticket;

import java.util.Comparator;
import java.util.List;

/** Default manager view: highest delivery risk and longest dwell first. */
public final class DashboardSort {

    private DashboardSort() {}

    public static void sortForManager(List<Ticket> tickets) {
        if (tickets == null) {
            return;
        }
        tickets.sort(Comparator
                .comparingInt(DashboardSort::deliveryRiskOrder)
                .thenComparing(t -> t.getViewGroup() != null ? groupOrder(t.getViewGroup()) : 9)
                .thenComparing(Comparator.comparingInt(Ticket::getTimeInState).reversed()));
    }

    private static int groupOrder(String g) {
        return switch (g) {
            case DeliveryViewEnricher.GROUP_UNASSIGNED -> 0;
            case DeliveryViewEnricher.GROUP_BLOCKED -> 1;
            case DeliveryViewEnricher.GROUP_AT_RISK -> 2;
            case DeliveryViewEnricher.GROUP_HEALTHY -> 3;
            default -> 4;
        };
    }

    private static int deliveryRiskOrder(Ticket t) {
        String r = t.getDeliveryRisk();
        if (r == null) {
            return 2;
        }
        return switch (r) {
            case DeliveryViewEnricher.RISK_HIGH -> 0;
            case DeliveryViewEnricher.RISK_MEDIUM -> 1;
            case DeliveryViewEnricher.RISK_LOW -> 2;
            default -> 2;
        };
    }
}
