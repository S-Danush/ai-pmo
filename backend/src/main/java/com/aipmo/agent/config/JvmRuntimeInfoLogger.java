package com.aipmo.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Logs Java runtime details so SSL/JDK issues can be correlated (LLM PKIX errors are often JDK
 * or trust-store related).
 */
@Component
@Order(50)
public class JvmRuntimeInfoLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JvmRuntimeInfoLogger.class);

    @Override
    public void run(ApplicationArguments args) {
        String spec = System.getProperty("java.specification.version", "?");
        String vm = System.getProperty("java.vm.name", "?");
        String vendor = System.getProperty("java.vendor", "?");
        log.info("Java runtime: specificationVersion={} vm={} vendor={}", spec, vm, vendor);
        int major = parseMajor(spec);
        if (major > 0 && major < 17) {
            log.error(
                    "Java {} is below the required minimum (17+). Upgrade JDK to avoid TLS and API client issues.",
                    spec);
        } else if (major >= 17) {
            log.info("Java version OK for this service (17+): specificationVersion={}", spec);
        }
    }

    private static int parseMajor(String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        int dot = spec.indexOf('.');
        String head = dot > 0 ? spec.substring(0, dot) : spec;
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
