package dev.ledgerlab.sim.relay;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
public final class RelayController {
    private final Relay relay;

    public RelayController(Relay relay) {
        this.relay = relay;
    }

    @PostMapping("/relay/stripe")
    public void receive(
            @RequestBody(required = false) byte @Nullable [] body,
            @RequestHeader(value = "Stripe-Signature", required = false) @Nullable String signature) {
        relay.receive(body == null ? new byte[0] : body, signature);
    }

    @PutMapping("/sim/relay/plan")
    public ResponseEntity<?> replace(@RequestBody JsonNode input) {
        try {
            relay.replace(RelayPlan.parse(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(inspect());
    }

    @GetMapping("/sim/relay/plan")
    public ObjectNode inspect() {
        ObjectNode result = JsonMapper.builder().build().createObjectNode();
        var rules = result.putArray("rules");
        for (RelayPlan.Rule rule : relay.plan().rules()) {
            var node = rules.addObject().put("action", rule.action().name());
            if (rule.eventId() != null) node.put("event_id", rule.eventId());
            if (rule.eventType() != null) node.put("event_type", rule.eventType());
            switch (rule.action()) {
                case DUPLICATE -> node.put("copies", rule.count());
                case DELAY -> node.put("delay_seconds", rule.count());
                case HOLD_AND_REORDER -> node.put("after_events", rule.count());
                default -> {}
            }
        }
        return result;
    }
}
