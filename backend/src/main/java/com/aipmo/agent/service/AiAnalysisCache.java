package com.aipmo.agent.service;

import com.aipmo.agent.dto.TicketInsightPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AiAnalysisCache {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisCache.class);

    private final Duration ttl;
    private final ConcurrentHashMap<String, CachedEntry> map = new ConcurrentHashMap<>();

    public AiAnalysisCache(@Value("${ai.cache.ttl.minutes:30}") long ttlMinutes) {
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
    }

    public record CachedEntry(TicketInsightPayload insight, Instant storedAt) {}

    public String buildKey(
            String ticketId,
            java.util.List<String> flags,
            String severity,
            int timeInState,
            int prTime,
            String trendIndicator,
            int pingPongTransitions) {
        String flagPart = flags == null ? "" : String.join(",", flags);
        return String.join(
                "|",
                Objects.toString(ticketId, ""),
                flagPart,
                Objects.toString(severity, ""),
                Integer.toString(timeInState),
                Integer.toString(prTime),
                Objects.toString(trendIndicator, ""),
                Integer.toString(pingPongTransitions));
    }

    public CachedEntry getIfFresh(String key) {
        CachedEntry e = map.get(key);
        if (e == null) {
            log.debug("AI cache miss key={}", key);
            return null;
        }
        if (e.storedAt().plus(ttl).isBefore(Instant.now())) {
            map.remove(key, e);
            log.debug("AI cache expired key={}", key);
            return null;
        }
        log.info("AI cache hit key={}", key);
        return e;
    }

    public void put(String key, TicketInsightPayload insight) {
        map.put(key, new CachedEntry(insight, Instant.now()));
    }
}
