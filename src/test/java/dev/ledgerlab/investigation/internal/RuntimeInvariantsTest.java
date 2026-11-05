package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.investigation.internal.InvariantQueryController.AuditResult;
import dev.ledgerlab.investigation.internal.InvariantQueryController.InvariantResult;
import dev.ledgerlab.payouts.internal.PayoutCorrectionFixture;
import dev.ledgerlab.refunds.internal.CorrectionFixture;
import dev.ledgerlab.testsupport.InvariantChecks;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AC-9.9: the twelve runtime invariants. The scenario is built through the real services (payment, settled batch,
 * bank deposit, refund, payout, unapplied cash), checked clean, then broken directly in the database as the
 * owner role, bypassing the application, and the matching check must report it. Where the database itself would
 * refuse the corruption (seal triggers, the non-negative CHECK, the unique source key) the test lifts exactly that
 * guard first: it proves the runtime check still catches what the constraint normally prevents.
 */
class RuntimeInvariantsTest {
    private CorrectionFixture f;
    private InvariantQueryController controller;
    private String payment;
    private String payout;
    private String refund;

    @BeforeEach
    void setup() {
        f = new CorrectionFixture();
        payment = f.s1();
        f.batch("po_b1", "ch_p1", 10000, 320);
        f.bankWaiting("po_b1", 9680);
        refund = f.accept(payment, 3000).refundId();
        payout = PayoutCorrectionFixture.payouts(f)
                .reserve(5000, "T01", "fixture")
                .payoutId();
        String unapplied = "H,BANK_STATEMENT,SIM-CAD-001,2026-10-24,2026-10-24,1,CAD\r\n"
                + "D,bt_unapplied,CREDIT,700,CAD,BOOKED,nobody-knows,2026-10-24,2026-10-24,fixture\r\n"
                + "T,1,700,0,9680,10380\r\n";
        assertThat(f.importBank("unapplied.csv", unapplied)).isEqualTo("ACCEPTED");
        controller = new InvariantQueryController(
                f.db.readonly(),
                Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC),
                InvariantChecks.all());
    }

    @AfterEach
    void close() {
        f.close();
    }

    @Test
    void ac_9_9_cleanDatabaseReportsAllTwelveOk() {
        try (var empty = new CorrectionFixture()) {
            var result = new InvariantQueryController(
                            empty.db.readonly(),
                            Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC),
                            InvariantChecks.all())
                    .check();
            assertThat(result.checks()).extracting(InvariantResult::id).containsExactly(ids());
            assertThat(result.checks()).allMatch(InvariantResult::ok);
            assertThat(result.violations()).isEmpty();
        }
    }

    @Test
    void ac_9_9_normalBusinessPathsLeaveAllTwelveOk() {
        assertAllOk(controller.check());
        assertThat(f.number("SELECT count(*) FROM recon.bank_line WHERE match_status = 'UNAPPLIED'"))
                .as("the scenario holds unapplied cash, so INV-12 is exercised with a non-zero balance")
                .isEqualTo(1);
        assertThat(f.number("SELECT balance FROM ledger.account WHERE code = 2105"))
                .isEqualTo(700);
        assertThat(f.number("SELECT count(*) FROM core.refund WHERE id = '" + refund + "'"))
                .isEqualTo(1);
    }

    @Test
    void ac_9_9_inv1_unbalancedEntryIsReported() throws SQLException {
        tamper(
                List.of("ledger.journal_entry"),
                "UPDATE ledger.journal_entry SET total_amount = total_amount + 1 WHERE seq = 1");
        assertViolated("INV-1");
    }

    @Test
    void ac_9_9_inv2_storedBalanceDifferentFromPostingsIsReported() throws SQLException {
        tamper(List.of(), "UPDATE ledger.account SET balance = balance + 1 WHERE code = 2102");
        assertViolated("INV-2");
    }

    @Test
    void ac_9_9_inv3_assetsDifferentFromLiabilitiesIsReported() throws SQLException {
        tamper(List.of(), "UPDATE ledger.account SET balance = balance + 5 WHERE code = 1103");
        assertViolated("INV-3");
    }

    @Test
    void ac_9_9_inv4_sealedCountDifferentFromPostingsIsReported() throws SQLException {
        tamper(
                List.of("ledger.journal_entry"),
                "UPDATE ledger.journal_entry SET posting_count = posting_count + 1 WHERE seq = 1");
        assertViolated("INV-4");
    }

    @Test
    void ac_9_9_inv5_negativeProtectedAccountIsReported() throws SQLException {
        // The CHECK on ledger.account is what normally refuses this; lift it to prove the runtime check also sees it.
        tamper(
                List.of(),
                "ALTER TABLE ledger.account DROP CONSTRAINT account_non_negative",
                "UPDATE ledger.account SET balance = -5 WHERE code = 2104");
        assertViolated("INV-5");
    }

    @Test
    void ac_9_9_inv6_reservationThatLeavesAvailableNegativeIsReported() throws SQLException {
        tamper(List.of("ledger.posting"), """
                UPDATE ledger.posting SET balance_after = -5 WHERE account_code = 2102 AND entry_id =
                    (SELECT id FROM ledger.journal_entry WHERE entry_type = 'PAYOUT_RESERVED')
                """);
        assertViolated("INV-6");
    }

    @Test
    void ac_9_9_inv6_reservationAboveFreeCashIsReported() throws SQLException {
        // Shrink the deposit that funded the cash: the 5000 reservation then exceeded the cash that was there.
        tamper(List.of("ledger.posting"), """
                UPDATE ledger.posting SET amount = 100 WHERE account_code = 1103 AND entry_id =
                    (SELECT id FROM ledger.journal_entry WHERE entry_type = 'BANK_DEPOSIT_RECEIVED')
                """);
        assertViolated("INV-6");
    }

    @Test
    void ac_9_9_inv7_refundsAbovePaymentAreReported() throws SQLException {
        tamper(List.of("core.refund"), "UPDATE core.refund SET amount = 10001 WHERE id = '" + refund + "'");
        assertViolated("INV-7");
    }

    @Test
    void ac_9_9_inv8_twoEntriesWithOneSourceKeyAreReported() throws SQLException {
        // The UNIQUE constraint normally makes this impossible; drop it, then give two entries the same key.
        tamper(
                List.of("ledger.journal_entry"),
                "ALTER TABLE ledger.journal_entry DROP CONSTRAINT journal_entry_source_key_key",
                "UPDATE ledger.journal_entry SET source_key = (SELECT source_key FROM ledger.journal_entry WHERE seq = 1)"
                        + " WHERE seq = 2");
        assertViolated("INV-8");
    }

    @Test
    void ac_9_9_inv9_capturedPaymentsDifferentFromPendingBalanceAreReported() throws SQLException {
        tamper(List.of("core.payment"), "UPDATE core.payment SET status = 'CAPTURED' WHERE id = '" + payment + "'");
        assertViolated("INV-9", "2101");
    }

    @Test
    void ac_9_9_inv9_inTransitRefundsDifferentFromRefundBalanceAreReported() throws SQLException {
        tamper(List.of("core.refund"), "UPDATE core.refund SET amount = 2999 WHERE id = '" + refund + "'");
        assertViolated("INV-9", "2103");
    }

    @Test
    void ac_9_9_inv9_inTransitPayoutsDifferentFromPayoutBalanceAreReported() throws SQLException {
        tamper(List.of("core.payout"), "UPDATE core.payout SET amount = 5001 WHERE id = '" + payout + "'");
        assertViolated("INV-9", "2104");
    }

    @Test
    void ac_9_9_inv9_confirmedBatchesDifferentFromSettlementBalanceAreReported() throws SQLException {
        tamper(
                List.of("core.settlement_batch"),
                "UPDATE core.settlement_batch SET status = 'CONFIRMED' WHERE batch_id = 'po_b1'");
        assertViolated("INV-9", "1102");
    }

    @Test
    void ac_9_9_inv10_refundReachingFinalStatusTwiceIsReported() throws SQLException {
        tamper(List.of(), """
                INSERT INTO core.refund_transition(subject_id, from_status, to_status, evidence_type, evidence_id,
                    evidence_source, evidence_ref, occurred_at, recorded_at)
                SELECT '%s', 'SUBMITTED', 'SUCCEEDED', 'fixture', 'x', 'OPERATOR', 'x', now(), now()
                FROM generate_series(1, 2)
                """.formatted(refund));
        assertViolated("INV-10", refund);
    }

    @Test
    void ac_9_9_inv10_payoutReservedTwiceIsReported() throws SQLException {
        // Relabel the refund acceptance as a second reservation of the same payout.
        tamper(
                List.of("ledger.journal_entry"),
                "UPDATE ledger.journal_entry SET entry_type = 'PAYOUT_RESERVED', subject_type = 'payout',"
                        + " subject_id = '" + payout + "' WHERE entry_type = 'REFUND_ACCEPTED'");
        assertViolated("INV-10", payout);
    }

    @Test
    void ac_9_9_inv11_statementThatDoesNotAddUpIsReported() throws SQLException {
        tamper(
                List.of("recon.bank_statement"),
                "UPDATE recon.bank_statement SET closing_balance = closing_balance + 1");
        assertViolated("INV-11", "opening");
    }

    @Test
    void ac_9_9_inv11_cashDifferentFromBankWithoutRecordIsReported() throws SQLException {
        tamper(List.of(), "UPDATE ledger.account SET balance = balance + 7 WHERE code = 1103");
        assertViolated("INV-11", "has no record");
    }

    @Test
    void ac_9_9_inv11_differenceWithAnOpenIssueIsNotReported() throws SQLException {
        tamper(List.of(), "UPDATE ledger.account SET balance = balance + 7 WHERE code = 1103", """
                INSERT INTO recon.issue(id, category, subject_type, subject_id, detected_by, first_run_id, status,
                    recorded_at, updated_at)
                VALUES ('iss_00000000000000000000000000', 'BANK_BALANCE_MISMATCH', 'bank_statement',
                    'SIM-CAD-001:2026-10-24:2026-10-24', 'INGESTION', NULL, 'OPEN', now(), now())
                """);
        assertThat(controller.check().checks())
                .filteredOn(check -> check.id().equals("INV-11"))
                .allMatch(InvariantResult::ok);
    }

    @Test
    void ac_9_9_inv12_unappliedBalanceDifferentFromUnappliedLinesIsReported() throws SQLException {
        // Mark the unapplied credit as returned without moving the money: 2105 still holds it.
        tamper(List.of(), "UPDATE recon.bank_line SET match_status = 'RETURNED' WHERE match_status = 'UNAPPLIED'");
        assertViolated("INV-12");
    }

    @Test
    void ac_9_9_readonlyRoleRunsEveryCheckWithoutWritePrivileges() throws SQLException {
        try (Connection connection = f.db.readonly().getConnection();
                Statement statement = connection.createStatement()) {
            try (var user = statement.executeQuery("SELECT current_user")) {
                assertThat(user.next()).isTrue();
                assertThat(user.getString(1)).isEqualTo("readonly");
            }
        }
        var transaction = new TransactionTemplate(new JdbcTransactionManager(f.db.readonly()));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var jdbc = JdbcClient.create(f.db.readonly());
        int executed = transaction.execute(status -> {
            int count = 0;
            for (var check : InvariantChecks.all()) {
                assertThat(check.check(jdbc).values()).allMatch(List::isEmpty);
                count++;
            }
            return count;
        });
        assertThat(executed).isEqualTo(InvariantChecks.all().size());
    }

    @Test
    void ac_9_9_concurrentPostingNeverProducesAFalseAlarm() throws Exception {
        var running = new AtomicBoolean(true);
        var committed = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var started = new CountDownLatch(1);
        var firstCommit = new CountDownLatch(1);
        var writer = new Thread(() -> {
            try {
                started.await();
                for (int i = 0; running.get(); i++) {
                    f.payment("ch_race_" + i, 500 + i);
                    committed.incrementAndGet();
                    firstCommit.countDown();
                }
            } catch (Throwable e) {
                failure.set(e);
                firstCommit.countDown();
            }
        });
        writer.start();
        try {
            started.countDown();
            firstCommit.await();
            for (int i = 0; i < 20; i++) {
                int before = committed.get();
                assertAllOk(controller.check());
                // Let the writer commit at least once between two checks so each check really races with posting.
                while (committed.get() == before && failure.get() == null) {
                    Thread.onSpinWait();
                }
            }
        } finally {
            running.set(false);
            writer.join();
        }
        assertThat(failure.get()).isNull();
        assertThat(committed.get()).isGreaterThan(20);
        assertAllOk(controller.check());
    }

    private void assertViolated(String id, String... detailParts) {
        AuditResult result = controller.check();
        InvariantResult found = result.checks().stream()
                .filter(check -> check.id().equals(id))
                .findFirst()
                .orElseThrow();
        assertThat(found.ok())
                .as("%s must be reported; got %s", id, result.violations())
                .isFalse();
        assertThat(found.violations())
                .allMatch(violation -> violation.invariant().equals(id));
        for (String part : detailParts) {
            assertThat(found.violations())
                    .anyMatch(violation -> violation.detail().contains(part));
        }
        assertThat(result.violations()).containsAll(found.violations());
    }

    private static void assertAllOk(AuditResult result) {
        assertThat(result.violations()).isEmpty();
        assertThat(result.checks()).extracting(InvariantResult::id).containsExactly(ids());
        assertThat(result.checks()).allMatch(InvariantResult::ok);
    }

    private static String[] ids() {
        return new String[] {
            "INV-1", "INV-2", "INV-3", "INV-4", "INV-5", "INV-6", "INV-7", "INV-8", "INV-9", "INV-10", "INV-11",
            "INV-12"
        };
    }

    /** Runs statements as the owner role, with the user triggers of the named tables off, in one transaction. */
    private void tamper(List<String> tablesWithoutTriggers, String... statements) throws SQLException {
        try (Connection connection = f.db.migrator().getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String table : tablesWithoutTriggers) {
                    statement.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
                }
                for (String sql : statements) {
                    statement.execute(sql);
                }
                for (String table : tablesWithoutTriggers) {
                    statement.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
                }
            }
            connection.commit();
        }
    }
}
