package dev.ledgerlab.payments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T03 schema prerequisites for AC-2.9–2.15 and AC-3; not end-to-end refund acceptance. */
class RefundTablesTest {
    private static final String TIME = "'2026-10-24T12:00:00Z'";
    private static final String PAYMENT = "'pay_01JB0000000000000000000001'";
    private static final String REFUND = "'rfd_01JB0000000000000000000001'";
    private static final String COMMAND = "'cmd_01JB0000000000000000000001'";
    private static final String[] TABLES = {
        "core.refund", "core.refund_transition", "io.outbound_command", "io.outbound_attempt", "io.idempotency_record"
    };
    private static final String EVENT = """
            INSERT INTO io.inbox_event(event_id,event_type,api_version,livemode,payload,payload_sha256,
                signed_at,occurred_at,received_at,recorded_at,status,next_attempt_at)
            VALUES ('evt_refund','charge.succeeded','test',false,'{}'::bytea,encode(sha256('{}'::bytea),'hex'),
                %1$s,%1$s,%1$s,%1$s,'RECEIVED',%1$s)
            """.formatted(TIME);
    private static final String PAYMENT_ROW = """
            INSERT INTO core.payment(id,processor_payment_ref,amount,expected_fee,status,
                source_event_id,occurred_at,received_at,recorded_at)
            VALUES (%s,'ch_refund',10000,320,'CAPTURED','evt_refund',%s,%s,%s)
            """.formatted(PAYMENT, TIME, TIME, TIME);
    private static final String BATCH_ROW = """
            INSERT INTO core.settlement_batch(batch_id,payout_amount,currency,automatic,method,status,
                gross,fees,refunds,payout_failures_returned,net,source_event_id,occurred_at,recorded_at,updated_at)
            VALUES ('po_refund',9680,'cad',true,'standard','CONFIRMED',10000,320,0,0,9680,
                'evt_refund',%s,%s,%s)
            """.formatted(TIME, TIME, TIME);

    private TestDatabase.Handle db;

    @BeforeEach
    void setup() throws SQLException {
        db = TestDatabase.freshMigrated();
        seedP2(db);
    }

    @AfterEach
    void cleanup() {
        db.drop();
    }

    private static void seedP2(TestDatabase.Handle database) throws SQLException {
        Sql.execute(database.app(), EVENT, PAYMENT_ROW, BATCH_ROW);
    }

    private void seedRefundAndCommand() throws SQLException {
        Sql.execute(db.app(), insert("core.refund"), insert("io.outbound_command"));
    }

