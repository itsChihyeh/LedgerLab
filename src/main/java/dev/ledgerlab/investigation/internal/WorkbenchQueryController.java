package dev.ledgerlab.investigation.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class WorkbenchQueryController {
    private final WorkbenchQueries queries;

    WorkbenchQueryController(WorkbenchQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/overview")
    WorkbenchQueries.Overview overview() {
        return queries.overview();
    }

    @GetMapping("/api/batches")
    WorkbenchQueries.BatchList batches(@RequestParam(defaultValue = "20") int limit) {
        return queries.batches(limit);
    }

    @GetMapping("/api/batches/{id}")
    WorkbenchQueries.BatchDetail batch(@PathVariable String id) {
        return queries.batchDetail(id);
    }

    @GetMapping("/api/recon/issues")
    WorkbenchQueries.IssueList issues(
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String category,
            @RequestParam(defaultValue = "20") int limit) {
        return queries.issues(status, category, limit);
    }

    @GetMapping("/api/recon/issues/{id}")
    WorkbenchQueries.IssueDetail issue(@PathVariable String id) {
        return queries.issueDetail(id);
    }

    @GetMapping("/api/recon/runs")
    WorkbenchQueries.RunList runs(@RequestParam(defaultValue = "20") int limit) {
        return queries.runs(limit);
    }

    @GetMapping("/api/bank-lines/{bankTxnId}")
    WorkbenchQueries.BankDetail bankLine(@PathVariable String bankTxnId) {
        return queries.bankDetail(bankTxnId);
    }

    @GetMapping("/api/statement-lines/{fileId}/{seq}")
    WorkbenchQueries.StatementDetail statementLine(@PathVariable String fileId, @PathVariable int seq) {
        return queries.statementDetail(fileId, seq);
    }
}
