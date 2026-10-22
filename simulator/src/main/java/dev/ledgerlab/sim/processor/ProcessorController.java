package dev.ledgerlab.sim.processor;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/sim")
public final class ProcessorController {
    private final Processor processor;

    public ProcessorController(Processor processor) {
        this.processor = processor;
    }

    @PostMapping("/charges")
    public Map<String, Object> create(@RequestBody JsonNode request) {
        return processor.create(request);
    }

    @PostMapping("/events/{id}/redeliver")
    public Map<String, Object> redeliver(@PathVariable String id) {
        return processor.redeliver(id);
    }

    @PostMapping("/events/{id}/reissue")
    public Map<String, Object> reissue(@PathVariable String id) {
        return processor.reissue(id);
    }

    @GetMapping("/events/{id}")
    public Map<String, Object> inspect(@PathVariable String id) {
        return processor.inspect(id);
    }
}