    @Test
    void ac_1_13_emptyDatabaseMigrationCreatesOwnedTablesViewsAndClaimIndex() throws SQLException {
        // freshMigrated is cloned from a blank bootstrap database migrated through every version.
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM flyway.flyway_schema_history WHERE version='15' AND success
                """)).isEqualTo(1);
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM pg_tables WHERE tableowner='migrator' AND
                    ((schemaname='core' AND tablename IN ('refund','refund_transition')) OR
                     (schemaname='io' AND tablename IN ('outbound_command','outbound_attempt','idempotency_record')))
                """)).isEqualTo(5);
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM pg_views WHERE viewowner='migrator' AND schemaname='core'
                    AND viewname IN ('v_refund','v_refund_transition')
                """)).isEqualTo(2);
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM pg_indexes WHERE schemaname='io' AND tablename='outbound_command'
                    AND indexdef LIKE '%(status, next_attempt_at, created_at)%'
                """)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12", "14"})
    void ac_1_13_upgradePreservesExistingP2FactsAndAcceptsUnknownRefund(String version) throws SQLException {
        var old = TestDatabase.freshBlank();
        try {
            Flyway.configure()
                    .dataSource(old.migrator())
                    .defaultSchema("flyway")
                    .schemas("flyway")
                    .target(version)
                    .load()
                    .migrate();
            seedP2(old);
            Sql.execute(old.app(), """
                    UPDATE io.inbox_event SET status='REJECTED',result='INVALID_AMOUNT'
                    """);
            if (version.equals("14")) {
                Sql.execute(old.app(), history("REJECTED", "INVALID_AMOUNT"));
            }
            var flyway = Flyway.configure()
                    .dataSource(old.migrator())
                    .defaultSchema("flyway")
                    .schemas("flyway")
                    .load();
            flyway.migrate();
            flyway.validate();
            assertThat(Sql.queryLong(old.app(), "SELECT count(*) FROM core.payment WHERE amount=10000"))
                    .isEqualTo(1);
            assertThat(Sql.queryLong(old.app(), "SELECT count(*) FROM core.settlement_batch WHERE net=9680"))
                    .isEqualTo(1);
            assertThat(Sql.queryLong(old.app(), """
                    SELECT count(*) FROM io.inbox_status_history
                    WHERE to_status='REJECTED' AND result='INVALID_AMOUNT'
                    """)).isEqualTo(1);
            Sql.execute(
                    old.app(),
                    insert("core.refund"),
                    insert("io.outbound_command"),
                    insert("io.outbound_attempt"),
                    insert("io.idempotency_record"),
                    insert("core.refund_transition"),
                    "UPDATE io.inbox_event SET result='UNKNOWN_REFUND'",
                    history("REJECTED", "UNKNOWN_REFUND"));
            assertThat(Sql.queryLong(old.readonly(), "SELECT count(*) FROM core.v_refund"))
                    .isEqualTo(1);
        } finally {
            old.drop();
        }
    }

    @Test
    void ac_1_13_appCanUseEveryMutableColumnAndReadonlyCanReadViews() throws SQLException {
        seedRefundAndCommand();
        Sql.execute(
                db.app(),
                insert("core.refund_transition"),
                insert("io.outbound_attempt"),
                insert("io.idempotency_record"),
                """
                UPDATE core.refund SET status='CANCELLED',processor_refund_ref='re_fixture',
                    failure_reason='retained evidence',cancel_reason='operator request',
                    submitted_at=%1$s,resolved_at=%1$s,batch_id='po_refund'
                """.formatted(TIME),
                """
                UPDATE io.outbound_command SET status='IN_FLIGHT',attempts=1,next_attempt_at=%1$s,
                    lease_owner='worker',lease_until=%1$s,lease_epoch=2,first_sent_at=%1$s,
                    external_ref='re_fixture',last_outcome='PENDING'
                """.formatted(TIME),
                """
                UPDATE io.idempotency_record SET state='COMPLETED',response_status=201,
                    response_body='{"status":"AWAITING_FUNDS"}',locked_until=%s
                """.formatted(TIME));
        for (String table : TABLES) {
            assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM " + table)).isEqualTo(1);
        }
        assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM core.v_refund WHERE status='CANCELLED'"))
                .isEqualTo(1);
        assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM core.v_refund_transition"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "core.refund|id,payment_id,merchant_refund_ref,amount,queue_seq,initial_result,request_fingerprint,accepted_at,recorded_at",
                "io.outbound_command|id,kind,subject_id,request_id,created_at,recorded_at",
                "io.idempotency_record|client_id,idem_key,request_fingerprint,created_at,expires_at,recorded_at"
            })
    void ac_1_13_appCannotRewriteImmutableColumns(String table, String columns) throws SQLException {
        seedRefundAndCommand();
        Sql.execute(db.app(), insert("io.idempotency_record"));
        for (String column : columns.split(",")) {
            Sql.assertRejected(
                    "42501",
                    db.app(),
                    "UPDATE " + table + " SET " + column + "=" + (column.equals("queue_seq") ? "DEFAULT" : column));
        }
    }

    @Test
    void ac_1_13_onlyApiCacheCanBeDeletedAndReadonlyCannotAccessBaseTablesOrWriteViews() throws SQLException {
        seedRefundAndCommand();
        Sql.execute(
                db.app(),
                insert("core.refund_transition"),
                insert("io.outbound_attempt"),
                insert("io.idempotency_record"));
        for (String table : TABLES) {
            Sql.assertRejected("42501", db.app(), "TRUNCATE " + table + " CASCADE");
            if (!table.equals("io.idempotency_record")) {
                Sql.assertRejected("42501", db.app(), "DELETE FROM " + table);
            }
            Sql.assertRejected("42501", db.readonly(), "SELECT * FROM " + table);
            Sql.assertRejected("42501", db.readonly(), insert(table));
            Sql.assertRejected("42501", db.readonly(), "UPDATE " + table + " SET recorded_at=recorded_at");
            Sql.assertRejected("42501", db.readonly(), "DELETE FROM " + table);
            Sql.assertRejected("42501", db.readonly(), "TRUNCATE " + table + " CASCADE");
        }
        for (String view : new String[] {"core.v_refund", "core.v_refund_transition"}) {
            Sql.assertRejected("42501", db.readonly(), "UPDATE " + view + " SET recorded_at=recorded_at");
            Sql.assertRejected("42501", db.readonly(), "DELETE FROM " + view);
            String table = view.replace("core.v_", "core.");
            Sql.assertRejected(
                    "42501", db.readonly(), insert(table).replace("INSERT INTO " + table, "INSERT INTO " + view));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"core.refund_transition", "io.outbound_attempt"})
    void ac_1_13_appendOnlyEvidenceRejectsAppAndOwnerRewrites(String table) throws SQLException {
        seedRefundAndCommand();
        Sql.execute(db.app(), insert(table));
        for (String sql : new String[] {
            "UPDATE " + table + " SET recorded_at=recorded_at", "DELETE FROM " + table, "TRUNCATE " + table
        }) {
            Sql.assertRejected("42501", db.app(), sql);
            Sql.assertRejected("LL004", db.migrator(), sql);
        }
    }

    @Test
    void ac_3_10_firstSentAtCanBeWrittenOnceButNeverResetOrMoved() throws SQLException {
        seedRefundAndCommand();
        Sql.execute(
                db.app(),
                "UPDATE io.outbound_command SET first_sent_at=NULL",
                "UPDATE io.outbound_command SET first_sent_at=" + TIME,
                "UPDATE io.outbound_command SET first_sent_at=" + TIME + ",attempts=1");
        for (var role : new javax.sql.DataSource[] {db.app(), db.migrator()}) {
            Sql.assertRejected("23514", role, "UPDATE io.outbound_command SET first_sent_at=NULL");
            Sql.assertRejected(
                    "23514", role, "UPDATE io.outbound_command SET first_sent_at=first_sent_at+interval '1 second'");
            Sql.assertRejected(
                    "23514", role, "UPDATE io.outbound_command SET first_sent_at=first_sent_at-interval '1 second'");
        }
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM io.outbound_command WHERE first_sent_at=" + TIME))
                .isEqualTo(1);
    }

    @Test
    void ac_2_14_expiredApiCacheCanBeRemovedWithoutLosingPermanentRefundResult() throws SQLException {
        seedRefundAndCommand();
        Sql.execute(
                db.app(),
                insert("io.idempotency_record"),
                "UPDATE core.refund SET status='SUCCEEDED',resolved_at=" + TIME,
                "DELETE FROM io.idempotency_record WHERE expires_at < '2026-10-25T13:00Z'");
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM io.idempotency_record"))
                .isZero();
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM core.refund WHERE status='SUCCEEDED'
                    AND initial_result='{"status":201,"body":{"status":"AWAITING_FUNDS"}}'::jsonb
                """)).isEqualTo(1);
        Sql.assertRejected("23505", db.app(), insert("core.refund", Map.of("id", "'rfd_01JB0000000000000000000002'")));
    }

    @Test
    void ac_2_9_refundExternalIdentityIsUniqueButNullIsAllowed() throws SQLException {
        Sql.execute(
                db.app(),
                insert("core.refund"),
                insert(
                        "core.refund",
                        Map.of("id", "'rfd_01JB0000000000000000000002'", "merchant_refund_ref", "'MR-2'")));
        Sql.execute(db.app(), "UPDATE core.refund SET processor_refund_ref='re_same' WHERE id=" + REFUND);
        Sql.assertRejected(
                "23505", db.app(), "UPDATE core.refund SET processor_refund_ref='re_same' WHERE id<>" + REFUND);
    }

    @Test
    void ac_3_7_queueSequenceIsGeneratedUniqueAndIndependentOfEqualTimestamps() throws SQLException {
        Sql.execute(
                db.app(),
                insert("core.refund"),
                insert(
                        "core.refund",
                        Map.of("id", "'rfd_01JB0000000000000000000002'", "merchant_refund_ref", "'MR-2'")));
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM core.refund first_refund JOIN core.refund second_refund
                    ON first_refund.queue_seq < second_refund.queue_seq
                WHERE first_refund.merchant_refund_ref='MR-1' AND second_refund.merchant_refund_ref='MR-2'
                    AND first_refund.accepted_at=second_refund.accepted_at
                """)).isEqualTo(1);
        String duplicateSequence = insert(
                "core.refund",
                Map.of(
                        "id",
                        "'rfd_01JB0000000000000000000003'",
                        "merchant_refund_ref",
                        "'MR-3'",
                        "queue_seq",
                        "(SELECT queue_seq FROM core.refund WHERE merchant_refund_ref='MR-1')"));
        Sql.assertRejected("428C9", db.app(), duplicateSequence);
        Sql.assertRejected(
                "23505", db.migrator(), duplicateSequence.replace(") VALUES (", ") OVERRIDING SYSTEM VALUE VALUES ("));
    }

    @Test
    void ac_2_10_allPrimaryAndRequestKeysPreventDuplicatesAndAllowIndependentIdentities() throws SQLException {
        seedRefundAndCommand();
        Sql.execute(
                db.app(),
                insert("core.refund_transition"),
                insert("io.outbound_attempt"),
                insert("io.idempotency_record"));
        for (String table : TABLES) {
            Sql.assertRejected(
                    "23505", db.migrator(), "INSERT INTO " + table + " OVERRIDING SYSTEM VALUE SELECT * FROM " + table);
        }
        Sql.assertRejected(
                "23505", db.app(), insert("io.outbound_command", Map.of("id", "'cmd_01JB0000000000000000000002'")));
        Sql.execute(
                db.app(),
                PAYMENT_ROW.replace(PAYMENT, "'pay_01JB0000000000000000000002'").replace("ch_refund", "ch_other"),
                insert(
                        "core.refund",
                        Map.of(
                                "id",
                                "'rfd_01JB0000000000000000000002'",
                                "payment_id",
                                "'pay_01JB0000000000000000000002'")),
                insert(
                        "io.outbound_command",
                        Map.of("id", "'cmd_01JB0000000000000000000002'", "request_id", "'request-2'")),
                insert("io.outbound_attempt", Map.of("attempt_no", "2")),
                insert("io.outbound_attempt", Map.of("command_id", "'cmd_01JB0000000000000000000002'")),
                insert("io.idempotency_record", Map.of("client_id", "'other-client'")),
                insert("io.idempotency_record", Map.of("idem_key", "'other-key'")));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "core.refund|id|'pay_01JB0000000000000000000002'|23514",
                "core.refund|payment_id|'pay_missing'|23503",
                "core.refund|batch_id|'po_missing'|23503",
                "core.refund|merchant_refund_ref|''|23514",
                "core.refund|merchant_refund_ref|E' \\t\\n'|23514",
                "core.refund|merchant_refund_ref|repeat('x',65)|23514",
                "core.refund|amount|0|23514",
                "core.refund|amount|-1|23514",
                "core.refund|amount|100000000|23514",
                "core.refund|status|'REQUESTED'|23514",
                "core.refund|status|'INVALID'|23514",
                "core.refund|processor_refund_ref|'reXinvalid'|23514",
                "core.refund|resolved_at|'2026-10-24T12:00Z'|23514",
                "core.refund_transition|subject_id|'rfd_missing'|23503",
                "core.refund_transition|from_status|'REQUESTED'|23514",
                "core.refund_transition|to_status|'REQUESTED'|23514",
                "core.refund_transition|evidence_source|'INVALID'|23514",
                "io.outbound_command|id|'rfd_01JB0000000000000000000001'|23514",
                "io.outbound_command|kind|'SUBMIT_PAYOUT'|23514",
                "io.outbound_command|kind|'QUERY_REFUND'|23514",
                "io.outbound_command|status|'FAILED'|23514",
                "io.outbound_command|attempts|-1|23514",
                "io.outbound_command|lease_epoch|-1|23514",
                "io.outbound_attempt|command_id|'cmd_missing'|23503",
                "io.outbound_attempt|attempt_no|0|23514",
                "io.outbound_attempt|lease_epoch|-1|23514",
                "io.outbound_attempt|response_status|99|23514",
                "io.outbound_attempt|response_status|600|23514",
                "io.idempotency_record|state|'FAILED'|23514",
                "io.idempotency_record|response_status|99|23514",
                "io.idempotency_record|response_status|600|23514"
            })
    void ac_1_13_constraintsRejectInvalidRows(String table, String column, String value, String sqlState)
            throws SQLException {
        if (table.equals("core.refund_transition")) {
            Sql.execute(db.app(), insert("core.refund"));
        } else if (table.equals("io.outbound_attempt")) {
            seedRefundAndCommand();
        }
        Sql.assertRejected(sqlState, db.app(), insert(table, Map.of(column, value)));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "core.refund|id,payment_id,merchant_refund_ref,amount,status,initial_result,request_fingerprint,accepted_at,recorded_at",
                "core.refund_transition|subject_id,to_status,evidence_type,evidence_id,evidence_source,evidence_ref,occurred_at,recorded_at",
                "io.outbound_command|id,kind,subject_id,request_id,status,attempts,next_attempt_at,lease_epoch,created_at,recorded_at",
                "io.outbound_attempt|command_id,attempt_no,lease_epoch,sent_at,request_summary,finished_at,recorded_at",
                "io.idempotency_record|client_id,idem_key,request_fingerprint,state,locked_until,created_at,expires_at,recorded_at"
            })
    void ac_1_13_requiredFieldsCannotBeNull(String table, String columns) throws SQLException {
        if (!table.equals("core.refund") && !table.equals("io.outbound_command")) {
            seedRefundAndCommand();
        }
        for (String column : columns.split(",")) {
            Sql.assertRejected("23502", db.app(), insert(table, Map.of(column, "NULL")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELLED"})
    void ac_3_9_terminalStatesRequireResolutionAndCancellationReason(String status) throws SQLException {
        Sql.assertRejected("23514", db.app(), insert("core.refund", Map.of("status", "'" + status + "'")));
        var changes = new LinkedHashMap<>(Map.of("status", "'" + status + "'", "resolved_at", TIME));
        if (status.equals("CANCELLED")) {
            for (String reason : new String[] {"NULL", "''", "E' \\t\\n'"}) {
                changes.put("cancel_reason", reason);
                Sql.assertRejected("23514", db.app(), insert("core.refund", changes));
            }
            changes.put("cancel_reason", "'customer requested cancellation'");
        }
        Sql.execute(db.app(), insert("core.refund", changes));
        Sql.assertRejected("23514", db.app(), "UPDATE core.refund SET resolved_at=NULL");
    }

    @Test
    void ac_3_16_boundaryAmountsAndAllDocumentedStatesAndSourcesAreAccepted() throws SQLException {
        Sql.execute(
                db.app(),
                insert("core.refund", Map.of("amount", "1", "merchant_refund_ref", "repeat('x',64)")),
                insert("core.refund", Map.of("id", "'rfd_01JB0000000000000000000002'", "amount", "99999999")));
        for (String status : new String[] {"AWAITING_FUNDS", "READY", "SUBMITTED", "UNKNOWN"}) {
            Sql.execute(db.app(), "UPDATE core.refund SET status='" + status + "'");
            Sql.assertRejected("23514", db.app(), "UPDATE core.refund SET resolved_at=" + TIME);
        }
        for (String status :
                new String[] {"AWAITING_FUNDS", "READY", "SUBMITTED", "UNKNOWN", "SUCCEEDED", "FAILED", "CANCELLED"}) {
            Sql.execute(
                    db.app(),
                    insert(
                            "core.refund_transition",
                            Map.of("from_status", "'" + status + "'", "to_status", "'" + status + "'")));
        }
        for (String source :
                new String[] {"API", "QUEUE", "COMMAND_RESPONSE", "COMMAND_QUERY", "WEBHOOK", "BATCH_LINE", "OPERATOR"
                }) {
            Sql.execute(db.app(), insert("core.refund_transition", Map.of("evidence_source", "'" + source + "'")));
        }
        Sql.execute(db.app(), insert("io.outbound_command"));
        for (String status : new String[] {"PENDING", "IN_FLIGHT", "UNKNOWN", "DONE"}) {
            Sql.execute(db.app(), "UPDATE io.outbound_command SET status='" + status + "'");
        }
    }

    @Test
    void ac_2_9_unknownRefundIsOnlyARejectionInBothInboxTables() throws SQLException {
        Sql.execute(
                db.app(),
                "UPDATE io.inbox_event SET status='REJECTED',result='UNKNOWN_REFUND'",
                history("REJECTED", "UNKNOWN_REFUND"));
        for (String status : new String[] {"RECEIVED", "PARKED", "PROCESSED", "IGNORED", "DEAD"}) {
            Sql.assertRejected(
                    "23514",
                    db.app(),
                    "UPDATE io.inbox_event SET status='" + status + "',parked_on="
                            + (status.equals("PARKED") ? "'refund:missing'" : "NULL"));
            Sql.assertRejected("23514", db.app(), history(status, "UNKNOWN_REFUND"));
        }
        Sql.assertRejected("23514", db.app(), "UPDATE io.inbox_event SET result='INVALID_RESULT'");
        Sql.assertRejected("23514", db.app(), history("REJECTED", "INVALID_RESULT"));
        for (String result : new String[] {
            "BUSINESS_CONFLICT",
            "INVALID_AMOUNT",
            "UNSUPPORTED_CURRENCY",
            "UNSUPPORTED_CAPTURE",
            "UNSUPPORTED_TYPE",
            "LIVEMODE"
        }) {
            Sql.execute(db.app(), "UPDATE io.inbox_event SET result='" + result + "'", history("REJECTED", result));
        }
        for (String result : new String[] {"APPLIED", "DUPLICATE_FACT"}) {
            Sql.execute(
                    db.app(),
                    "UPDATE io.inbox_event SET status='PROCESSED',result='" + result + "'",
                    history("PROCESSED", result));
        }
    }

    private static String history(String status, String result) {
        return """
                INSERT INTO io.inbox_status_history(event_id,to_status,result,parked_on,lease_epoch,recorded_at)
                VALUES ('evt_refund','%s','%s',%s,0,%s)
                """.formatted(status, result, status.equals("PARKED") ? "'refund:missing'" : "NULL", TIME);
    }

    private static String insert(String table) {
        return insert(table, Map.of());
    }

    private static String insert(String table, Map<String, String> changes) {
        var fields = new LinkedHashMap<String, String>();
        switch (table) {
            case "core.refund" -> {
                fields.put("id", REFUND);
                fields.put("payment_id", PAYMENT);
                fields.put("merchant_refund_ref", "'MR-1'");
                fields.put("amount", "2000");
                fields.put("status", "'AWAITING_FUNDS'");
                fields.put("initial_result", "'{\"status\":201,\"body\":{\"status\":\"AWAITING_FUNDS\"}}'");
                fields.put("request_fingerprint", "'fingerprint'");
                fields.put("accepted_at", TIME);
            }
            case "core.refund_transition" -> {
                fields.put("subject_id", REFUND);
                fields.put("from_status", "NULL");
                fields.put("to_status", "'AWAITING_FUNDS'");
                fields.put("evidence_type", "'refund_request'");
                fields.put("evidence_id", REFUND);
                fields.put("evidence_source", "'API'");
                fields.put("evidence_ref", "'client:K1'");
                fields.put("occurred_at", TIME);
            }
            case "io.outbound_command" -> {
                fields.put("id", COMMAND);
                fields.put("kind", "'SUBMIT_REFUND'");
                fields.put("subject_id", REFUND);
                fields.put("request_id", REFUND);
                fields.put("status", "'PENDING'");
                fields.put("next_attempt_at", TIME);
                fields.put("created_at", TIME);
            }
            case "io.outbound_attempt" -> {
                fields.put("command_id", COMMAND);
                fields.put("attempt_no", "1");
                fields.put("lease_epoch", "0");
                fields.put("sent_at", TIME);
                fields.put("request_summary", "'{\"method\":\"POST\"}'");
                fields.put("finished_at", TIME);
            }
            case "io.idempotency_record" -> {
                fields.put("client_id", "'client'");
                fields.put("idem_key", "'K1'");
                fields.put("request_fingerprint", "'fingerprint'");
                fields.put("state", "'IN_PROGRESS'");
                fields.put("locked_until", TIME);
                fields.put("created_at", TIME);
                fields.put("expires_at", TIME + "::timestamptz + interval '24 hours'");
            }
            default -> throw new IllegalArgumentException(table);
        }
        fields.put("recorded_at", TIME);
        fields.putAll(changes);
        return "INSERT INTO " + table + " (" + String.join(",", fields.keySet()) + ") VALUES ("
                + String.join(",", fields.values()) + ")";
    }
}
