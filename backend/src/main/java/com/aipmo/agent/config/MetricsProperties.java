package com.aipmo.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunable thresholds for {@link com.aipmo.agent.service.MetricsService}. */
@Data
@ConfigurationProperties(prefix = "metrics")
public class MetricsProperties {

    /** Hours in current status before STUCK. */
    private int stuckHours = 24;

    /** Hours in current status before CRITICAL_STUCK. */
    private int criticalHours = 48;

    /** PR time above cohort median × this → PR_DELAY (when PR stats are sufficient). */
    private double prDelayRatio = 1.4;

    /** PR time above median × this → SLOWDOWN; dwell above average × this → TREND_SPIKE. */
    private double anomalyRatio = 1.5;

    /** Minimum tickets with positive PR hours required for PR_DELAY / SLOWDOWN vs median. */
    private int minPrSamples = 3;

    /** Minimum batch size to compare dwell vs cohort average (TREND_SPIKE). */
    private int minDwellSamples = 3;

    /** Ping-pong reversals (A→B→A) needed before BOUNCING. */
    private int bouncePingPongMin = 3;
}
