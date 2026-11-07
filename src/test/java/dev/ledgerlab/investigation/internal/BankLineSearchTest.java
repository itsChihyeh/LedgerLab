package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerlab.inbox.scheduling-enabled=false")
@Import(PaymentQueryTest.Controls.class)
class BankLineSearchTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshMigrated();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String FILE = "file_01K88P00000000000000000000";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
        registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
        registry.add("LEDGERLAB_READONLY_PASSWORD", () -> TestDatabase.READONLY_PASSWORD);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> StripeSignatures.SECRET);
        registry.add("STRIPE_API_KEY", () -> "");
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    @Qualifier("investigationDataSource")
    DataSource readonly;

    @BeforeAll
    static void seed() throws SQLException {
        Sql.execute(
                DB.app(),
                """
                INSERT INTO io.imported_file (id, kind, source_name, sha256, raw, statement_date, version, status,
                    reject_reason, received_at, recorded_at)
                VALUES ('%1$s', 'BANK_STATEMENT', 'search.csv', encode(sha256('raw'::bytea), 'hex'), 'raw'::bytea,
                    '2026-10-23', 1, 'ACCEPTED', NULL, '2026-10-23T12:00:00Z', '2026-10-23T12:00:00Z')
                """.formatted(FILE),
                """
                INSERT INTO recon.bank_statement (file_id, account_ref, currency, opening_balance, closing_balance,
                    period_start, period_end, version, recorded_at)
                VALUES ('%1$s', 'SIM-CAD-001', 'CAD', 0, 0, '2026-10-23', '2026-10-23', 1, '2026-10-23T12:00:00Z')
                """.formatted(FILE),
                // SPLIT: two credits for one reference; DUPLICATE: the same amount booked twice under two ids
                line("split_b", 1, "BOOKED", 6000, "po_split", "2026-10-24", "UNAPPLIED"),
                line("split_a", 1, "BOOKED", 4000, "po_split", "2026-10-23", "UNAPPLIED"),
                line("dup_1", 1, "BOOKED", 9680, "po_dup", "2026-10-23", "UNAPPLIED"),
                line("dup_2", 1, "BOOKED", 9680, "po_dup", "2026-10-23", "UNAPPLIED"),
                line("late", 1, "PENDING", 500, "po_late", "2026-10-23", "IGNORED_PENDING"),
                line("late", 2, "BOOKED", 500, "po_late", "2026-10-24", "UNAPPLIED"),
                line("other", 1, "BOOKED", 100, "po_other", "2026-10-23", "UNAPPLIED"),
                """
                INSERT INTO recon.bank_line (bank_txn_id, version, file_id, direction, currency, status, amount,
                    reference, booking_date, value_date, memo, match_status, recorded_at)
                SELECT 'many_' || lpad(n::text, 3, '0'), 1, '%1$s', 'CREDIT', 'CAD', 'BOOKED', 100, 'po_many',
                    '2026-10-23', '2026-10-23', 'memo', 'UNAPPLIED', '2026-10-23T12:00:00Z'
                FROM generate_series(1, 101) n
                """.formatted(FILE));
    }

    private static String line(
            String id, int version, String status, long amount, String reference, String booked, String match) {
        return """
                INSERT INTO recon.bank_line (bank_txn_id, version, file_id, direction, currency, status, amount,
                    reference, booking_date, value_date, memo, match_status, recorded_at)
                VALUES ('%s', %d, '%s', 'CREDIT', 'CAD', '%s', %d, '%s', '%s', '%s', 'memo', '%s',
                    '2026-10-23T12:00:00Z')
                """.formatted(id, version, FILE, status, amount, reference, booked, booked, match);
    }

    @BeforeEach
    void resetProbe() {
        PaymentQueryTest.PROBE.observations.clear();
    }

    @AfterAll
    static void close() {
        HTTP.close();
        DB.drop();
    }

    @Test
    void ac_10_3_searchByReferenceReturnsEveryCurrentLine() throws Exception {
        JsonNode split = ok("/api/bank-lines?reference=po_split");
        assertThat(split.path("reference").asString()).isEqualTo("po_split");
        assertThat(ids(split)).containsExactly("split_a", "split_b");
        assertThat(split.path("truncated").asBoolean()).isFalse();
        Instant.parse(split.path("asOf").path("at").asString());
        assertThat(split.path("asOf").path("seq").isNumber()).isTrue();

        JsonNode duplicate = ok("/api/bank-lines?reference=po_dup");
        assertThat(ids(duplicate)).containsExactly("dup_1", "dup_2");

        JsonNode late = ok("/api/bank-lines?reference=po_late");
        assertThat(late.path("lines").size()).isEqualTo(1);
        assertThat(late.path("lines").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(late.path("lines").get(0).path("status").asString()).isEqualTo("BOOKED");

        // the line shape is the one /api/bank-lines/{id} reports as bankLine
        JsonNode detail = ok("/api/bank-lines/split_a").path("bankLine");
        assertThat(split.path("lines").get(0)).isEqualTo(detail);
    }

    @Test
    void ac_10_3_searchIsCappedAtOneHundredLines() throws Exception {
        JsonNode many = ok("/api/bank-lines?reference=po_many");
        assertThat(many.path("lines").size()).isEqualTo(100);
        assertThat(many.path("truncated").asBoolean()).isTrue();
        assertThat(many.path("lines").get(0).path("bankTxnId").asString()).isEqualTo("many_001");
    }

    @Test
    void ac_10_3_unknownReferenceIsEmptyAndBadReferenceIs400() throws Exception {
        JsonNode none = ok("/api/bank-lines?reference=po_nothing");
        assertThat(none.path("lines").isArray()).isTrue();
        assertThat(none.path("lines").isEmpty()).isTrue();
        assertThat(none.path("truncated").asBoolean()).isFalse();
        assertThat(get("/api/bank-lines").statusCode()).isEqualTo(400);
        assertThat(get("/api/bank-lines?reference=").statusCode()).isEqualTo(400);
        assertThat(get("/api/bank-lines?reference=" + encode("po split")).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/bank-lines?reference=" + "a".repeat(129)).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/bank-lines?reference=" + "a".repeat(128)).statusCode())
                .isEqualTo(200);
    }

    @Test
    void ac_10_3_searchRunsOnReadonly() throws Exception {
        ok("/api/bank-lines?reference=po_split");
        assertThat(PaymentQueryTest.PROBE.observations.size()).isGreaterThanOrEqualTo(2);
        PaymentQueryTest.assertQueryCredentials(PaymentQueryTest.PROBE.observations.size());
        for (String sql : List.of(
                "UPDATE recon.v_bank_line SET memo='changed'",
                "DELETE FROM recon.v_bank_line",
                "INSERT INTO recon.bank_line (bank_txn_id) VALUES ('x')")) Sql.assertRejected("42501", readonly, sql);
    }

    private static List<String> ids(JsonNode result) {
        return result.path("lines")
                .valueStream()
                .map(l -> l.path("bankTxnId").asString())
                .toList();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode ok(String path) throws Exception {
        PaymentQueryTest.PROBE.observations.clear();
        HttpResponse<String> response = get(path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
