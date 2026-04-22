package com.aipmo.agent.dto;

import java.util.Map;

/**
 * GitHub closed-PR scan outcome: key→hours map plus whether PR cycle data is usable for this run
 * (config ok, scan succeeded, at least one ticket key received hours).
 */
public record GitHubPrLoadResult(Map<String, Integer> ticketKeyToPrHours, boolean prDataAvailable) {}
