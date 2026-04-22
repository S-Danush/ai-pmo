package com.aipmo.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.URL;

/**
 * Optional TLS handshake check to api.openai.com using the same {@link SSLContext} as HTTP clients
 * (including custom truststore). Logs diagnostics only; does not fail application startup.
 */
@Component
public class SslStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SslStartupValidator.class);

    private final SSLContext sslContext;
    private final boolean enabled;

    public SslStartupValidator(
            SSLContext applicationSslContext, @Value("${ssl.startup-validation.enabled:true}") boolean enabled) {
        this.sslContext = applicationSslContext;
        this.enabled = enabled;
    }

    @PostConstruct
    public void validateSsl() {
        if (!enabled) {
            log.info("SSL startup validation skipped (ssl.startup-validation.enabled=false)");
            return;
        }
        try {
            URL url = URI.create("https://api.openai.com").toURL();
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(sslContext.getSocketFactory());
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.connect();
            int code = conn.getResponseCode();
            log.info("SSL startup validation SUCCESS: TLS to api.openai.com ok (httpStatus={})", code);
        } catch (Exception ex) {
            log.error("SSL startup validation FAILED: {}", ex.getMessage(), ex);
            log.error("Likely causes:");
            log.error("  1. Missing CA certificate in JVM truststore or ssl.truststore.path trust material");
            log.error("  2. Corporate HTTP proxy not set (proxy.host / proxy.port) or TLS inspection chain incomplete");
            log.error("  3. Outdated Java or blocked outbound HTTPS to api.openai.com");
            log.error("See README-SSL.md for remediation steps.");
        }
    }
}
