package com.aipmo.agent.config;

import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.TimedCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class CacheConfig {

    @Bean
    public TimedCache<List<Ticket>> mergedTicketListCache() {
        return new TimedCache<>();
    }
}
