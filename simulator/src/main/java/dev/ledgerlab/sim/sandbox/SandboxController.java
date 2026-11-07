package dev.ledgerlab.sim.sandbox;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@Profile("sandbox")
@RequestMapping("/sim/sandbox")
public final class SandboxController {
    private final SandboxPayments payments;
    private final SandboxBankSync bank;

    public SandboxController(SandboxPayments payments, SandboxBankSync bank) {
        this.payments = payments;
        this.bank = bank;
    }

    @PostMapping("/payments")
    public Map<String, Object> generate(@RequestBody JsonNode request) {
        return payments.generate(request);
    }

    @GetMapping("/payments")
    public List<Map<String, @org.jspecify.annotations.Nullable Object>> records() {
        return payments.records();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return payments.summary();
    }

    @PostMapping("/bank/sync")
    public ResponseEntity<SandboxBankSync.Result> sync() {
        var result = bank.sync();
        return ResponseEntity.status(result.httpStatus()).body(result);
    }
}
