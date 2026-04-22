package com.aipmo.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "integration")
public class IntegrationProperties {

    private Cache cache = new Cache();

    @Data
    public static class Cache {
        private Ttl ttl = new Ttl();
    }

    @Data
    public static class Ttl {
        /** In-memory TTL for merged ticket list cache (seconds). */
        private int seconds = 120;
    }

    public int getCacheTtlSeconds() {
        if (cache == null || cache.ttl == null) {
            return 120;
        }
        return cache.ttl.seconds;
    }
}
