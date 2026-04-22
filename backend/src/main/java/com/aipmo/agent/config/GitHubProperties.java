package com.aipmo.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    private String token = "";
    /** Repository as {@code owner/repo}. */
    private String repo = "";

    public String resolvedToken() {
        return token == null ? "" : token.trim();
    }

    public String resolvedRepo() {
        return repo == null ? "" : repo.trim();
    }

    public boolean isComplete() {
        String r = resolvedRepo();
        if (r.isEmpty() || !r.contains("/")) {
            return false;
        }
        String[] parts = r.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return false;
        }
        return !resolvedToken().isEmpty();
    }

    public String owner() {
        String r = resolvedRepo();
        int i = r.indexOf('/');
        return i > 0 ? r.substring(0, i) : "";
    }

    public String repoName() {
        String r = resolvedRepo();
        int i = r.indexOf('/');
        return i >= 0 && i < r.length() - 1 ? r.substring(i + 1) : "";
    }
}
