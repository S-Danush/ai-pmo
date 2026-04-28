package com.aipmo.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;

/**
 * Provides JVM default TLS context. Outbound integration HTTP clients are not used in simulation-only mode.
 */
@Configuration
public class SslConfig {

    @Bean
    public SSLContext applicationSslContext() throws Exception {
        return SSLContext.getDefault();
    }
}
