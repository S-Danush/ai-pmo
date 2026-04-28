package com.aipmo.agent.util;

import java.util.regex.Pattern;

/**
 * Strips internal tokens, very large time figures, and repeated “AI unavailable” phrasing for
 * user-facing surfaces.
 */
public final class FriendlyText {

    private static final Pattern hugeHourPattern =
            Pattern.compile("\\b\\d{3,}\\s*(h(ours?)?|hrs?)(\\b|\\s*in\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern anyLargeIntHours =
            Pattern.compile("\\b\\d{2,5}\\b\\s*hours? in", Pattern.CASE_INSENSITIVE);

    private FriendlyText() {}

    public static boolean looksLikeMetricFallback(String rootCause) {
        if (rootCause == null) {
            return true;
        }
        String c = rootCause.toLowerCase();
        return c.contains("ai temporarily unavailable")
                || c.contains("openai api key not configured")
                || c.contains("groq api key not configured")
                || c.contains("ssl/tls")
                || c.contains("jvm truststore");
    }

    /**
     * Removes substrings that read as internal diagnostics; keeps the rest readable. Safe for
     * null/blank.
     */
    public static String sanitizeForReader(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        String t = s;
        t = t.replaceAll("(?i)AI temporarily unavailable[^.!?\n]*[.!]?", " ");
        t = t.replaceAll("(?i)OpenAI API key not configured[^.!?\n]*[.!]?", " ");
        t = t.replaceAll("(?i)Groq API key not configured[^.!?\n]*[.!]?", " ");
        t = t.replaceAll("(?i)AI temporarily unavailable:\\s*SSL/[^.!?\n]*[.!]?", " ");
        t = t.replaceAll("\\bPR_DATA_MISSING\\b", "limited PR data");
        t = t.replaceAll("\\bSTUCK\\b", "at risk (slow)");
        t = t.replaceAll("\\bCRITICAL_STUCK\\b", "at high risk (long dwell)");
        t = t.replaceAll("\\bDATA_INSUFFICIENT\\b", "limited team comparison");
        t = t.replaceAll("\\bTREND_SPIKE\\b", "higher than peers");
        t = t.replaceAll("\\bBOUNCING\\b", "repeated back-and-forth in status");
        t = t.replaceAll("\\bPR_DELAY\\b", "slow review cycle");
        t = t.replaceAll("\\bDEPENDENCY_RISK\\b", "long wait with an external dependency");
        t = t.replaceAll("\\bSLOWDOWN\\b", "slower merge time");
        t = hugeHourPattern.matcher(t).replaceAll("a long time in the current state");
        t = anyLargeIntHours.matcher(t).replaceAll("an extended time in the current");
        t = t.replaceAll("\\s{2,}", " ");
        t = t.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t;
    }
}
