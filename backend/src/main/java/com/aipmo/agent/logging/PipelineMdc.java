package com.aipmo.agent.logging;

import org.slf4j.MDC;

/** Request-scoped pipeline labels for structured audit logs (use with SLF4J MDC). */
public final class PipelineMdc {

    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_STAGE = "stage";
    public static final String KEY_ACTION = "action";
    public static final String KEY_SIMULATION = "simulation";

    public static final String STAGE_PIPELINE = "PIPELINE";
    public static final String STAGE_DATA_FETCH = "DATA_FETCH";
    public static final String STAGE_METRICS = "METRICS";
    public static final String STAGE_AI = "AI";
    public static final String STAGE_NOTIFY = "NOTIFY";
    public static final String STAGE_SUMMARY = "SUMMARY";

    public static final String ACTION_START = "start";
    public static final String ACTION_COMPLETE = "complete";
    public static final String ACTION_JIRA_GITHUB = "jira_github";
    public static final String ACTION_SIMULATION = "simulation";
    public static final String ACTION_CACHE_HIT = "cache_hit";
    public static final String ACTION_ANALYZE = "analyze";
    public static final String ACTION_INSIGHT_REQUEST = "insight_request";
    public static final String ACTION_INSIGHT_CACHE = "insight_cache";
    public static final String ACTION_NUDGE_REQUEST = "nudge_request";
    public static final String ACTION_NUDGE_CACHE = "nudge_cache";
    public static final String ACTION_TEAMS_WEBHOOK = "teams_webhook";
    public static final String ACTION_PROJECT_SUMMARY = "project_summary";

    private PipelineMdc() {}

    public static void stage(String stage) {
        if (stage == null) {
            MDC.remove(KEY_STAGE);
        } else {
            MDC.put(KEY_STAGE, stage);
        }
    }

    public static void action(String action) {
        if (action == null) {
            MDC.remove(KEY_ACTION);
        } else {
            MDC.put(KEY_ACTION, action);
        }
    }

    public static void stageAndAction(String stage, String action) {
        stage(stage);
        action(action);
    }

    public static void clearStageAction() {
        MDC.remove(KEY_STAGE);
        MDC.remove(KEY_ACTION);
    }
}
