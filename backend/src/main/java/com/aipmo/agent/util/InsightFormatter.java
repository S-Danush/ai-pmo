package com.aipmo.agent.util;

import com.aipmo.agent.dto.TicketInsightPayload;

/** Formats structured insight fields for API and notifications. */
public final class InsightFormatter {

    private InsightFormatter() {}

    public static String formatInsightText(TicketInsightPayload p) {
        String c = p.getConfidence() != null && !p.getConfidence().isBlank() ? p.getConfidence() : "MEDIUM";
        String reasoningBlock =
                p.getReasoning() != null && !p.getReasoning().isBlank()
                        ? "Reading of the signals:\n" + p.getReasoning().trim() + "\n\n"
                        : "";
        return reasoningBlock
                + "What is wrong:\n"
                + nullToDash(p.getRootCause())
                + "\n\nWhy it matters:\n"
                + nullToDash(p.getImpact())
                + "\n\nWhat to do next:\n"
                + nullToDash(p.getRecommendedAction())
                + "\n\nConfidence:\n"
                + c;
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
