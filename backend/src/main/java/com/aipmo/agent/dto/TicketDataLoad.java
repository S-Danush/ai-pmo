package com.aipmo.agent.dto;

import com.aipmo.agent.model.Ticket;

import java.util.List;

/** Result of loading tickets for analysis, including provenance for audit logs. */
public record TicketDataLoad(
        List<Ticket> tickets,
        TicketDataPath path,
        boolean jiraDataAvailable,
        boolean prDataAvailable,
        DataQuality dataQuality) {}
