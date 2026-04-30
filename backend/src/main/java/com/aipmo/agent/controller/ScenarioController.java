package com.aipmo.agent.controller;

import com.aipmo.agent.service.ScenarioService;
import com.aipmo.agent.service.SimulationScenarioTier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/scenario")
public class ScenarioController {

    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping("/current")
    public Map<String, String> currentScenario() {
        return Map.of("scenario", scenarioService.currentTier().name());
    }

    @PostMapping("/{type}")
    public ResponseEntity<Map<String, String>> setScenario(@PathVariable("type") String type) {
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        SimulationScenarioTier tier =
                switch (t) {
                    case "GREEN" -> SimulationScenarioTier.GREEN;
                    case "RED" -> SimulationScenarioTier.RED;
                    default -> SimulationScenarioTier.AMBER;
                };
        scenarioService.switchTo(tier);
        return ResponseEntity.ok(
                Map.of(
                        "scenario",
                        tier.name(),
                        "message",
                        "Scenario updated — run the agent or refresh insights to load the new dataset."));
    }
}
