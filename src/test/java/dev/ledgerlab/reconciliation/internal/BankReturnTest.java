package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BankReturnTest {
    private final BankAgingRuleTest fixture = new BankAgingRuleTest();
    private final ProcessorRuleTest f = fixture.f;

    @BeforeEach
    void setup() {
        fixture.setup();
        f.payment("ch_return", 10000, ProcessorRuleTest.CAPTURED);
        f.batch("po_return", ProcessorRuleTest.CAPTURED, List.of(f.localLine("txn_return", "ch_return", 10000, 320)));
        fixture.statement(
                "2026-11-10",
                1,
                0,
                19360,
                fixture.credit("T1", 9680, "po_return", "2026-11-10"),
                fixture.credit("T2", 9680, "po_return", "2026-11-10"));
    }

    @AfterEach
    void cleanup() {
        fixture.cleanup();
    }

    String debit(String id, long amount, String reference, String status) {
        return "D," + id + ",DEBIT," + amount + ",CAD," + status + "," + reference + ",2026-11-12,2026-11-12,return";
    }

    String bankState(String id) {
        return f.jdbc.sql("SELECT match_status FROM recon.bank_line WHERE bank_txn_id=? ORDER BY version DESC LIMIT 1")
                .param(id)
                .query(String.class)
                .single();
    }

    long count(String table) {
        return f.jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    long balance(int account) {
        return f.jdbc.sql("SELECT balance FROM ledger.account WHERE code=?")
                .param(account)
                .query(Long.class)
                .single();
    }

    @Test
    void ac_6_9_returnResolvesAllOpenIssuesAndHistoricalEvidenceStillReplays() {
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        var before = fixture.run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        assertThat(before.summary()).containsEntry("UNAPPLIED_CASH_OPEN", 1L);
        long entries = count("ledger.journal_entry");
        String row = debit("T3", 9680, "T2", "BOOKED");
        String file = fixture.statement("2026-11-12", 1, 19360, 9680, row);
        assertThat(balance(2105)).isZero();
        assertThat(balance(1103)).isEqualTo(9680);
        assertThat(bankState("T2")).isEqualTo("RETURNED");
        assertThat(bankState("T3")).isEqualTo("RETURN_POSTED");
        assertThat(count("ledger.correction")).isZero();
        assertThat(f.jdbc.sql("SELECT status FROM recon.issue WHERE subject_id='T2'")
                        .query(String.class)
                        .list())
                .hasSize(2)
                .containsOnly("RESOLVED");
        assertThat(f.jdbc.sql("SELECT explanation FROM recon.issue WHERE subject_id='T2'")
                        .query(String.class)
                        .list())
                .allMatch(note -> note.contains("T3"));
        assertThat(f.jdbc.sql("""
                SELECT evidence_refs::text FROM recon.issue_transition
                WHERE to_status='RESOLVED'
                """).query(String.class).list())
                .hasSize(2)
                .allMatch(refs -> refs.contains("T3") && refs.contains(file));
        assertThat(f.jdbc.sql("SELECT occurred_at FROM ledger.journal_entry WHERE source_key='bank_txn:T3'")
                        .query(OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(Instant.parse("2026-11-12T05:00:00Z"));
        assertThat(f.jdbc.sql("""
                SELECT p.account_code || ':' || p.side || ':' || p.amount FROM ledger.posting p
                JOIN ledger.journal_entry e ON e.id=p.entry_id WHERE e.source_key='bank_txn:T3'
                """).query(String.class).list()).containsExactlyInAnyOrder("2105:D:9680", "1103:C:9680");
        assertThat(fixture.statement("2026-11-12", 1, 19360, 9680, row)).isEqualTo(file);
        fixture.statement("2026-11-12", 2, 19360, 9680, row);
        assertThat(count("ledger.journal_entry")).isEqualTo(entries + 1);
        assertThat(count("recon.bank_line")).isEqualTo(3);
        Instant later = Instant.parse("2026-11-13T06:00:00Z");
        f.clock.set(later.plusSeconds(61));
        var after = fixture.run(RunRequest.Kind.BANK, Instant.parse("2026-11-13T05:00:00Z"), later, true);
        assertThat(after.findings()).isEmpty();
        assertThat(fixture.run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, true)
                        .findings())
                .isEqualTo(before.findings());
    }

    @ParameterizedTest
    @CsvSource({"9000,T2,CAD", "9680,T1,CAD", "9680,missing,CAD", "9680,T2,USD"})
    void ac_6_9_ineligibleDebitsKeepTheExistingUnmatchedRule(long amount, String reference, String currency) {
        long entries = count("ledger.journal_entry");
        fixture.statement(
                "2026-11-12",
                1,
                19360,
                19360 - amount,
                debit("bad", amount, reference, "BOOKED").replace(",CAD,", "," + currency + ","));
        assertThat(bankState("bad")).isEqualTo("UNMATCHED_NO_POSTING");
        assertThat(fixture.status("UNMATCHED_BANK_LINE", "bad")).isEqualTo("OPEN");
        assertThat(count("ledger.journal_entry")).isEqualTo(entries);
        assertThat(balance(2105)).isEqualTo(9680);
    }

    @Test
    void ac_6_9_pendingAdvancesOnceAndAnotherDebitCannotReturnTheSameCredit() {
        fixture.statement("2026-11-12", 1, 19360, 19360, debit("T3", 9680, "T2", "PENDING"));
        assertThat(bankState("T3")).isEqualTo("IGNORED_PENDING");
        assertThat(bankState("T2")).isEqualTo("UNAPPLIED");
        fixture.statement("2026-11-12", 2, 19360, 9680, debit("T3", 9680, "T2", "BOOKED"));
        assertThat(bankState("T3")).isEqualTo("RETURN_POSTED");
        assertThat(f.jdbc.sql("SELECT match_status FROM recon.bank_line WHERE bank_txn_id='T3' ORDER BY version")
                        .query(String.class)
                        .list())
                .containsExactly("IGNORED_PENDING", "RETURN_POSTED");
        long entries = count("ledger.journal_entry");
        fixture.statement("2026-11-12", 3, 9680, 0, debit("T4", 9680, "T2", "BOOKED"));
        assertThat(bankState("T4")).isEqualTo("UNMATCHED_NO_POSTING");
        assertThat(fixture.status("UNMATCHED_BANK_LINE", "T4")).isEqualTo("OPEN");
        assertThat(count("ledger.journal_entry")).isEqualTo(entries);
    }

    @Test
    void ac_6_9_returnAndIssueResolutionRollBackTogether() {
        var money = f.money();
        long files = count("io.imported_file");
        assertThatThrownBy(() -> f.tx.executeWithoutResult(tx -> {
                    fixture.statement("2026-11-12", 1, 19360, 9680, debit("T3", 9680, "T2", "BOOKED"));
                    assertThat(bankState("T2")).isEqualTo("RETURNED");
                    assertThat(fixture.status("UNMATCHED_BANK_LINE", "T2")).isEqualTo("RESOLVED");
                    throw new IllegalStateException("rollback after return");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback after return");
        assertThat(f.money()).isEqualTo(money);
        assertThat(count("io.imported_file")).isEqualTo(files);
        assertThat(count("recon.issue_transition")).isZero();
        assertThat(bankState("T2")).isEqualTo("UNAPPLIED");
        assertThat(fixture.status("UNMATCHED_BANK_LINE", "T2")).isEqualTo("OPEN");
    }

    @Test
    void ac_6_9_returnUsesLatestCreditVersionAndPreservesAcceptedIssue() {
        String pending =
                fixture.credit("versioned", 500, "unknown", "2026-11-10").replace("BOOKED", "PENDING");
        fixture.statement("2026-11-10", 2, 19360, 19360, pending);
        fixture.statement("2026-11-10", 3, 19360, 19860, pending.replace("PENDING", "BOOKED"));
        String issue = f.jdbc.sql("SELECT id FROM recon.issue WHERE subject_id='versioned'")
                .query(String.class)
                .single();
        new IssueStore(f.jdbc, dev.ledgerlab.shared.IdGenerator.seeded(f.clock, 6009), f.clock, f.manager)
                .accept(issue, "previously reviewed");
        fixture.statement("2026-11-12", 1, 19860, 19360, debit("version_return", 500, "versioned", "BOOKED"));
        assertThat(f.jdbc.sql("SELECT match_status FROM recon.bank_line WHERE bank_txn_id='versioned' ORDER BY version")
                        .query(String.class)
                        .list())
                .containsExactly("IGNORED_PENDING", "RETURNED");
        assertThat(fixture.status("UNMATCHED_BANK_LINE", "versioned")).isEqualTo("ACCEPTED");
        assertThat(f.jdbc.sql("SELECT count(*) FROM recon.issue_transition WHERE issue_id=? AND to_status='RESOLVED'")
                        .param(issue)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(balance(2105)).isEqualTo(9680);
    }

    @Test
    void ac_6_9_concurrentDebitsReturnOneCreditOnlyOnce() throws Exception {
        long entries = count("ledger.journal_entry");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return fixture.statement("2026-11-12", 1, 19360, 9680, debit("return_a", 9680, "T2", "BOOKED"));
            });
            var second = executor.submit(() -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return fixture.statement("2026-11-12", 2, 19360, 9680, debit("return_b", 9680, "T2", "BOOKED"));
            });
            first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            second.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(List.of(bankState("return_a"), bankState("return_b")))
                .containsExactlyInAnyOrder("RETURN_POSTED", "UNMATCHED_NO_POSTING");
        assertThat(count("ledger.journal_entry")).isEqualTo(entries + 1);
        assertThat(balance(1103)).isEqualTo(9680);
        assertThat(balance(2105)).isZero();
        assertThat(f.jdbc.sql("SELECT count(*) FROM recon.issue_transition WHERE to_status='RESOLVED'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }
}
