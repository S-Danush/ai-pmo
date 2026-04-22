package com.aipmo.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Logs Jira-related configuration at startup (API token masked) so mis-binding or missing
 * {@code config/local-keys.properties} is obvious in logs.
 */
@Component
@Order(100)
public class JiraStartupConfigLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JiraStartupConfigLogger.class);

    private final JiraProperties jiraProperties;

    public JiraStartupConfigLogger(JiraProperties jiraProperties) {
        this.jiraProperties = jiraProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String token = jiraProperties.resolvedApiToken();
        log.info(
                "Jira config: baseUrl={} email={} api.token={} jql(raw)={} project(raw)={} issue.types={} resolvedJql={} complete={}",
                emptyToDash(jiraProperties.resolvedBaseUrl()),
                emptyToDash(jiraProperties.resolvedEmail()),
                maskToken(token),
                emptyToDash(jiraProperties.getJql()),
                emptyToDash(jiraProperties.getProject()),
                jiraProperties.resolvedAllowedIssueTypeNames(),
                emptyToDash(jiraProperties.resolvedJql()),
                jiraProperties.isComplete());
        if (!jiraProperties.isComplete()) {
            log.warn(
                    "Jira integration disabled until configured. Missing: {}",
                    jiraProperties.describeConfigurationGaps());
        }
    }

    private static String emptyToDash(String s) {
        if (s == null || s.isBlank()) {
            return "(empty)";
        }
        return s;
    }

    static String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return "(empty)";
        }
        if (token.length() <= 6) {
            return "****";
        }
        return token.substring(0, 2) + "…" + token.substring(token.length() - 2) + " (len=" + token.length() + ")";
    }
}
