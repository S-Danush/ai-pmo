package com.aipmo.agent.util;

import java.util.regex.Pattern;

/**
 * Masks common secret patterns before writing integration diagnostics to logs.
 */
public final class LogSanitizer {

    private static final Pattern OPENAI_SK = Pattern.compile("sk-(?:proj-)?[A-Za-z0-9_-]{20,}");
    private static final Pattern GROQ_KEY = Pattern.compile("gsk_[A-Za-z0-9_-]{20,}");
    private static final Pattern GITHUB_PAT = Pattern.compile("github_pat_[A-Za-z0-9_]{20,}");
    private static final Pattern JIRA_ATLASSIAN_TOKEN = Pattern.compile("ATATT3x[A-Za-z0-9_+=/-]{30,}");
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+[A-Za-z0-9._+=/-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_TOKEN_PARAM =
            Pattern.compile("([?&](?:token|key|access_token|password)=)[^&\\s]+", Pattern.CASE_INSENSITIVE);

    private LogSanitizer() {}

    public static String maskSecrets(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = raw;
        s = OPENAI_SK.matcher(s).replaceAll("sk-***MASKED***");
        s = GROQ_KEY.matcher(s).replaceAll("gsk_***MASKED***");
        s = GITHUB_PAT.matcher(s).replaceAll("github_pat_***MASKED***");
        s = JIRA_ATLASSIAN_TOKEN.matcher(s).replaceAll("ATATT***MASKED***");
        s = BEARER.matcher(s).replaceAll("Bearer ***MASKED***");
        s = GENERIC_TOKEN_PARAM.matcher(s).replaceAll("$1***MASKED***");
        return s;
    }

    public static String trimMessage(String m, int maxLen) {
        if (m == null) {
            return "";
        }
        String masked = maskSecrets(m);
        return masked.length() > maxLen ? masked.substring(0, maxLen) + "..." : masked;
    }
}
