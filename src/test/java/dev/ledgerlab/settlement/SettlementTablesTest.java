package dev.ledgerlab.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AC-1.13 extension: V6 constraints and permissions through real PostgreSQL roles. */
class SettlementTablesTest {
    private static final String TIME = "'2026-10-23T12:00:00Z'";
    private static final String CONFIRM = """
            UPDATE core.settlement_batch SET status = 'CONFIRMED', hold_reason = NULL,
                gross = 10000, fees = 320, refunds = 0, payout_failures_returned = 0, net = 9680
            """;
    private TestDatabase.Handle db;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.freshMigrated();
        Sql.execute(db.app(), """
                INSERT INTO io.inbox_event
                    (event_id, event_type, api_version, livemode, payload, payload_sha256,
                     signed_at, occurred_at, received_at, recorded_at, status, next_attempt_at)
                VALUES ('evt_batch', 'payout.created', 'test-version', false,
                    '{}'::bytea, encode(sha256('{}'::bytea), 'hex'),
                    %1$s, %1$s, %1$s, %1$s, 'RECEIVED', %1$s)
                """.formatted(TIME));
    }

    @AfterEach
    void tearDown() {
        db.drop();
    }

    private void seedBatch() throws SQLException {
        Sql.execute(db.app(), insert("io.processor_fetch"), insert("core.settlement_batch"));
    }

    private void seedEvidence() throws SQLException {
        seedBatch();
        Sql.execute(db.app(), CONFIRM, insert("core.settlement_batch_line"), insert("core.batch_transition"));
    }

    @Test
    void ac_1_13_migrationFromEmptyDatabaseCreatesMigratorOwnedTables() throws SQLException {
        // freshMigrated's template is built from the role bootstrap and all Flyway migrations.
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM flyway.flyway_schema_history WHERE version = '6' AND success
                """)).isEqualTo(1);
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM pg_tables WHERE tableowner = 'migrator' AND
                    ((schemaname = 'core' AND tablename IN
                        ('settlement_batch', 'settlement_batch_line', 'batch_transition'))
                    OR (schemaname = 'io' AND tablename = 'processor_fetch'))
                """)).isEqualTo(4);
    }

    @Test
    void ac_1_13_appCanInsertReadAndUpdateEveryMutableBatchColumn() throws SQLException {
        seedBatch();
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM core.settlement_batch WHERE status = 'ANNOUNCED'
                    AND settlement_date IS NULL AND arrival_date IS NULL AND lines_fetch_id IS NULL
                    AND gross IS NULL AND fees IS NULL AND refunds IS NULL
                    AND payout_failures_returned IS NULL AND net IS NULL AND hold_reason IS NULL
                """)).isEqualTo(1);
        Sql.execute(db.app(), """
                UPDATE core.settlement_batch SET status = 'HELD', hold_reason = 'LINES_NOT_READY',
                    settlement_date = '2026-10-23', arrival_date = '2026-10-26',
                    lines_fetch_id = (SELECT id FROM io.processor_fetch), updated_at = '2026-10-23T13:00:00Z'
                """, CONFIRM, insert("core.settlement_batch_line"), insert("core.batch_transition"));
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM core.settlement_batch WHERE status = 'CONFIRMED' AND hold_reason IS NULL
                    AND settlement_date = '2026-10-23' AND arrival_date = '2026-10-26'
                    AND lines_fetch_id IS NOT NULL AND updated_at = '2026-10-23T13:00:00Z'
                    AND gross = 10000 AND fees = 320 AND refunds = 0 AND payout_failures_returned = 0
                    AND net = payout_amount AND payout_amount = 9680
                """)).isEqualTo(1);
        for (String table :
                new String[] {"core.settlement_batch_line", "core.batch_transition", "io.processor_fetch"}) {
            assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM " + table)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "batch_id",
                "payout_amount",
                "currency",
                "automatic",
                "method",
                "source_event_id",
                "occurred_at",
                "recorded_at"
            })
    void ac_1_13_appCannotUpdateImmutableBatchColumns(String column) throws SQLException {
        seedBatch();
        Sql.assertRejected("42501", db.app(), "UPDATE core.settlement_batch SET " + column + " = " + column);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "core.settlement_batch",
                "core.settlement_batch_line",
                "core.batch_transition",
                "io.processor_fetch"
            })
    void ac_1_13_appCannotDeleteOrTruncateAnyNewTable(String table) throws SQLException {
        seedEvidence();
        Sql.assertRejected("42501", db.app(), "DELETE FROM " + table);
        Sql.assertRejected("42501", db.app(), "TRUNCATE " + table + " CASCADE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"core.settlement_batch_line", "core.batch_transition", "io.processor_fetch"})
    void ac_1_13_appendOnlyEvidenceRejectsAppUpdatesAndOwnerRewrites(String table) throws SQLException {
        seedEvidence();
        String update = "UPDATE " + table + " SET recorded_at = recorded_at";
        Sql.assertRejected("42501", db.app(), update);
        Sql.assertRejected("LL004", db.migrator(), update);
        Sql.assertRejected("LL004", db.migrator(), "DELETE FROM " + table);
        Sql.assertRejected("LL004", db.migrator(), "TRUNCATE " + table + " CASCADE");
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM " + table)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "core.settlement_batch",
                "core.settlement_batch_line",
                "core.batch_transition",
                "io.processor_fetch"
            })
    void ac_1_13_readonlyCannotAccessBaseTables(String table) throws SQLException {
        seedEvidence();
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM " + table);
        Sql.assertRejected("42501", db.readonly(), insert(table));
        Sql.assertRejected("42501", db.readonly(), "UPDATE " + table + " SET recorded_at = recorded_at");
        Sql.assertRejected("42501", db.readonly(), "DELETE FROM " + table);
        Sql.assertRejected("42501", db.readonly(), "TRUNCATE " + table + " CASCADE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"settlement_batch", "settlement_batch_line", "batch_transition"})
    void ac_1_13_readonlyReadsViewsButCannotWriteThem(String table) throws SQLException {
        seedEvidence();
        String view = "core.v_" + table;
        // SELECT * exercises every explicit column, not just permission to count rows.
        Sql.inTransaction(db.readonly(), connection -> {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT * FROM " + view)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("recorded_at")).isNotBlank();
                assertThat(rows.next()).isFalse();
            }
        });
        Sql.assertRejected(
                "42501",
                db.readonly(),
                insert("core." + table).replace("INSERT INTO core." + table, "INSERT INTO " + view));
        Sql.assertRejected("42501", db.readonly(), "UPDATE " + view + " SET recorded_at = recorded_at");
        Sql.assertRejected("42501", db.readonly(), "DELETE FROM " + view);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "batch_id = 'bad'",
                "batch_id = 'po_'",
                "status = 'UNKNOWN'",
                "status = 'HELD'",
                "hold_reason = 'UNKNOWN_PAYMENT'",
                "status = 'HELD', hold_reason = 'UNKNOWN'",
                "status = 'HELD', hold_reason = ''",
                "gross = 10000, fees = 320, refunds = 0, payout_failures_returned = 0, net = 9681"
            })
    void ac_1_13_batchChecksRejectInvalidValues(String assignment) throws SQLException {
        seedBatch();
        Sql.assertRejected("23514", db.migrator(), "UPDATE core.settlement_batch SET " + assignment);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UNKNOWN_PAYMENT",
                "AMOUNT_MISMATCH",
                "UNSUPPORTED_LINE",
                "UNSUPPORTED_PAYOUT",
                "FEE_EXCEEDS_GROSS",
                "LINE_IN_OTHER_BATCH",
                "TOTAL_MISMATCH",
                "LINES_NOT_READY"
            })
    void ac_1_13_allHoldReasonsCanBeRecordedAndCleared(String reason) throws SQLException {
        seedBatch();
        Sql.execute(db.app(), "UPDATE core.settlement_batch SET status = 'HELD', hold_reason = '" + reason + "'");
        Sql.assertRejected("23514", db.app(), "UPDATE core.settlement_batch SET status = 'RECEIVED'");
        Sql.execute(db.app(), "UPDATE core.settlement_batch SET status = 'RECEIVED', hold_reason = NULL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "BANKED", "PAYOUT_FAILED"})
    void ac_1_13_confirmedAndLaterStatesRequireCompletePositiveMatchingTotals(String status) throws SQLException {
        seedBatch();
        Sql.assertRejected("23514", db.app(), "UPDATE core.settlement_batch SET status = '" + status + "'");
        Sql.execute(db.app(), CONFIRM, "UPDATE core.settlement_batch SET status = '" + status + "'");
        for (String column : new String[] {"gross", "fees", "refunds", "payout_failures_returned", "net"}) {
            Sql.assertRejected("23514", db.app(), "UPDATE core.settlement_batch SET " + column + " = NULL");
        }
        Sql.assertRejected("23514", db.app(), "UPDATE core.settlement_batch SET net = 9679, fees = 321");
        Sql.assertRejected(
                "23514", db.migrator(), "UPDATE core.settlement_batch SET payout_amount = 0, net = 0, gross = 320");
        Sql.assertRejected(
                "23514", db.migrator(), "UPDATE core.settlement_batch SET payout_amount = -1, net = -1, gross = 319");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payout_amount", "gross", "fees", "refunds", "payout_failures_returned", "net"})
    void ac_1_13_summaryAmountsEnforceSafeIntegerBounds(String column) throws SQLException {
        seedBatch();
        // Other totals remain NULL, isolating each column's range constraint from the formula.
        boolean signed = column.equals("payout_amount") || column.equals("net");
        long min = signed ? -9007199254740991L : 0;
        for (long valid : new long[] {min, 0, 9007199254740991L}) {
            Sql.execute(db.migrator(), "UPDATE core.settlement_batch SET " + column + " = " + valid);
        }
        for (long invalid : new long[] {min - 1, 9007199254740992L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            Sql.assertRejected("23514", db.migrator(), "UPDATE core.settlement_batch SET " + column + " = " + invalid);
        }
    }

    @Test
    void ac_1_13_formulaIncludesRefundsAndReturnedPayoutsAndAllowsHeldMismatches() throws SQLException {
        seedBatch();
        Sql.execute(db.app(), """
                UPDATE core.settlement_batch SET status = 'HELD', hold_reason = 'TOTAL_MISMATCH',
                    gross = 5000, fees = 175, refunds = 2000, payout_failures_returned = 100, net = 2925
                """);
        Sql.assertRejected("23514", db.app(), "UPDATE core.settlement_batch SET net = 2825");
        // Example H: no payments or fees, only a returned payout.
        Sql.execute(db.app(), """
                UPDATE core.settlement_batch SET status = 'CONFIRMED', hold_reason = NULL,
                    gross = 0, fees = 0, refunds = 0, payout_failures_returned = 9680, net = 9680
                """);
    }

    @Test
    void ac_1_13_unsupportedPayoutFactsRemainAvailableForIsolation() throws SQLException {
        Sql.execute(
                db.app(),
                insert(
                        "core.settlement_batch",
                        "payout_amount",
                        "-9680",
                        "currency",
                        "'usd'",
                        "automatic",
                        "false",
                        "method",
                        "'instant'",
                        "status",
                        "'HELD'",
                        "hold_reason",
                        "'UNSUPPORTED_PAYOUT'"));
        assertThat(Sql.queryLong(db.app(), "SELECT payout_amount FROM core.settlement_batch"))
                .isEqualTo(-9680);
    }

    @Test
    void ac_1_13_batchKeysAndEvidenceReferencesAreEnforced() throws SQLException {
        seedBatch();
        Sql.assertRejected("23505", db.app(), insert("core.settlement_batch"));
        Sql.assertRejected(
                "23503",
                db.app(),
                insert("core.settlement_batch", "batch_id", "'po_other'", "source_event_id", "'evt_missing'"));
        Sql.assertRejected("23503", db.app(), "UPDATE core.settlement_batch SET lines_fetch_id = 999999");
        Sql.assertRejected("23503", db.app(), insert("io.processor_fetch", "event_id", "'evt_missing'"));
        Sql.assertRejected("23503", db.app(), insert("core.batch_transition", "subject_id", "'po_missing'"));
        Sql.assertRejected("23503", db.app(), insert("core.settlement_batch_line", "batch_id", "'po_missing'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANNOUNCED", "RECEIVED", "HELD", "BANKED", "PAYOUT_FAILED"})
    void ac_1_13_linesCanOnlyBeInsertedWhileBatchIsConfirmed(String status) throws SQLException {
        seedBatch();
        Sql.execute(
                db.app(),
                CONFIRM,
                "UPDATE core.settlement_batch SET status = '" + status + "', hold_reason = "
                        + (status.equals("HELD") ? "'UNKNOWN_PAYMENT'" : "NULL"));
        Sql.assertRejected("23514", db.app(), insert("core.settlement_batch_line"));
        Sql.execute(
                db.app(),
                CONFIRM,
                insert("core.settlement_batch_line"),
                "UPDATE core.settlement_batch SET status = 'BANKED'");
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM core.settlement_batch_line"))
                .isEqualTo(1);
    }

    @Test
    void ac_1_13_lineKeysAreUniqueAcrossBatches() throws SQLException {
        seedEvidence();
        Sql.assertRejected("23505", db.app(), insert("core.settlement_batch_line", "balance_txn_id", "'txn_other'"));
        Sql.execute(db.app(), insert("core.settlement_batch", "batch_id", "'po_other'"), CONFIRM);
        Sql.assertRejected("23505", db.app(), insert("core.settlement_batch_line", "batch_id", "'po_other'"));
        Sql.execute(
                db.app(),
                insert("core.settlement_batch_line", "batch_id", "'po_other'", "balance_txn_id", "'txn_other'"));
    }

    @Test
    void ac_1_13_lineChecksRejectInvalidValues() throws SQLException {
        seedBatch();
        Sql.execute(db.app(), CONFIRM);
        String[][] invalid = {
            {"line_no", "0"},
            {"line_no", "-1"},
            {"line_type", "'DISPUTE'"},
            {"balance_txn_id", "'bad'"},
            {"balance_txn_id", "'txn_'"},
            {"gross", "0"},
            {"gross", "100000000"},
            {"gross", "-1"},
            {"fee", "-1"},
            {"fee", "100000000"},
            {"net", "100000000"},
            {"net", "-100000000"},
            {"net", "9681"},
            {"gross", "-9223372036854775808"},
            {"line_type", "'REFUND'"},
            {"line_type", "'REFUND'", "gross", "-100000000", "fee", "0", "net", "-100000000"}
        };
        for (String[] fields : invalid) {
            Sql.assertRejected("23514", db.app(), insert("core.settlement_batch_line", fields));
        }
    }

    @Test
    void ac_1_13_lineAmountBoundariesAndAllTypesAreAccepted() throws SQLException {
        seedBatch();
        Sql.execute(db.app(), CONFIRM);
        int lineNo = 0;
        for (String type : new String[] {"PAYMENT", "REFUND", "PAYOUT_FAILURE"}) {
            for (long amount : new long[] {1, 99999999}) {
                lineNo++;
                String signed = Long.toString(type.equals("REFUND") ? -amount : amount);
                Sql.execute(
                        db.app(),
                        insert(
                                "core.settlement_batch_line",
                                "line_no",
                                Integer.toString(lineNo),
                                "balance_txn_id",
                                "'txn_" + lineNo + "'",
                                "line_type",
                                "'" + type + "'",
                                "gross",
                                signed,
                                "fee",
                                "0",
                                "net",
                                signed));
            }
        }
        Sql.execute(
                db.app(),
                insert(
                        "core.settlement_batch_line",
                        "line_no",
                        "7",
                        "balance_txn_id",
                        "'txn_zero_net'",
                        "gross",
                        "99999999",
                        "fee",
                        "99999999",
                        "net",
                        "0"));
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM core.settlement_batch_line"))
                .isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANNOUNCED", "RECEIVED", "CONFIRMED", "HELD", "PAYOUT_FAILED", "BANKED"})
    void ac_1_13_transitionStatusesAcceptOnlyBatchStates(String status) throws SQLException {
        seedBatch();
        Sql.execute(
                db.app(),
                insert("core.batch_transition", "from_status", "'" + status + "'", "to_status", "'" + status + "'"));
        for (String column : new String[] {"from_status", "to_status"}) {
            Sql.assertRejected("23514", db.app(), insert("core.batch_transition", column, "'UNKNOWN'"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io.processor_fetch", "core.batch_transition"})
    void ac_1_13_evidenceIdentityIsGeneratedAndUnique(String table) throws SQLException {
        seedBatch();
        if (table.equals("core.batch_transition")) {
            Sql.execute(db.app(), insert(table));
        }
        Sql.execute(db.app(), insert(table));
        assertThat(Sql.queryLong(db.app(), "SELECT count(DISTINCT id) FROM " + table))
                .isEqualTo(2);
        Sql.assertRejected("428C9", db.app(), insert(table, "id", "999"));
        Sql.assertRejected(
                "23505", db.migrator(), "INSERT INTO " + table + " OVERRIDING SYSTEM VALUE SELECT * FROM " + table);
    }

    @Test
    void ac_1_13_fetchPreservesRawUtf8PagesAndIncompleteSnapshots() throws SQLException {
        for (String purpose : new String[] {"BATCH_LINES", "PAYOUT", "RECON_SNAPSHOT"}) {
            String body = "'[ {\"data\":[], \"label\":\"合成\"}, {\"has_more\":true} ]'";
            Sql.execute(
                    db.app(),
                    insert(
                            "io.processor_fetch",
                            "event_id",
                            "NULL",
                            "purpose",
                            "'" + purpose + "'",
                            "response_body",
                            body,
                            "response_sha256",
                            "encode(sha256(convert_to(" + body + ", 'UTF8')), 'hex')",
                            "complete",
                            "false"));
        }
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM io.processor_fetch WHERE event_id IS NULL AND NOT complete
                    AND response_body = '[ {"data":[], "label":"合成"}, {"has_more":true} ]'
                """)).isEqualTo(3);
        Sql.assertRejected("23514", db.app(), insert("io.processor_fetch", "purpose", "'UNKNOWN'"));
        Sql.assertRejected("23514", db.app(), insert("io.processor_fetch", "response_sha256", "repeat('0', 64)"));
        Sql.assertRejected("23514", db.app(), insert("io.processor_fetch", "response_sha256", "'bad'"));
        Sql.assertRejected("23514", db.app(), insert("io.processor_fetch", "response_body", "'{} '"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "core.settlement_batch",
                "core.settlement_batch_line",
                "core.batch_transition",
                "io.processor_fetch"
            })
    void ac_1_13_requiredColumnsRejectNull(String table) throws SQLException {
        seedBatch();
        Sql.execute(db.app(), CONFIRM);
        String columns = switch (table) {
            case "core.settlement_batch" ->
                "batch_id payout_amount currency automatic method status source_event_id occurred_at recorded_at updated_at";
            case "core.settlement_batch_line" ->
                "batch_id line_no line_type processor_ref balance_txn_id gross fee net occurred_at recorded_at";
            case "core.batch_transition" -> "subject_id to_status evidence_type evidence_id occurred_at recorded_at";
            case "io.processor_fetch" ->
                "purpose request response_body response_sha256 complete fetched_at recorded_at";
            default -> throw new IllegalArgumentException(table);
        };
        for (String column : columns.split(" ")) {
            Sql.assertRejected("23502", db.app(), insert(table, column, "NULL"));
        }
    }

    /** SQL literals only: defaults describe a valid synthetic row; overrides isolate each constraint. */
    private static String insert(String table, String... overrides) {
        String[] fields = switch (table) {
            case "core.settlement_batch" ->
                new String[] {
                    "batch_id",
                    "'po_test'",
                    "payout_amount",
                    "9680",
                    "currency",
                    "'cad'",
                    "automatic",
                    "true",
                    "method",
                    "'standard'",
                    "status",
                    "'ANNOUNCED'",
                    "source_event_id",
                    "'evt_batch'",
                    "occurred_at",
                    TIME,
                    "recorded_at",
                    TIME,
                    "updated_at",
                    TIME
                };
            case "core.settlement_batch_line" ->
                new String[] {
                    "batch_id",
                    "'po_test'",
                    "line_no",
                    "1",
                    "line_type",
                    "'PAYMENT'",
                    "processor_ref",
                    "'ch_test'",
                    "balance_txn_id",
                    "'txn_test'",
                    "gross",
                    "10000",
                    "fee",
                    "320",
                    "net",
                    "9680",
                    "occurred_at",
                    TIME,
                    "recorded_at",
                    TIME
                };
            case "core.batch_transition" ->
                new String[] {
                    "subject_id",
                    "'po_test'",
                    "from_status",
                    "NULL",
                    "to_status",
                    "'ANNOUNCED'",
                    "evidence_type",
                    "'WEBHOOK'",
                    "evidence_id",
                    "'evt_batch'",
                    "occurred_at",
                    TIME,
                    "recorded_at",
                    TIME
                };
            case "io.processor_fetch" ->
                new String[] {
                    "event_id",
                    "'evt_batch'",
                    "purpose",
                    "'BATCH_LINES'",
                    "request",
                    "'{\"path\":\"/v1/balance_transactions\",\"payout\":\"po_test\"}'::jsonb",
                    "response_body",
                    "'{}'",
                    "response_sha256",
                    "encode(sha256(convert_to('{}', 'UTF8')), 'hex')",
                    "complete",
                    "true",
                    "fetched_at",
                    TIME,
                    "recorded_at",
                    TIME
                };
            default -> throw new IllegalArgumentException(table);
        };
        Map<String, String> values = new LinkedHashMap<>();
        for (String[] pairs : new String[][] {fields, overrides}) {
            for (int i = 0; i < pairs.length; i += 2) {
                values.put(pairs[i], pairs[i + 1]);
            }
        }
        return "INSERT INTO " + table + " (" + String.join(", ", values.keySet()) + ") VALUES ("
                + String.join(", ", values.values()) + ")";
    }
}
