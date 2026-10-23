package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.reconciliation.ProcessorSnapshotReader;
import dev.ledgerlab.shared.BusinessCalendar;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class ProcessorSnapshotsTest {
    TestDatabase.Handle db;
    JdbcClient jdbc;
    ProcessorSnapshots snapshots;
    MutableClock clock = new MutableClock(Instant.parse("2026-11-11T08:00:00Z"));
    static final LocalDate DATE = LocalDate.parse("2026-11-10");
    static final String PAYOUTS = """
            {"data":[{"id":"po_b1","amount":9680,"currency":"cad","automatic":true,
            "method":"standard","status":"paid","reconciliation_status":"completed",
            "arrival_date":1794441600,"created":1794333600}],"has_more":false}
            """;
    static final String LINE = """
            {"id":"txn_l1","type":"charge","source":"ch_p1","amount":10000,
            "fee":320,"net":9680,"currency":"cad","created":1794326400}
            """;

    @BeforeEach
    void setup() {
        db = TestDatabase.freshMigrated();
        var source = db.app();
        jdbc = JdbcClient.create(source);
        var ids = IdGenerator.seeded(clock, 10);
        var mapper = JsonMapper.builder().build();
        snapshots = new ProcessorSnapshots(
                jdbc,
                new DataSourceTransactionManager(source),
                clock,
                ids,
                new BusinessCalendar(),
                mapper,
                new ReconJson(mapper),
                new JdbcIngestionIssues(jdbc, ids, clock));
    }

    @AfterEach
    void cleanup() {
        db.drop();
    }

    static String page(String lines, boolean more) {
        return "{\"data\":[" + lines + "],\"has_more\":" + more + "}";
    }

    @Test
    void ac_5_10_duplicateLinesArePreservedAndNormalizedImportsAreIdempotent() {
        var result = snapshots.importSnapshot(
                "fixture", DATE, PAYOUTS, Map.of("po_b1", page(LINE + "," + LINE, false)), clock.instant());
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(jdbc.sql("SELECT count(*) FROM recon.statement_line")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(snapshots.importSnapshot(
                        "renamed",
                        DATE,
                        PAYOUTS.replace("\n", " "),
                        Map.of("po_b1", page(LINE + "," + LINE, false)),
                        clock.instant()))
                .isEqualTo(result);
    }

    @Test
    void ac_5_10_partialSnapshotIsArchivedWithoutAnyLines() {
        var result =
                snapshots.importSnapshot("partial", DATE, PAYOUTS, Map.of("po_b1", page(LINE, true)), clock.instant());
        assertThat(result.rejectReason()).isEqualTo("FILE_INCOMPLETE");
        assertThat(jdbc.sql("SELECT count(*) FROM recon.statement_line")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM io.imported_file")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM io.imported_file")
                        .query(String.class)
                        .single())
                .isEqualTo("REJECTED");
    }

    @Test
    void ac_5_10_changedIdentityPreservesBothVersionsAndOpensIngestionConflict() {
        var first = snapshots.importSnapshot("v1", DATE, PAYOUTS, Map.of("po_b1", page(LINE, false)), clock.instant());
        var second = snapshots.importSnapshot(
                "v2",
                DATE,
                PAYOUTS,
                Map.of("po_b1", page(LINE.replace("320", "321").replace("9680", "9679"), false)),
                clock.instant());
        assertThat(second.fileId()).isNotEqualTo(first.fileId());
        assertThat(jdbc.sql("SELECT fee FROM recon.statement_line ORDER BY fee")
                        .query(Long.class)
                        .list())
                .containsExactly(320L, 321L);
        assertThat(jdbc.sql("SELECT category || ':' || detected_by FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("STATEMENT_VERSION_CONFLICT:INGESTION");
        assertThat(jdbc.sql("SELECT processor_source::text FROM io.imported_file WHERE id=?")
                        .param(first.fileId())
                        .query(String.class)
                        .single())
                .contains("txn_l1");
        assertThat(jdbc.sql("SELECT version FROM io.imported_file ORDER BY version")
                        .query(Integer.class)
                        .list())
                .containsExactly(1, 2);
    }

    @Test
    void ac_5_10_databaseRejectsInvalidRowsAndProtectsEvidenceForEveryRole() throws Exception {
        snapshots.importSnapshot("v1", DATE, PAYOUTS, Map.of("po_b1", page(LINE, false)), clock.instant());
        for (String patch : List.of(
                "{\"line_seq\":0}",
                "{\"statement_line_id\":\"bad\"}",
                "{\"line_type\":\"bad\"}",
                "{\"processor_ref\":\" \"}",
                "{\"batch_id\":\"bad\"}",
                "{\"gross\":0}",
                "{\"gross\":100000000}",
                "{\"gross\":-1}",
                "{\"fee\":-1}",
                "{\"fee\":100000000}",
                "{\"net\":100000000}",
                "{\"net\":-100000000}",
                "{\"net\":1}",
                "{\"line_type\":\"REFUND\"}")) {
            Sql.assertRejected("23514", db.app(), copyRow(patch));
        }
        for (String field : List.of(
                "file_id",
                "line_seq",
                "statement_line_id",
                "line_type",
                "processor_ref",
                "batch_id",
                "gross",
                "fee",
                "net",
                "occurred_at",
                "settlement_date",
                "memo",
                "recorded_at")) {
            Sql.assertRejected(
                    field.equals("file_id") ? "23514" : "23502", db.app(), copyRow("{\"" + field + "\":null}"));
        }
        Sql.assertRejected("23505", db.app(), copyRow("{\"line_seq\":1}"));
        Sql.assertRejected("23514", db.app(), copyRow("{\"file_id\":\"file_00000000000000000000000000\"}"));
        var rejected = snapshots.importSnapshot(
                "incomplete", DATE, PAYOUTS, Map.of("po_b1", page(LINE, true)), clock.instant());
        Sql.assertRejected("23514", db.app(), copyRow("{\"file_id\":\"" + rejected.fileId() + "\"}"));
        for (String sql : List.of(
                "UPDATE recon.statement_line SET memo='changed'",
                "DELETE FROM recon.statement_line",
                "TRUNCATE recon.statement_line")) {
            Sql.assertRejected("42501", db.app(), sql);
            Sql.assertRejected("LL004", db.migrator(), sql);
        }
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM recon.statement_line");
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM io.imported_file");
        Sql.assertRejected("42501", db.readonly(), "UPDATE recon.v_statement_line SET memo='changed'");
        Sql.execute(
                db.readonly(),
                "SELECT * FROM recon.v_statement_line",
                "SELECT * FROM recon.v_processor_snapshot",
                "SELECT * FROM recon.v_input_evidence");
        assertThat(JdbcClient.create(db.migrator())
                        .sql("SELECT tableowner FROM pg_tables WHERE schemaname='recon' AND tablename='statement_line'")
                        .query(String.class)
                        .single())
                .isEqualTo("migrator");
        for (String field : List.of("processor_fetch_ids", "processor_source")) {
            String value = field.equals("processor_fetch_ids") ? "{}" : "[]";
            Sql.assertRejected(
                    "23514",
                    db.app(),
                    "INSERT INTO io.imported_file SELECT (jsonb_populate_record(NULL::io.imported_file, to_jsonb(t) || '{\"id\":\"file_00000000000000000000000000\",\""
                            + field + "\":" + value + "}'::jsonb)).* FROM io.imported_file t LIMIT 1");
        }
    }

    String copyRow(String patch) {
        return "INSERT INTO recon.statement_line SELECT (jsonb_populate_record(NULL::recon.statement_line, to_jsonb(t) || '{\"line_seq\":2}'::jsonb || '"
                + patch + "'::jsonb)).* FROM recon.statement_line t LIMIT 1";
    }

    @Test
    void ac_5_10_invalidFixturesRejectWholeSnapshotAndArchiveExactText() {
        for (String invalid : List.of(
                "not json",
                "{}",
                page(LINE, true),
                page(LINE.replace("10000", "1.5"), false),
                page(LINE.replace("cad", "usd"), false),
                page(LINE.replace("charge", "adjustment"), false),
                page(LINE.replace("txn_l1", "bad"), false),
                page(LINE.replace("ch_p1", " "), false),
                page(LINE.replace("320", "-1"), false),
                page(LINE.replace("9680", "999"), false),
                page(LINE.replace("10000", "100000000"), false),
                page(LINE.replace("10000", "-1"), false),
                page(LINE.replace("\"charge\"", "null"), false))) {
            var result = snapshots.importSnapshot("invalid", DATE, PAYOUTS, Map.of("po_b1", invalid), clock.instant());
            assertThat(result.status()).as(invalid).isEqualTo("REJECTED");
            String archived = jdbc.sql("SELECT processor_source::text FROM io.imported_file WHERE id=?")
                    .param(result.fileId())
                    .query(String.class)
                    .single();
            assertThat(JsonMapper.builder()
                            .build()
                            .readTree(archived)
                            .path("lines")
                            .path("po_b1")
                            .asText())
                    .isEqualTo(invalid);
        }
        assertThat(snapshots
                        .importSnapshot("missing", DATE, PAYOUTS, Map.of(), clock.instant())
                        .status())
                .isEqualTo("REJECTED");
        assertThat(snapshots
                        .importSnapshot(
                                "extra",
                                DATE,
                                "{\"data\":[],\"has_more\":false}",
                                Map.of("extra", "{}"),
                                clock.instant())
                        .status())
                .isEqualTo("REJECTED");
        assertThat(snapshots
                        .importSnapshot(
                                "date", DATE.plusDays(1), PAYOUTS, Map.of("po_b1", page(LINE, false)), clock.instant())
                        .status())
                .isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT count(*) FROM recon.statement_line")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThatThrownBy(() -> snapshots.importSnapshot("", DATE, PAYOUTS, Map.of(), clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshots.importSnapshot(
                        "future", DATE, PAYOUTS, Map.of(), clock.instant().plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_5_10_concurrentSameSnapshotReturnsOneResult() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<ProcessorSnapshots.Result>>();
            for (int i = 0; i < 2; i++)
                tasks.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return snapshots.importSnapshot(
                            "concurrent", DATE, PAYOUTS, Map.of("po_b1", page(LINE, false)), clock.instant());
                }));
            assertThat(tasks.get(0).get(20, TimeUnit.SECONDS))
                    .isEqualTo(tasks.get(1).get(20, TimeUnit.SECONDS));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM io.imported_file")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void ac_5_10_readerUsesTorontoDayAndCompletionTimeAndRejectsPartialPayoutList() {
        List<Instant> bounds = new ArrayList<>();
        ProcessorSnapshotReader reader = new ProcessorSnapshotReader() {
            @Override
            public FetchResult listPayoutsCreated(Instant from, Instant to) {
                bounds.add(from);
                bounds.add(to);
                clock.set(clock.instant().plusSeconds(30));
                return new FetchResult(42, true, clock.instant(), List.of());
            }

            @Override
            public FetchResult listBalanceTransactionsForPayout(String id) {
                throw new AssertionError();
            }
        };
        for (var date : List.of(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-11-01")))
            snapshots.fetch(date, reader);
        assertThat(java.time.Duration.between(bounds.get(0), bounds.get(1)).toHours())
                .isEqualTo(23);
        assertThat(java.time.Duration.between(bounds.get(2), bounds.get(3)).toHours())
                .isEqualTo(25);
        assertThat(jdbc.sql("SELECT max(received_at) FROM io.imported_file")
                        .query(java.time.OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(clock.instant());
    }
}
