package dev.ledgerlab.sim.bank;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import tools.jackson.databind.JsonNode;

@RestController
public final class BankPaymentController {
    private final Bank bank;
    private final long timeoutMillis;

    public BankPaymentController(Bank bank, @Value("${sim.bank.timeout:PT30S}") Duration timeout) {
        this.bank = bank;
        this.timeoutMillis = timeout.toMillis();
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Bank timeout must be positive");
    }

    @PostMapping("/sim/bank/payments")
    public DeferredResult<ResponseEntity<JsonNode>> submit(@RequestBody JsonNode request, HttpServletResponse response)
            throws IOException {
        // Bank commits before transport fault injection. No transaction/lock remains during the hang.
        var result = bank.submitPayment(request);
        var deferred = new DeferredResult<ResponseEntity<JsonNode>>(
                timeoutMillis, ResponseEntity.status(result.status()).body(result.body()));
        if (result.transport().equals("EXECUTE_DROP_RESPONSE")) {
            // Deliberately truncate a framed response: closing an unframed empty response would be success.
            response.setStatus(result.status());
            response.setHeader("Connection", "close");
            response.setContentLength(1);
            response.flushBuffer();
            response.getOutputStream().close();
            deferred.setResult(ResponseEntity.status(result.status()).build());
        } else if (!result.transport().equals("TIMEOUT")) {
            deferred.setResult(ResponseEntity.status(result.status()).body(result.body()));
        }
        return deferred;
    }

    @GetMapping("/sim/bank/payments/{requestId}")
    public ResponseEntity<JsonNode> get(@PathVariable String requestId) {
        var result = bank.payment(requestId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/sim/bank/payments")
    public ResponseEntity<JsonNode> list() {
        var result = bank.payments();
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    public ResponseEntity<Map<String, String>> invalid(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }
}
