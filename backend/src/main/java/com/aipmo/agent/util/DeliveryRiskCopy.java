package com.aipmo.agent.util;

/**
 * Default, human-friendly copy when AI is unavailable or as building blocks for Teams alerts.
 */
public final class DeliveryRiskCopy {

    public static final String AI_LIMITED_NOTE = "Insight temporarily unavailable";

    public static final String DEFAULT_ISSUE =
            "This issue has not been updated in a while and may be stuck.";

    public static final String DEFAULT_IMPACT = "It could delay related work if not addressed.";

    public static final String DEFAULT_ACTION =
            "• Check current status\n" + "• Confirm owner\n" + "• Move forward or unblock";

    public static final String DEFAULT_SUGGESTION = "Have a quick 15–20 min check with the owner today.";

    /** Short list for Teams and fallbacks. */
    public static final String SIMPLE_SUGGESTED_ACTION =
            "• Check with assignee\n" + "• Confirm blocker\n" + "• Move to next status";

    private DeliveryRiskCopy() {}

    public static String issueTiedToTiming(String timeNarrativePhrase) {
        if (timeNarrativePhrase == null || timeNarrativePhrase.isBlank()) {
            return DEFAULT_ISSUE;
        }
        if ("on track for now".equalsIgnoreCase(timeNarrativePhrase.trim())) {
            return "This work item still stood out in this batch and deserves a check-in.";
        }
        return "This item shows " + timeNarrativePhrase + " in its current state, which can signal risk.";
    }
}
