package com.aipmo.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TicketInsightPayload {

    /**
     * Cross-signal narrative (priority, dwell, PR, dependency, flags, churn) before the categorized
     * root-cause line.
     */
    private String reasoning;

    private String rootCause;
    private String impact;
    private String recommendedAction;
    /** Short Teams-ready line(s); produced in the same model call as structured fields. */
    private String nudge;
    /** LOW, MEDIUM, HIGH — model self-assessment */
    private String confidence;
}
