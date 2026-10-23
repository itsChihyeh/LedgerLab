package dev.ledgerlab.sim.bank;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public final class BankController {
    private final Bank bank;

    public BankController(Bank bank) {
        this.bank = bank;
    }

    @PutMapping("/sim/bank/plan")
    public JsonNode plan(@RequestBody JsonNode request) {
        return bank.plan(request);
    }

    @GetMapping(value = "/sim/bank/statements/{date}", produces = "text/csv;charset=UTF-8")
    public String statement(@PathVariable LocalDate date, @RequestParam(defaultValue = "") String corrupt) {
        return bank.statement(date, corrupt);
    }

    @GetMapping("/sim/bank/transactions")
    public List<Bank.Line> transactions() {
        return bank.transactions();
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    public ResponseEntity<Map<String, String>> invalidRange(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid bank value or calendar range"));
    }
}
