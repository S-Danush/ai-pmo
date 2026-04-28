package com.aipmo.agent.util;

import com.aipmo.agent.dto.AiInsightOutcome;
import com.aipmo.agent.dto.TicketInsightPayload;
import com.aipmo.agent.model.Ticket;

/**
 * Builds reader-facing insight payloads shared by the agent pipeline and manual Teams notify.
 */
public final class InsightUiFactory {

    private InsightUiFactory() {}

    /**
     * True when the live model did not produce the insight (config, transport, or cache of such a
     * result).
     */
    public static boolean isAiInsightLimited(TicketInsightPayload insight, AiInsightOutcome outcome) {
        if (outcome != null) {
            if (outcome.fromLocalHeuristicEngine()) {
                return false;
            }
            return !outcome.usedOpenAiModel();
        }
        return FriendlyText.looksLikeMetricFallback(insight.getRootCause());
    }

    /** Replaces raw model/fallback text with one human-readable payload for UI + Teams. */
    public static TicketInsightPayload buildUiInsight(Ticket ticket, TicketInsightPayload raw, boolean aiLimited) {
        if (aiLimited) {
            return TicketInsightPayload.builder()
                    .reasoning(null)
                    .rootCause(DeliveryRiskCopy.DEFAULT_ISSUE)
                    .impact(DeliveryRiskCopy.DEFAULT_IMPACT)
                    .recommendedAction(DeliveryRiskCopy.DEFAULT_ACTION)
                    .nudge(DeliveryRiskCopy.DEFAULT_SUGGESTION)
                    .confidence("—")
                    .build();
        }
        String timePhrase = TicketDisplayMapper.toTimeInStateNarrative(ticket.getTimeInState());
        String rc = stringOrNull(FriendlyText.sanitizeForReader(raw.getRootCause()));
        if (rc == null || rc.isBlank()) {
            rc = DeliveryRiskCopy.issueTiedToTiming(timePhrase);
        }
        String im = stringOrNull(FriendlyText.sanitizeForReader(raw.getImpact()));
        if (im == null || im.isBlank()) {
            im = DeliveryRiskCopy.DEFAULT_IMPACT;
        }
        String ra = stringOrNull(FriendlyText.sanitizeForReader(raw.getRecommendedAction()));
        String nud = stringOrNull(FriendlyText.sanitizeForReader(raw.getNudge()));
        if (ra == null || ra.isBlank()) {
            ra = nud != null && !nud.isBlank()
                    ? nud
                    : "Check current status, confirm the owner, and move forward or unblock.";
        }
        if (nud == null || nud.isBlank()) {
            nud = DeliveryRiskCopy.DEFAULT_SUGGESTION;
        }
        String conf = raw.getConfidence() != null && !raw.getConfidence().isBlank()
                ? raw.getConfidence()
                : "MEDIUM";
        String rs = stringOrNull(FriendlyText.sanitizeForReader(raw.getReasoning()));
        return TicketInsightPayload.builder()
                .reasoning(rs)
                .rootCause(rc)
                .impact(im)
                .recommendedAction(ra)
                .nudge(nud)
                .confidence(conf)
                .build();
    }

    private static String stringOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }
}
