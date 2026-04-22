package com.aipmo.agent.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** Parses Jira REST date-time strings, including offsets without a colon (e.g. +0000). */
public final class JiraTime {

    private JiraTime() {}

    public static Optional<Instant> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.endsWith("Z")) {
            try {
                return Optional.of(Instant.parse(s));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
        // Jira: ...+0000 or -0500 without colon
        if (s.length() >= 5) {
            String tail = s.substring(s.length() - 5);
            if (tail.length() == 5
                    && (tail.charAt(0) == '+' || tail.charAt(0) == '-')
                    && tail.substring(1).chars().allMatch(Character::isDigit)) {
                s = s.substring(0, s.length() - 5) + tail.substring(0, 3) + ":" + tail.substring(3);
            }
        }
        try {
            return Optional.of(OffsetDateTime.parse(s).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(Instant.parse(s));
            } catch (DateTimeParseException e2) {
                return Optional.empty();
            }
        }
    }

    public static int hoursSince(Instant past, Instant now) {
        if (past == null) {
            return 0;
        }
        if (!past.isBefore(now)) {
            return 0;
        }
        return (int) Math.max(0, ChronoUnit.HOURS.between(past, now));
    }
}
