package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ProjectSummaryBundle {

    private ProjectSummaryDto summary;
    private List<DeliveryTicketCardDto> deliveryCards;
}
