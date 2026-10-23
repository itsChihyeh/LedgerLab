package dev.ledgerlab.sim.scenario;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ScenarioController {
    private final ScenarioRunner runner;

    public ScenarioController(ScenarioRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/sim/scenarios/{name}/run")
    public Map<String, String> run(@PathVariable String name) {
        return runner.run(name);
    }
}
