package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.ingestion.StripeReader.FetchPurpose;
import dev.ledgerlab.ingestion.StripeReader.FetchResult;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class StripeReaderTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final String KEY = "sk_test_" + "reader_synthetic_only";
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final List<String> queries = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    private final List<String> versions = new ArrayList<>();
    private TestDatabase.Handle db;
    private JdbcClient jdbc;
    private JdbcTransactionManager manager;
    private HttpServer server;
    private HttpClient http;
    private StripeReader reader;

    @BeforeEach
    void setUp() throws IOException {
        db = TestDatabase.freshMigrated();
        var source = db.app();
        jdbc = JdbcClient.create(source);
        manager = new JdbcTransactionManager(source);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        reader = reader(KEY, Duration.ofSeconds(2));
    }

    @AfterEach
    void close() {
        server.stop(0);
        http.close();
        db.drop();
    }

    private StripeReader reader(@Nullable String key, Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return execution.execute(request, body);
                })
                .build();
        return new RestStripeReader(
                client,
                new StripeProperties(StripeSignatures.SECRET, key),
                jdbc,
                manager,
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void ac_5_17_threePagesAreArchivedOnceWithExactHashAndHeaders() throws Exception {
        List<String> pages = List.of(page(line("txn_3"), true), page(line("txn_2"), true), page(line("txn_1"), false));
        serve("/v1/balance_transactions", pages, 200);
        var result = reader.listBalanceTransactionsForPayout("po_batch", FetchPurpose.BATCH_LINES, null);
        assertThat(result.complete()).isTrue();
        assertThat(result.items())
                .extracting(StripeReader.BalanceTransaction::id)
                .containsExactly("txn_3", "txn_2", "txn_1");
        assertThat(result.items().getFirst().amount()).isEqualTo(10000);
        assertThat(result.items().getFirst().fee()).isEqualTo(320);
        assertThat(result.items().getFirst().net()).isEqualTo(9680);
        assertThat(result.items().getFirst().source()).isEqualTo("ch_payment");
        assertThat(result.items().getFirst().created()).isEqualTo(Instant.ofEpochSecond(1792756800));
        assertThat(queries)
                .containsExactly(
                        "payout=po_batch&limit=100",
                        "payout=po_batch&limit=100&starting_after=txn_3",
                        "payout=po_batch&limit=100&starting_after=txn_2");
        assertThat(auth).containsOnly("Bearer " + KEY);
        assertThat(versions).containsOnly("2026-09-30.endive");
        assertArchive(result, mapper.writeValueAsString(pages));
        assertThat(jdbc.sql("SELECT request::text FROM io.processor_fetch")
                        .query(String.class)
                        .single())
                .contains("starting_after", "txn_2", "BATCH_LINES")
                .doesNotContain(KEY, "Authorization");
    }

    @Test
    void ac_5_10_secondPageHttpFailureKeepsEvidenceButNoUsableItems() throws Exception {
        List<String> pages = List.of(page(line("txn_3"), true), "{\"error\":\"synthetic\"}");
        serve("/v1/balance_transactions", pages, 500);
        var result = reader.listBalanceTransactionsForPayout("po_batch", FetchPurpose.BATCH_LINES, null);
        assertThat(result.complete()).isFalse();
        assertThat(result.items()).isEmpty();
        assertThat(result.failure()).isEqualTo("HTTP_STATUS_500");
        assertArchive(result, mapper.writeValueAsString(pages));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{broken",
                "{}",
                "{\"data\":[],\"has_more\":true}",
                "{\"data\":[],\"has_more\":\"false\"}",
                "{\"data\":[],\"has_more\":false} {}"
            })
    void ac_5_10_invalidSecondPageNeverCompletes(String invalid) throws Exception {
        List<String> pages = List.of(page(line("txn_3"), true), invalid);
        serve("/v1/balance_transactions", pages, 200);
        var result = reader.listBalanceTransactionsForPayout("po_batch", FetchPurpose.BATCH_LINES, null);
        assertThat(result.complete()).isFalse();
        assertThat(result.items()).isEmpty();
        assertArchive(result, mapper.writeValueAsString(pages));
    }

    @Test
    void ac_5_17_repeatedCursorFailsInsteadOfLooping() throws Exception {
        String page = page(line("txn_same"), true);
        serve("/v1/balance_transactions", List.of(page, page), 200);
        var result = reader.listBalanceTransactionsForPayout("po_batch", FetchPurpose.BATCH_LINES, null);
        assertThat(result.complete()).isFalse();
        assertThat(queries).hasSize(2);
        assertArchive(result, mapper.writeValueAsString(List.of(page, page)));
    }

    @Test
    void ac_5_17_payoutFieldsAreParsedAndArchiveSurvivesCallerRollback() throws Exception {
        serve("/v1/payouts/po_batch", List.of(payout("po_batch")), 200);
        var result = new TransactionTemplate(manager).execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            var fetched = reader.retrievePayout("po_batch", FetchPurpose.PAYOUT, null);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            status.setRollbackOnly();
            return fetched;
        });
        assertThat(result).isNotNull();
        assertThat(result.complete()).isTrue();
        assertThat(result.items())
                .containsExactly(new StripeReader.Payout(
                        "po_batch",
                        9680,
                        "cad",
                        true,
                        "standard",
                        "paid",
                        "completed",
                        Instant.ofEpochSecond(1792843200),
                        Instant.ofEpochSecond(1792756800)));
        assertArchive(result, payout("po_batch"));
    }

    @Test
    void ac_5_10_payoutCreatedRangeUsesExclusiveEndAndAllPages() throws Exception {
        List<String> pages = List.of(page(payout("po_b"), true), page(payout("po_a"), false));
        serve("/v1/payouts", pages, 200);
        var result = reader.listPayoutsCreated(NOW.minusSeconds(86400), NOW, FetchPurpose.RECON_SNAPSHOT, null);
        assertThat(result.complete()).isTrue();
        assertThat(result.items()).extracting(StripeReader.Payout::id).containsExactly("po_b", "po_a");
        assertThat(queries.getFirst()).isEqualTo("created[gte]=1792670400&created[lt]=1792756800&limit=100");
        assertThat(queries.getLast()).endsWith("&starting_after=po_b");
        assertArchive(result, mapper.writeValueAsString(pages));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9680.0", "\"9680\"", "null", "true", "9223372036854775808"})
    void ac_5_10_moneyMustBeJsonIntegerRepresentableAsLong(String amount) throws Exception {
        String body = payout("po_batch").replace("\"amount\":9680", "\"amount\":" + amount);
        serve("/v1/payouts/po_batch", List.of(body), 200);
        var result = reader.retrievePayout("po_batch", FetchPurpose.PAYOUT, null);
        assertThat(result.complete()).isFalse();
        assertThat(result.items()).isEmpty();
        assertArchive(result, body);
    }

    @Test
    void ac_5_10_missingApiKeyFailsWithoutHttpAndIsArchived() throws Exception {
        var result = reader("", Duration.ofSeconds(1)).retrievePayout("po_batch", FetchPurpose.PAYOUT, null);
        assertThat(result.complete()).isFalse();
        assertThat(result.failure())
                .contains("ledgerlab.stripe.api-key", "required")
                .doesNotContain(KEY);
        assertArchive(result, "");
    }

    @Test
    void ac_5_10_absentApiKeyArchivesEmptyPageArray() throws Exception {
        var result = reader(null, Duration.ofSeconds(1))
                .listBalanceTransactionsForPayout("po_batch", FetchPurpose.BATCH_LINES, null);
        assertThat(result.complete()).isFalse();
        assertThat(result.items()).isEmpty();
        assertArchive(result, "[]");
    }

    @Test
    void ac_5_10_emptyCompletedListIsValidEvidence() throws Exception {
        String body = "{\"data\":[],\"has_more\":false}";
        serve("/v1/payouts", List.of(body), 200);
        var result = reader.listPayoutsCreated(NOW.minusSeconds(1), NOW, FetchPurpose.RECON_SNAPSHOT, null);
        assertThat(result.complete()).isTrue();
        assertThat(result.items()).isEmpty();
        assertArchive(result, mapper.writeValueAsString(List.of(body)));
    }

    @Test
    void ac_5_17_readerPreservesDuplicateUnsupportedAndSignedRowsForBusinessValidation() throws Exception {
        String item = line("txn_adjustment")
                .replace("\"charge\"", "\"adjustment\"")
                .replace("\"ch_payment\"", "null")
                .replace("10000", "-10000")
                .replace("9680", "-10320");
        String body = page(item + "," + item, false);
        serve("/v1/balance_transactions", List.of(body), 200);
        var result = reader.listBalanceTransactionsForPayout("po_batch", FetchPurpose.BATCH_LINES, null);
        assertThat(result.complete()).isTrue();
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().amount()).isEqualTo(-10000);
        assertThat(result.items().getFirst().source()).isNull();
        assertArchive(result, mapper.writeValueAsString(List.of(body)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"fee\":\"320\"",
                "\"net\":9680.0",
                "\"amount\":null",
                "\"created\":9223372036854775807",
                "\"id\":\"\"",
                "\"currency\":false",
                "\"source\":{}"
            })
    void ac_5_10_invalidBalanceFieldsFailWholeFetch(String replacement) throws Exception {
        var node = mapper.readTree(line("txn_1"));
        var changed = (tools.jackson.databind.node.ObjectNode) node;
        var field =
                mapper.readTree("{" + replacement + "}").properties().iterator().next();
        changed.set(field.getKey(), field.getValue());
        String body = page(mapper.writeValueAsString(changed), false);
        serve("/v1/balance_transactions", List.of(body), 200);
        var result = reader.listBalanceTransactionsForPayout("po_batch", FetchPurpose.BATCH_LINES, null);
        assertThat(result.complete()).isFalse();
        assertThat(result.items()).isEmpty();
        assertArchive(result, mapper.writeValueAsString(List.of(body)));
    }

    @Test
    void ac_5_17_eventReferenceIsStoredWithoutBusinessWrites() throws Exception {
        jdbc.sql("""
                INSERT INTO io.inbox_event
                  (event_id,event_type,api_version,livemode,payload,payload_sha256,signed_at,occurred_at,
                   received_at,recorded_at,status,next_attempt_at)
                VALUES ('evt_fetch','payout.created','test',false,'{}'::bytea,encode(sha256('{}'::bytea),'hex'),
                  :now,:now,:now,:now,'RECEIVED',:now)
                """).param("now", NOW.atOffset(ZoneOffset.UTC)).update();
        String body = payout("po_batch")
                .replace("completed", "in_progress")
                .replace("9680", "-1")
                .replace("\"automatic\":true", "\"automatic\":false");
        serve("/v1/payouts/po_batch", List.of(body), 200);
        var result = reader.retrievePayout("po_batch", FetchPurpose.PAYOUT, "evt_fetch");
        assertThat(result.complete()).isTrue();
        assertThat(result.items().getFirst().amount()).isEqualTo(-1);
        assertThat(result.items().getFirst().automatic()).isFalse();
        assertThat(result.items().getFirst().reconciliationStatus()).isEqualTo("in_progress");
        assertThat(jdbc.sql("SELECT event_id FROM io.processor_fetch")
                        .query(String.class)
                        .single())
                .isEqualTo("evt_fetch");
        assertThat(jdbc.sql("SELECT count(*) FROM core.settlement_batch")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT status FROM io.inbox_event")
                        .query(String.class)
                        .single())
                .isEqualTo("RECEIVED");
        assertArchive(result, body);
    }

    @Test
    void ac_5_10_upstreamErrorCannotLeakCredentialThroughFailureResult() throws Exception {
        String body = "{\"error\":\"" + KEY + "\"}";
        serve("/v1/payouts/po_batch", List.of(body), 401);
        var result = reader.retrievePayout("po_batch", FetchPurpose.PAYOUT, null);
        assertThat(result.failure()).isEqualTo("HTTP_STATUS_401");
        assertThat(result.toString()).doesNotContain(KEY);
        assertArchive(result, body);
    }

    @Test
    void ac_5_10_archiveFailureIsExplicitAndDoesNotExposeRawResponse() {
        serve("/v1/payouts/po_batch", List.of(payout("po_batch")), 200);
        assertThatThrownBy(() -> reader.retrievePayout("po_batch", FetchPurpose.PAYOUT, "evt_missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stripe fetch archive failed")
                .hasNoCause();
        assertThat(jdbc.sql("SELECT count(*) FROM io.processor_fetch")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void ac_5_10_invalidPayoutIdStillArchivesFailureWithoutHttp() throws Exception {
        var result = reader.retrievePayout("invalid/id", FetchPurpose.PAYOUT, null);
        assertThat(result.complete()).isFalse();
        assertThat(result.failure()).isEqualTo("INVALID_PAYOUT_ID");
        assertArchive(result, "");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void ac_5_10_invalidCreatedIntervalStillArchivesFailureWithoutHttp(int kind) throws Exception {
        Instant from = kind == 2 ? NOW.minusNanos(1) : NOW;
        Instant to = switch (kind) {
            case 0 -> NOW;
            case 1 -> NOW.minusSeconds(1);
            case 2 -> NOW.plusSeconds(1);
            default -> NOW.plusNanos(1);
        };
        var result = reader.listPayoutsCreated(from, to, FetchPurpose.RECON_SNAPSHOT, null);
        assertThat(result.complete()).isFalse();
        assertThat(result.failure()).isEqualTo("INVALID_CREATED_INTERVAL");
        assertArchive(result, "[]");
    }

    @Test
    void ac_5_10_timeoutOnSecondPageRetainsFirstPage() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String first = page(line("txn_1"), true);
        server.createContext("/v1/balance_transactions", exchange -> {
            if (exchange.getRequestURI().getQuery().contains("starting_after")) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            } else {
                respond(exchange, first, 200);
            }
        });
        try {
            var result = reader(KEY, Duration.ofMillis(250))
                    .listBalanceTransactionsForPayout("po_batch", FetchPurpose.BATCH_LINES, null);
            assertThat(result.complete()).isFalse();
            assertThat(result.failure()).isEqualTo("TRANSPORT_FAILURE");
            assertArchive(result, mapper.writeValueAsString(List.of(first)));
        } finally {
            release.countDown();
        }
    }

    private void serve(String path, List<String> bodies, int lastStatus) {
        server.createContext(path, exchange -> {
            queries.add(
                    URLDecoder.decode(String.valueOf(exchange.getRequestURI().getRawQuery()), StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            versions.add(exchange.getRequestHeaders().getFirst("Stripe-Version"));
            int index = queries.size() - 1;
            respond(exchange, bodies.get(index), index == bodies.size() - 1 ? lastStatus : 200);
        });
    }

    private static void respond(HttpExchange exchange, String body, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void assertArchive(FetchResult<?> result, String body) throws Exception {
        assertThat(jdbc.sql("SELECT count(*) FROM io.processor_fetch")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        var row = jdbc.sql("SELECT * FROM io.processor_fetch WHERE id = :id")
                .param("id", result.fetchId())
                .query()
                .singleRow();
        assertThat(row.get("response_body")).isEqualTo(body);
        String hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(row.get("response_sha256")).isEqualTo(hash);
        assertThat(result.responseSha256()).isEqualTo(hash);
        assertThat(row.get("complete")).isEqualTo(result.complete());
        assertThat(((java.sql.Timestamp) Objects.requireNonNull(row.get("fetched_at"))).toInstant())
                .isEqualTo(NOW);
        assertThat(((java.sql.Timestamp) Objects.requireNonNull(row.get("recorded_at"))).toInstant())
                .isEqualTo(NOW);
    }

    private static String page(String item, boolean more) {
        return "{\"data\":[" + item + "],\"has_more\":" + more + "}";
    }

    private static String line(String id) {
        return """
                {"id":"%s","type":"charge","source":"ch_payment", "amount":10000,
                 "fee":320,"net":9680,"currency":"cad","created":1792756800}
                """.formatted(id);
    }

    private static String payout(String id) {
        return """
                {"id":"%s","amount":9680,"currency":"cad","automatic":true,"method":"standard",
                 "status":"paid","reconciliation_status":"completed","arrival_date":1792843200,"created":1792756800}
                """.formatted(id);
    }
}
