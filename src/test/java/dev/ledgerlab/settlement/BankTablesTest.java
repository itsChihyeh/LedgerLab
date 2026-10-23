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

class BankTablesTest {
    private static final String FILE = "'file_01K88P00000000000000000000'";
    private TestDatabase.Handle db;

    @BeforeEach
    void start() {
        db = TestDatabase.freshMigrated();
    }

    @AfterEach
    void stop() {
        db.drop();
    }

    private static String insert(String table, Map<String, String> overrides) {
        var values = new LinkedHashMap<String, String>();
        switch (table) {
            case "io.imported_file" -> {
                values.put("id", FILE);
                values.put("kind", "'BANK_STATEMENT'");
                values.put("source_name", "'test.csv'");
                values.put("sha256", "encode(sha256('raw'::bytea), 'hex')");
                values.put("raw", "'raw'::bytea");
                values.put("statement_date", "'2026-10-23'");
                values.put("version", "1");
                values.put("status", "'ACCEPTED'");
                values.put("reject_reason", "NULL");
                values.put("received_at", "'2026-10-23T12:00:00Z'");
            }
            case "recon.bank_statement" -> {
                values.put("file_id", FILE);
                values.put("account_ref", "'SIM-CAD-001'");
                values.put("currency", "'CAD'");
                values.put("opening_balance", "0");
                values.put("closing_balance", "9680");
                values.put("period_start", "'2026-10-23'");
                values.put("period_end", "'2026-10-23'");
                values.put("version", "1");
            }
            case "recon.bank_line" -> {
                values.put("bank_txn_id", "'t1'");
                values.put("version", "1");
                values.put("file_id", FILE);
                values.put("direction", "'CREDIT'");
                values.put("currency", "'CAD'");
                values.put("status", "'BOOKED'");
                values.put("amount", "9680");
                values.put("reference", "'po_b1'");
                values.put("booking_date", "'2026-10-23'");
                values.put("value_date", "'2026-10-23'");
                values.put("memo", "'memo'");
                values.put("match_status", "'UNAPPLIED'");
                values.put("matched_batch_id", "NULL");
                values.put("wait_until", "NULL");
            }
            default -> throw new IllegalArgumentException(table);
        }
        values.put("recorded_at", "'2026-10-23T12:00:00Z'");
        values.putAll(overrides);
        return "INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", values.values()) + ")";
    }

    private void seedFile() throws SQLException {
        Sql.execute(db.app(), insert("io.imported_file", Map.of()));
    }

    private void seedStatement() throws SQLException {
        seedFile();
        Sql.execute(db.app(), insert("recon.bank_statement", Map.of()));
    }

    private void seedLine() throws SQLException {
        seedStatement();
        Sql.execute(db.app(), insert("recon.bank_line", Map.of()));
    }

    @Test
    void ac_1_13_v7MigratesFromEmptyAndTablesBelongToMigrator() throws SQLException {
        assertThat(Sql.queryLong(
                        db.migrator(),
                        "SELECT count(*) FROM flyway.flyway_schema_history WHERE version='7' AND success"))
                .isEqualTo(1);
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM pg_tables WHERE tableowner='migrator' AND
                ((schemaname='io' AND tablename='imported_file') OR
                 (schemaname='recon' AND tablename IN ('bank_line','bank_statement')))
                """)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id|'bad'",
                "kind|'OTHER'",
                "sha256|'bad'",
                "version|0",
                "status|'OTHER'",
                "statement_date|NULL",
                "version|NULL",
                "reject_reason|'FILE_INCOMPLETE'",
                "status|'REJECTED'"
            })
    void ac_5_10_fileConstraintsRejectInvalidEvidence(String change) {
        String[] parts = change.split("\\|", 2);
        Sql.assertRejected("23514", db.app(), insert("io.imported_file", Map.of(parts[0], parts[1])));
    }

    @Test
    void ac_5_10_hashAndAcceptedFileIdentityAreUnique() throws SQLException {
        seedStatement();
        Sql.assertRejected(
                "23505", db.app(), insert("io.imported_file", Map.of("id", "'file_01K88P00000000000000000001'")));
        Sql.assertRejected("23505", db.app(), insert("recon.bank_statement", Map.of()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "account_ref|'other'",
                "currency|'USD'",
                "opening_balance|9007199254740992",
                "closing_balance|-9007199254740992",
                "period_end|'2026-10-24'",
                "version|0",
                "version|2",
                "file_id|'missing'",
                "period_start|'2026-10-24'"
            })
    void ac_5_12_statementConstraintsAndFileAgreement(String change) throws SQLException {
        seedFile();
        String[] parts = change.split("\\|", 2);
        Sql.assertRejected("23514", db.app(), insert("recon.bank_statement", Map.of(parts[0], parts[1])));
    }

    @Test
    void ac_5_10_rejectedAndWrongKindFilesCannotHaveStatements() {
        for (Map<String, String> changes : java.util.List.of(
                Map.of("status", "'REJECTED'", "reject_reason", "'FILE_INCOMPLETE'"),
                Map.of("kind", "'PROCESSOR_STATEMENT'"))) {
            Sql.assertRejected(
                    "23514", db.app(), insert("io.imported_file", changes), insert("recon.bank_statement", Map.of()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "bank_txn_id|' '",
                "version|0",
                "direction|'OTHER'",
                "currency|'cad'",
                "status|'OTHER'",
                "amount|0",
                "amount|100000000",
                "match_status|'OTHER'",
                "match_status|'WAITING'",
                "match_status|'MATCHED'",
                "status|'PENDING'",
                "match_status|'IGNORED_PENDING'",
                "direction|'DEBIT'"
            })
    void ac_5_20_lineConstraintsRejectInvalidStates(String change) throws SQLException {
        seedStatement();
        String[] parts = change.split("\\|", 2);
        Sql.assertRejected("23514", db.app(), insert("recon.bank_line", Map.of(parts[0], parts[1])));
    }

    @Test
    void ac_5_20_waitingRequiresCadAndLineRequiresStatement() throws SQLException {
        seedStatement();
        Sql.assertRejected(
                "23514",
                db.app(),
                insert(
                        "recon.bank_line",
                        Map.of(
                                "currency",
                                "'USD'",
                                "match_status",
                                "'WAITING'",
                                "wait_until",
                                "'2026-10-28T04:00:00Z'")));
        Sql.assertRejected("23503", db.app(), insert("recon.bank_line", Map.of("file_id", "'missing'")));
    }

    @Test
    void ac_5_10_evidenceIdentityIncludesVersion() throws SQLException {
        seedLine();
        Sql.assertRejected("23505", db.app(), insert("recon.bank_line", Map.of()));
        Sql.execute(db.app(), insert("recon.bank_line", Map.of("version", "2")));
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM recon.bank_line WHERE bank_txn_id='t1'"))
                .isEqualTo(2);
    }

    @Test
    void ac_1_13_appCanOnlyChangeMatchColumnsAndReadonlyOnlyViews() throws SQLException {
        seedLine();
        Sql.execute(db.app(), "UPDATE recon.bank_line SET match_status='UNMATCHED_NO_POSTING', matched_batch_id=NULL");
        for (String table : java.util.List.of("io.imported_file", "recon.bank_statement", "recon.bank_line")) {
            for (String operation : java.util.List.of("DELETE FROM ", "TRUNCATE ")) {
                Sql.assertRejected("42501", db.app(), operation + table);
                Sql.assertRejected(
                        "LL004", db.migrator(), operation + table + (operation.equals("TRUNCATE ") ? " CASCADE" : ""));
            }
            Sql.assertRejected("42501", db.readonly(), "SELECT * FROM " + table);
            Sql.assertRejected("42501", db.readonly(), insert(table, Map.of()));
        }
        for (String table : java.util.List.of("io.imported_file", "recon.bank_statement")) {
            Sql.assertRejected("42501", db.app(), "UPDATE " + table + " SET recorded_at=recorded_at");
            Sql.assertRejected("LL004", db.migrator(), "UPDATE " + table + " SET recorded_at=recorded_at");
        }
        for (String column : java.util.List.of(
                "bank_txn_id",
                "version",
                "file_id",
                "direction",
                "currency",
                "status",
                "amount",
                "reference",
                "return_of",
                "reason_code",
                "booking_date",
                "value_date",
                "memo",
                "wait_until",
                "recorded_at",
                "import_order"))
            Sql.assertRejected(
                    "42501",
                    db.app(),
                    "UPDATE recon.bank_line SET " + column + "="
                            + (column.equals("import_order") ? "DEFAULT" : column));
        Sql.assertRejected("LL004", db.migrator(), "UPDATE recon.bank_line SET memo='changed'");
        for (String view : java.util.List.of("recon.v_bank_statement", "recon.v_bank_line")) {
            assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM " + view))
                    .isEqualTo(1);
            Sql.assertRejected("42501", db.readonly(), "DELETE FROM " + view);
        }
    }

    @Test
    void ac_5_4_onlyOneLineMayMatchBatchAndBatchMustExist() throws SQLException {
        seedStatement();
        Sql.assertRejected(
                "23503",
                db.app(),
                insert("recon.bank_line", Map.of("match_status", "'MATCHED'", "matched_batch_id", "'po_missing'")));
        Sql.execute(db.app(), """
                INSERT INTO io.inbox_event (event_id,event_type,api_version,livemode,payload,payload_sha256,
                    signed_at,occurred_at,received_at,recorded_at,status,next_attempt_at)
                VALUES ('evt_bank','payout.created','test',false,'{}',encode(sha256('{}'::bytea),'hex'),
                    '2026-10-23','2026-10-23','2026-10-23','2026-10-23','RECEIVED','2026-10-23')
                """, """
                INSERT INTO core.settlement_batch(batch_id,payout_amount,currency,automatic,method,status,
                    source_event_id,occurred_at,recorded_at,updated_at)
                VALUES ('po_b1',9680,'cad',true,'standard','ANNOUNCED','evt_bank','2026-10-23','2026-10-23','2026-10-23')
                """);
        Sql.execute(
                db.app(),
                insert("recon.bank_line", Map.of("match_status", "'MATCHED'", "matched_batch_id", "'po_b1'")));
        Sql.assertRejected(
                "23505",
                db.app(),
                insert(
                        "recon.bank_line",
                        Map.of("bank_txn_id", "'t2'", "match_status", "'MATCHED'", "matched_batch_id", "'po_b1'")));
        Sql.assertRejected(
                "23514",
                db.app(),
                insert("recon.bank_line", Map.of("bank_txn_id", "'t2'", "matched_batch_id", "'po_b1'")));
    }
}
