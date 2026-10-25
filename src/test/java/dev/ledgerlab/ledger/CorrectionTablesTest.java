package dev.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CorrectionTablesTest {
    private static final String COR = "cor_00000000000000000000000001";
    private static final String ENTRY = "je_00000000000000000000000001";
    private static final String FILE = "file_00000000000000000000000001";
    private static final String TIME = "'2026-10-25T12:00:00Z'";
    private LedgerFixture f;

    @BeforeEach
    void setup() {
        f = new LedgerFixture(TestDatabase.freshMigrated());
    }

    @AfterEach
    void cleanup() {
        f.db().drop();
    }

    private static String correction(Map<String, String> overrides) {
        var values = new LinkedHashMap<String, String>();
        values.put("id", "'" + COR + "'");
        values.put("correction_type", "'VOID_CAPTURE'");
        values.put("reason_code", "'OTHER'");
        values.put("reason_note", "'verified'");
        values.put("evidence_refs", "'[{\"type\":\"inbox_event\",\"id\":\"evt_test\"}]'");
        values.put("params", "'{}'");
        values.put("subject_type", "'payment'");
        values.put("subject_id", "'pay_test'");
        values.put("requested_by", "'operator'");
        values.put("recorded_at", TIME);
        values.putAll(overrides);
        return "INSERT INTO ledger.correction (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", values.values()) + ")";
    }

    private static String target(String entry, String role) {
        return "INSERT INTO ledger.correction_target (correction_id,entry_id,role,recorded_at) VALUES ('" + COR + "','"
                + entry + "','" + role + "'," + TIME + ")";
    }

    private static String entry(String type, @Nullable String original, int count, long total) {
        return """
                INSERT INTO ledger.journal_entry
                    (id,entry_type,source_key,posting_count,total_amount,occurred_at,recorded_at,
                     subject_type,subject_id,reverses_entry_id,correction_id)
                VALUES ('%s','%s','test:generated',%d,%d,%s,%s,'payment','pay_test',%s,'%s')
                """.formatted(
                        ENTRY, type, count, total, TIME, TIME, original == null ? "NULL" : "'" + original + "'", COR);
    }

    private static String postings(String values) {
        return """
                INSERT INTO ledger.posting
                    (id,entry_id,line_no,account_code,side,amount,account_version,balance_after,recorded_at)
                SELECT 'pst_' || lpad(n::text,26,'0'), '%s', n, account, side, amount, 1, 0, %s
                FROM (VALUES %s) AS lines(n,account,side,amount)
                """.formatted(ENTRY, TIME, values);
    }

    private static final String MIRROR = "(1,1101,'C',10000),(2,2101,'D',10000)";

    private void validCorrection() throws SQLException {
        String original = f.s1().entryId();
        Sql.execute(
                f.db().app(),
                correction(Map.of()),
                target(original, "REVERSED"),
                entry("REVERSAL", original, 2, 10000),
                postings(MIRROR));
    }

    @Test
    void ac_6_8_emptyMigrationOwnsTablesAndViews() throws SQLException {
        assertThat(Sql.queryLong(
                        f.db().migrator(),
                        "SELECT count(*) FROM flyway.flyway_schema_history WHERE version='20' AND success"))
                .isEqualTo(1);
        assertThat(Sql.queryLong(f.db().migrator(), """
                SELECT count(*) FROM pg_tables WHERE schemaname='ledger' AND
                tablename IN ('correction','correction_target') AND tableowner='migrator'
                """)).isEqualTo(2);
        assertThat(Sql.queryLong(f.db().migrator(), """
                SELECT count(*) FROM pg_views WHERE schemaname='ledger' AND
                viewname IN ('v_correction','v_correction_target') AND viewowner='migrator'
                """)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id|'bad'",
                "correction_type|'BAD'",
                "reason_code|'BAD'",
                "reason_note|''",
                "reason_note|E' \\t\\n'",
                "reason_note|repeat('x',2001)",
                "evidence_refs|'[]'",
                "requested_by|E' \\t\\n'",
                "subject_type|'bad'",
                "subject_id|''"
            })
    void ac_6_7_metadataChecksRejectInvalidRows(String change) {
        var parts = change.split("\\|", 2);
        Sql.assertRejected("23514", f.db().app(), correction(Map.of(parts[0], parts[1])));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "params",
                "evidence_refs",
                "reason_code",
                "reason_note",
                "requested_by",
                "recorded_at",
                "subject_type",
                "subject_id",
                "correction_type"
            })
    void ac_6_7_requiredMetadataCannotBeSqlNull(String column) {
        Sql.assertRejected("23502", f.db().app(), correction(Map.of(column, "NULL")));
    }

    @Test
    void ac_6_7_evidenceMustBeArray() {
        // PostgreSQL may evaluate jsonb_array_length before the type predicate, but must reject either way.
        var error = catchThrowable(() -> Sql.execute(f.db().app(), correction(Map.of("evidence_refs", "'{}'"))));
        assertThat(error).isInstanceOf(SQLException.class);
        assertThat(((SQLException) error).getSQLState()).isIn("23514", "22023");
    }

    @Test
    void ac_6_8_emptyCorrectionFailsAtCommit() throws SQLException {
        assertCommitRejected(correction(Map.of()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "(1,1101,'C',9999),(2,2101,'D',9999)",
                "(1,1101,'D',10000),(2,2101,'C',10000)",
                "(1,1101,'C',10000),(2,2102,'D',10000)",
                "(1,1101,'C',5000),(2,1101,'C',5000),(3,2101,'D',10000)"
            })
    void ac_6_8_balancedButNonMirrorReversalFailsAtCommit(String lines) throws SQLException {
        String original = f.s1().entryId();
        int count = lines.contains("(3,") ? 3 : 2;
        long amount = lines.contains("9999") ? 9999 : 10000;
        assertCommitRejected(
                correction(Map.of()),
                target(original, "REVERSED"),
                entry("REVERSAL", original, count, amount),
                postings(lines));
    }

    @Test
    void ac_6_8_reversedTargetWithoutReversalFailsAtCommit() throws SQLException {
        String original = f.s1().entryId();
        assertCommitRejected(
                correction(Map.of()),
                target(original, "REVERSED"),
                entry("CORRECTION", null, 2, 10000),
                postings(MIRROR));
    }

    @Test
    void ac_6_8_reversalWithoutTargetFailsAtCommit() throws SQLException {
        String original = f.s1().entryId();
        assertCommitRejected(correction(Map.of()), entry("REVERSAL", original, 2, 10000), postings(MIRROR));
    }

    @Test
    void ac_6_8_reversalAndTargetMustBelongToSameCorrection() throws SQLException {
        String original = f.s1().entryId();
        String second = "cor_00000000000000000000000002";
        assertCommitRejected(
                correction(Map.of()),
                correction(Map.of("id", "'" + second + "'")),
                target(original, "REVERSED").replace(COR, second),
                entry("REVERSAL", original, 2, 10000),
                postings(MIRROR));
    }

    @Test
    void ac_6_10_appliedTargetMustBeUnappliedCash() throws SQLException {
        String original = f.s1().entryId();
        assertCommitRejected(
                correction(Map.of()),
                target(original, "APPLIED"),
                entry("CORRECTION", null, 2, 10000),
                postings(MIRROR));
    }

    @Test
    void ac_6_6_databaseRejectsReversalOfReversal() throws SQLException {
        validCorrection();
        String second = "cor_00000000000000000000000002";
        String nextEntry = "je_00000000000000000000000002";
        assertCommitRejected(
                correction(Map.of("id", "'" + second + "'")),
                target(ENTRY, "REVERSED").replace(COR, second),
                entry("REVERSAL", ENTRY, 2, 10000)
                        .replace("'" + ENTRY + "','REVERSAL'", "'" + nextEntry + "','REVERSAL'")
                        .replace("test:generated", "test:second")
                        .replace(COR, second),
                postings("(3,1101,'D',10000),(4,2101,'C',10000)").replace(ENTRY, nextEntry));
    }

    @Test
    void ac_6_6_databaseTargetIsUniqueAcrossCorrectionsAndRoles() throws SQLException {
        String original = f.s1().entryId();
        Sql.assertRejected(
                "23505",
                f.db().app(),
                correction(Map.of()),
                correction(Map.of("id", "'cor_00000000000000000000000002'")),
                target(original, "REVERSED"),
                target(original, "APPLIED").replace(COR, "cor_00000000000000000000000002"));
    }

    @Test
    void ac_6_6_targetRoleAndForeignKeysAreEnforced() {
        Sql.assertRejected("23503", f.db().app(), correction(Map.of()), target("missing", "REVERSED"));
        String original = f.s1().entryId();
        Sql.assertRejected("23514", f.db().app(), correction(Map.of()), target(original, "BAD"));
        Sql.assertRejected("23503", f.db().app(), target(original, "REVERSED"));
        Sql.assertRejected("23503", f.db().app(), entry("CORRECTION", null, 2, 10000));
        Sql.assertRejected("23503", f.db().app(), issue(COR));
    }

    @Test
    void ac_6_8_oneReplacementPerCorrection() {
        String secondEntry = "je_00000000000000000000000002";
        Sql.assertRejected(
                "23505",
                f.db().app(),
                correction(Map.of()),
                entry("CORRECTION", null, 2, 10000),
                entry("CORRECTION", null, 2, 10000)
                        .replace(ENTRY, secondEntry)
                        .replace("test:generated", "test:second"));
    }

    private void assertCommitRejected(String... statements) throws SQLException {
        try (var connection = f.db().app().getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var s = connection.createStatement()) {
                    for (String sql : statements) s.execute(sql);
                }
                Sql.assertSqlState("LL005", catchThrowable(connection::commit));
            } finally {
                connection.rollback();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "correction,UPDATE",
        "correction,DELETE",
        "correction,TRUNCATE",
        "correction_target,UPDATE",
        "correction_target,DELETE",
        "correction_target,TRUNCATE"
    })
    void ac_6_8_correctionAuditIsImmutableForAppAndOwner(String table, String operation) throws SQLException {
        validCorrection();
        String sql = switch (operation) {
            case "UPDATE" -> "UPDATE ledger." + table + " SET recorded_at=" + TIME;
            case "DELETE" -> "DELETE FROM ledger." + table;
            default -> "TRUNCATE ledger." + table + " CASCADE";
        };
        Sql.assertRejected("42501", f.db().app(), sql);
        Sql.assertRejected("LL004", f.db().migrator(), sql);
    }

    @Test
    void ac_6_14_readonlyCanOnlyReadCorrectionViews() throws SQLException {
        validCorrection();
        for (String table : new String[] {"correction", "correction_target"}) {
            assertThat(Sql.queryLong(f.db().readonly(), "SELECT count(*) FROM ledger.v_" + table))
                    .isEqualTo(1);
            Sql.assertRejected("42501", f.db().readonly(), "SELECT * FROM ledger." + table);
            Sql.assertRejected("42501", f.db().readonly(), "UPDATE ledger.v_" + table + " SET recorded_at=" + TIME);
            Sql.assertRejected("42501", f.db().readonly(), "DELETE FROM ledger.v_" + table);
            Sql.assertRejected(
                    "42501",
                    f.db().readonly(),
                    "INSERT INTO ledger.v_" + table + " (recorded_at) VALUES (" + TIME + ")");
        }
    }

    @Test
    void ac_6_5_appCanCorrectAmountAndFeeButCannotOverRefund() throws SQLException {
        seedBusiness(f.db());
        Sql.execute(f.db().app(), "UPDATE core.payment SET amount=12000,expected_fee=378,refunded_amount=11000");
        assertThat(Sql.queryLong(f.db().app(), "SELECT amount FROM core.payment"))
                .isEqualTo(12000);
        assertThat(Sql.queryLong(f.db().app(), "SELECT expected_fee FROM core.payment"))
                .isEqualTo(378);
        Sql.assertRejected("23514", f.db().app(), "UPDATE core.payment SET amount=10000");
        Sql.assertRejected("23514", f.db().app(), "UPDATE core.payment SET refunded_amount=12001");
        Sql.assertRejected("23514", f.db().app(), "UPDATE core.payment SET amount=49");
        Sql.assertRejected("23514", f.db().app(), "UPDATE core.payment SET expected_fee=-1");
    }

    @ParameterizedTest
    @CsvSource({
        "APPLIED,DEBIT,po_test",
        "APPLIED,CREDIT,NULL",
        "RETURNED,DEBIT,NULL",
        "RETURN_POSTED,CREDIT,NULL",
        "UNAPPLIED,CREDIT,po_test",
        "BAD,CREDIT,NULL"
    })
    void ac_6_10_bankStatusConstraintsRejectInvalidCombinations(String status, String direction, String batch)
            throws SQLException {
        seedBusiness(f.db());
        seedBank(f.db());
        Sql.assertRejected("23514", f.db().app(), bankLine(status, direction, batch));
    }

    @Test
    void ac_6_10_newBankStatesAndPaymentConflictHoldAreAccepted() throws SQLException {
        seedBusiness(f.db());
        seedBank(f.db());
        Sql.execute(
                f.db().app(),
                bankLine("APPLIED", "CREDIT", "po_test"),
                bankLine("APPLIED", "CREDIT", "po_test").replace("bank_APPLIED", "bank_APPLIED_split"),
                bankLine("RETURNED", "CREDIT", "NULL"),
                bankLine("RETURN_POSTED", "DEBIT", "NULL"),
                "UPDATE core.settlement_batch SET status='HELD',hold_reason='PAYMENT_STATE_CONFLICT'");
        assertThat(Sql.queryLong(f.db().readonly(), "SELECT count(*) FROM recon.v_bank_line"))
                .isEqualTo(4);
    }

    @Test
    void ac_6_8_upgradeFromP3PreservesFactsAndEnablesCorrections() throws SQLException {
        var old = TestDatabase.freshBlank();
        try {
            migrate(old, "19");
            seedBusiness(old);
            seedBank(old);
            Sql.execute(old.app(), bankLine("UNAPPLIED", "CREDIT", "NULL"), issue(null));
            var oldFixture = new LedgerFixture(old);
            String entry = oldFixture.s1().entryId();
            // Include a P3 refund fact, not just P2 payment and bank evidence.
            Sql.execute(old.app(), """
                    INSERT INTO core.refund (id,payment_id,merchant_refund_ref,amount,status,initial_result,
                        request_fingerprint,accepted_at,recorded_at)
                    VALUES ('rfd_00000000000000000000000001','pay_00000000000000000000000001',
                        'refund-test',50,'AWAITING_FUNDS','{}','fingerprint',%1$s,%1$s)
                    """.formatted(TIME));
            var before = oldFixture.balances();
            migrate(old, "latest");
            assertThat(oldFixture.balances()).isEqualTo(before);
            assertThat(oldFixture.count("core.refund")).isEqualTo(1);
            assertThat(oldFixture.count("recon.bank_line")).isEqualTo(1);
            var correction = oldFixture.inMoneyTransaction(l -> l.recordCorrection(
                    JdbcCorrectionTest.request(java.util.List.of(entry), java.util.List.of(), java.util.List.of())));
            Sql.execute(
                    old.app(),
                    "UPDATE recon.issue SET status='RESOLVED',resolution_correction_id='" + correction.correctionId()
                            + "'");
            assertThat(oldFixture.audit().check()).isEmpty();
            assertThat(oldFixture.balances()).isEqualTo(LedgerFixture.only());
        } finally {
            old.drop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"issue", "entry"})
    void ac_6_8_upgradeStopsOnLegacyCorrectionReferencesWithoutChangingThem(String source) throws SQLException {
        var old = TestDatabase.freshBlank();
        try {
            migrate(old, "19");
            if (source.equals("issue")) Sql.execute(old.app(), issue(COR));
            else Sql.execute(old.app(), entry("CORRECTION", null, 2, 10000), postings(MIRROR));
            assertThatThrownBy(() -> migrate(old, "latest")).hasMessageContaining("legacy correction references exist");
            assertThat(Sql.queryLong(
                            old.migrator(), "SELECT count(*) FROM flyway.flyway_schema_history WHERE version='20'"))
                    .isZero();
            assertThat(Sql.queryLong(
                            old.migrator(),
                            "SELECT count(*) FROM pg_tables WHERE schemaname='ledger' AND tablename='correction'"))
                    .isZero();
            assertThat(Sql.queryLong(
                            old.app(),
                            source.equals("issue")
                                    ? "SELECT count(*) FROM recon.issue WHERE resolution_correction_id IS NOT NULL"
                                    : "SELECT count(*) FROM ledger.journal_entry WHERE correction_id IS NOT NULL"))
                    .isEqualTo(1);
        } finally {
            old.drop();
        }
    }

    private static void migrate(TestDatabase.Handle db, String target) {
        var flyway = Flyway.configure()
                .dataSource(db.migrator())
                .defaultSchema("flyway")
                .schemas("flyway")
                .target(target)
                .load();
        flyway.migrate();
        flyway.validate();
    }

    private static String issue(@Nullable String correction) {
        return """
                INSERT INTO recon.issue (id,category,subject_type,subject_id,detected_by,status,
                    resolution_correction_id,recorded_at,updated_at)
                VALUES ('iss_00000000000000000000000001','AMOUNT_MISMATCH','payment','pay_test',
                    'INGESTION','OPEN',%s,%s,%s)
                """.formatted(correction == null ? "NULL" : "'" + correction + "'", TIME, TIME);
    }

    private static void seedBusiness(TestDatabase.Handle db) throws SQLException {
        Sql.execute(db.app(), """
                INSERT INTO io.inbox_event(event_id,event_type,api_version,livemode,payload,payload_sha256,
                    signed_at,occurred_at,received_at,recorded_at,status,next_attempt_at)
                VALUES ('evt_test','charge.succeeded','test',false,'{}'::bytea,encode(sha256('{}'::bytea),'hex'),
                    %1$s,%1$s,%1$s,%1$s,'RECEIVED',%1$s)
                """.formatted(TIME), """
                INSERT INTO core.payment(id,processor_payment_ref,amount,expected_fee,status,
                    source_event_id,occurred_at,received_at,recorded_at)
                VALUES ('pay_00000000000000000000000001','ch_test',10000,320,'CAPTURED','evt_test',%1$s,%1$s,%1$s)
                """.formatted(TIME), """
                INSERT INTO core.settlement_batch(batch_id,payout_amount,currency,automatic,method,status,
                    gross,fees,refunds,payout_failures_returned,net,source_event_id,occurred_at,recorded_at,updated_at)
                VALUES ('po_test',9680,'cad',true,'standard','CONFIRMED',10000,320,0,0,9680,'evt_test',%1$s,%1$s,%1$s)
                """.formatted(TIME));
    }

    private static void seedBank(TestDatabase.Handle db) throws SQLException {
        Sql.execute(db.app(), """
                INSERT INTO io.imported_file(id,kind,source_name,sha256,raw,statement_date,version,status,received_at,recorded_at)
                VALUES ('%s','BANK_STATEMENT','test.csv',encode(sha256('raw'::bytea),'hex'),'raw'::bytea,
                    '2026-10-25',1,'ACCEPTED',%s,%s)
                """.formatted(FILE, TIME, TIME), """
                INSERT INTO recon.bank_statement(file_id,account_ref,currency,opening_balance,closing_balance,
                    period_start,period_end,version,recorded_at)
                VALUES ('%s','SIM-CAD-001','CAD',0,9680,'2026-10-25','2026-10-25',1,%s)
                """.formatted(FILE, TIME));
    }

    private static String bankLine(String status, String direction, String batch) {
        return """
                INSERT INTO recon.bank_line(bank_txn_id,version,file_id,direction,currency,status,amount,
                    reference,booking_date,value_date,memo,match_status,matched_batch_id,recorded_at)
                VALUES ('%s',1,'%s','%s','CAD','BOOKED',9680,'po_test','2026-10-25','2026-10-25',
                    'synthetic','%s',%s,%s)
                """.formatted(
                "bank_" + status, FILE, direction, status, batch.equals("NULL") ? "NULL" : "'" + batch + "'", TIME);
    }
}
