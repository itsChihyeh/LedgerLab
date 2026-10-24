package dev.ledgerlab.settlement;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class BankStatementController {
    private final BankStatements statements;

    BankStatementController(BankStatements statements) {
        this.statements = statements;
    }

    @PostMapping(value = "/api/bank-statements", consumes = "text/csv", produces = "application/json")
    BankStatements.ImportResult importStatement(
            @RequestHeader(value = "X-Source-Name", defaultValue = "bank-statement.csv") String sourceName,
            @RequestBody(required = false) byte @Nullable [] content) {
        return statements.importBankStatement(sourceName, content == null ? new byte[0] : content);
    }
}
