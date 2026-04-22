package com.aipmo.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@ConfigurationProperties(prefix = "jira")
public class JiraProperties {

    private Base base = new Base();
    private String email = "";
    private Api api = new Api();
    /** Raw JQL; if blank and {@link #project} is set, JQL becomes {@code project = {project} ORDER BY updated DESC}. */
    private String jql = "";
    /** Convenience when {@link #jql} is empty. */
    private String project = "";
    /** Allowed issue type names for JQL and server-side filtering (e.g. {@code jira.issue.types=Story,Task,Bug}). */
    private Issue issue = new Issue();
    private Search search = new Search();
    /**
     * When true (default), JQL is amended so only open/active work is returned (e.g. {@code
     * statusCategory != Done}). Set false to use the raw {@link #jql} / project query only.
     */
    private boolean openTicketsOnly = true;

    @Data
    public static class Issue {
        /**
         * Issue type names included in JQL {@code issuetype IN (...)} and in the post-fetch safety filter.
         * Sub-tasks are excluded when {@code Sub-task} is not listed. Default: Story, Task, Bug.
         */
        private List<String> types = new ArrayList<>(List.of("Story", "Task", "Bug"));
    }

    @Data
    public static class Search {
        /** Max issues returned from search. */
        private int max = 50;
    }

    @Data
    public static class Base {
        private String url = "";
    }

    @Data
    public static class Api {
        private String token = "";
    }

    public String resolvedBaseUrl() {
        if (base == null || base.url == null) {
            return "";
        }
        return base.url.trim();
    }

    public String resolvedEmail() {
        return email == null ? "" : email.trim();
    }

    public String resolvedApiToken() {
        return api == null || api.token == null ? "" : api.token.trim();
    }

    public String resolvedJql() {
        if (jql != null && !jql.isBlank()) {
            String withTypes = appendIssueTypesToJql(jql.trim());
            if (!openTicketsOnly) {
                return withTypes;
            }
            return appendOpenTicketsOnlyJqlIfNeeded(withTypes);
        }
        if (project != null && !project.isBlank()) {
            String base = appendIssueTypesToJql("project = " + project.trim() + " ORDER BY updated DESC");
            if (!openTicketsOnly) {
                return base;
            }
            return appendOpenTicketsOnlyJqlIfNeeded(base);
        }
        return "";
    }

    /**
     * Names used for JQL {@code issuetype IN (...)} and for {@link com.aipmo.agent.service.JiraService} filtering.
     * If {@link Issue#getTypes()} is empty or only blanks, defaults to Story, Task, Bug.
     */
    public List<String> resolvedAllowedIssueTypeNames() {
        List<String> raw = issue == null || issue.getTypes() == null ? List.of() : issue.getTypes();
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        if (out.isEmpty()) {
            return List.of("Story", "Task", "Bug");
        }
        return List.copyOf(out);
    }

    /**
     * Adds {@code AND issuetype IN (...)} before {@code ORDER BY} when the JQL does not already constrain
     * {@code issuetype}.
     */
    String appendIssueTypesToJql(String jql) {
        if (jql == null || jql.isBlank()) {
            return jql;
        }
        String t = jql.trim();
        if (t.toLowerCase().contains("issuetype")) {
            return t;
        }
        String inList = resolvedAllowedIssueTypeNames().stream()
                .map(JiraProperties::quoteIssueTypeForJql)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(", "));
        if (inList.isEmpty()) {
            return t;
        }
        return insertBeforeOrderBy(t, " AND issuetype IN (" + inList + ")");
    }

    /** Quotes issue type names for JQL when they contain spaces or quotes. */
    static String quoteIssueTypeForJql(String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return "";
        }
        if (n.contains(" ") || n.contains("\"")) {
            return "\"" + n.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return n;
    }

    /**
     * Appends {@code AND statusCategory != Done} when the JQL has no status-related constraint yet.
     * Skips if {@code statusCategory} is already present; note that "status" is a substring of
     * {@code statusCategory}, so existing category filters are not double-applied.
     */
    static String appendOpenTicketsOnlyJqlIfNeeded(String jql) {
        if (jql == null || jql.isBlank()) {
            return jql;
        }
        String t = jql.trim();
        String lower = t.toLowerCase();
        if (lower.contains("statuscategory")) {
            return t;
        }
        if (!lower.contains("status")) {
            return insertBeforeOrderBy(t, " AND statusCategory != Done");
        }
        return t;
    }

    private static String insertBeforeOrderBy(String jql, String fragment) {
        int idx = jql.toUpperCase().lastIndexOf("ORDER BY");
        if (idx < 0) {
            return jql + fragment;
        }
        return jql.substring(0, idx).trim() + fragment + " " + jql.substring(idx).trim();
    }

    public boolean isComplete() {
        return !resolvedBaseUrl().isEmpty()
                && !resolvedEmail().isEmpty()
                && !resolvedApiToken().isEmpty()
                && !resolvedJql().isEmpty();
    }

    /** Human-readable list of unset properties (for errors and startup diagnostics). */
    public String describeConfigurationGaps() {
        List<String> gaps = new ArrayList<>();
        if (resolvedBaseUrl().isEmpty()) {
            gaps.add("jira.base.url");
        }
        if (resolvedEmail().isEmpty()) {
            gaps.add("jira.email");
        }
        if (resolvedApiToken().isEmpty()) {
            gaps.add("jira.api.token");
        }
        if (resolvedJql().isEmpty()) {
            gaps.add("jira.jql or jira.project");
        }
        return String.join(", ", gaps);
    }

    public int resolvedSearchMax() {
        if (search == null) {
            return 50;
        }
        return search.max;
    }
}
