package com.aipmo.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Switches the synthetic dataset profile (healthy / watch / stressed). Clears the last agent run so
 * dashboards reload fresh metrics.
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final SimulationDataService simulationDataService;
    private final AgentResultStore agentResultStore;

    public ScenarioService(SimulationDataService simulationDataService, AgentResultStore agentResultStore) {
        this.simulationDataService = simulationDataService;
        this.agentResultStore = agentResultStore;
    }

    public SimulationScenarioTier currentTier() {
        return simulationDataService.getScenarioTier();
    }

    /** Applies tier, rebuilds tickets in memory, and drops cached agent output. */
    public void switchTo(SimulationScenarioTier tier) {
        if (tier == null) {
            tier = SimulationScenarioTier.AMBER;
        }
        simulationDataService.setScenarioTier(tier);
        agentResultStore.clearLastRun();
        log.info("Simulation scenario switched to {}", tier);
    }
}
