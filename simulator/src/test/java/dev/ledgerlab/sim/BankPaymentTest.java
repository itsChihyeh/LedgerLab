package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.sim.bank.Bank;
import dev.ledgerlab.sim.bank.BankCalendar;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BankPaymentTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static PostgreSQLContainer database;
    private static ConfigurableApplicationContext app;
    private static HttpClient client;
    private static String base;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager manager;
    private LocalDate today;

    @BeforeAll
    static void start() {
        database = TestDatabase.start();
        app = new SpringApplication(SimulatorApplication.class)
                .run(
                        "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.password=test-simulator",
                                "--sim.stripe.webhook-secret=synthetic-bank-only",
                        "--sim.bank.timeout=PT1S", "--sim.clock.start=2026-11-06T16:00:00Z");
        base = "http://127.0.0.1:"
                + java.util.Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer())
                        .getPort();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var source = TestDatabase.role(database.getJdbcUrl(), "simulator");
        jdbc = JdbcClient.create(source);
        manager = new JdbcTransactionManager(source);
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        if (app != null) app.close();
        if (database != null) database.stop();
    }

    @BeforeEach
    void reset() throws Exception {
        jdbc.sql("truncate sim_bank.account,sim_bank.fault_plan,sim_truth.injected_fault cascade")
                .update();
        today = Instant.parse(body(send("GET", "/sim/clock", "")).path("now").asText())
                .atZone(ZoneId.of("America/Toronto"))
                .toLocalDate();
    }

    @Test
    void ac_7_1_acceptedExecutesThroughClockEndpointAndExportsDebit() throws Exception {
        fund(9680);
        var created = post("mpo_normal", 9680);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(body(created).path("status").asText()).isEqualTo("ACCEPTED");
        LocalDate execution = execution(created);
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, execution);
        assertThat(send("POST", "/sim/clock/advance", "{\"seconds\":" + days * 86400 + "}")
                        .statusCode())
                .isEqualTo(200);
        assertThat(get("mpo_normal").path("status").asText()).isEqualTo("EXECUTED");
        assertThat(bank(execution).statement(execution, ""))
                .contains("D,bt_mpo_normal_1,DEBIT,9680,CAD,BOOKED,mpo_normal," + execution)
                .endsWith("T,1,0,9680,9680,0\r\n");
        bank(execution.plusDays(1)).advance();
        assertThat(count("sim_bank.txn where request_id='mpo_normal'")).isEqualTo(1);
        assertThat(balance()).isZero();
        assertThat(truth("mpo_normal")).containsExactly("PAYMENT_REQUEST", "PAYMENT_EXECUTED");
    }

    @Test
    void ac_7_8_permanentDedupReturnsCurrentStatusAndConflictsLeaveOriginalIntact() throws Exception {
        fund(9680);
        var first = post("mpo_same", 9680);
        var repeat = post("mpo_same", 9680);
        assertThat(repeat.statusCode()).isEqualTo(200);
        assertThat(body(repeat)).isEqualTo(body(first));
        for (String changed : List.of(
                request("mpo_same", 9681),
                request("mpo_same", 9680).replace("CAD", "USD"),
                request("mpo_same", 9680).replace("MERCHANT-001", "MERCHANT-002"))) {
            var conflict = send("POST", "/sim/bank/payments", changed);
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(body(conflict).path("code").asText()).isEqualTo("REQUEST_CONFLICT");
        }
        bank(execution(first)).advance();
        plan("mpo_same", "TIMEOUT", 0);
        repeat = post("mpo_same", 9680);
        assertThat(repeat.statusCode()).isEqualTo(200);
        assertThat(body(repeat).path("status").asText()).isEqualTo("EXECUTED");
        assertThat(body(repeat).path("paymentId")).isEqualTo(body(first).path("paymentId"));
        assertThat(body(send("GET", "/sim/bank/payments", ""))).hasSize(1);
    }

    @Test
    void ac_7_8_concurrentDuplicatePostsAndExecutionsHaveOneEffect() throws Exception {
        fund(9680);
        var barrier = new CyclicBarrier(4);
        var responses = new ArrayList<HttpResponse<String>>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 4; i++)
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return post("mpo_race", 9680);
                }));
            for (var future : futures) responses.add(future.get(20, TimeUnit.SECONDS));
        }
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 200, 200, 200);
        var execution = execution(responses.getFirst());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++)
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    bank(execution).advance();
                    return null;
                }));
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertThat(count("sim_bank.outgoing_payment")).isEqualTo(1);
        assertThat(count("sim_bank.txn where direction='DEBIT'")).isEqualTo(1);
        assertThat(balance()).isZero();
    }

    @Test
    void ac_7_22_insufficientBalanceRejectsWithoutDebitEvenIfLaterFunded() throws Exception {
        var created = post("mpo_empty", 9680);
        bank(execution(created)).advance();
        assertThat(get("mpo_empty").path("status").asText()).isEqualTo("REJECTED");
        assertThat(get("mpo_empty").path("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        bank(execution(created)).receive("po_later", 9680);
        bank(execution(created).plusDays(7)).advance();
        assertThat(count("sim_bank.txn where direction='DEBIT'")).isZero();
    }

    @Test
    void ac_7_6_timeoutPersistsOnlyTruthAndReleasesBankLock() throws Exception {
        plan("mpo_timeout", "TIMEOUT", 0);
        var request = HttpRequest.newBuilder(URI.create(base + "/sim/bank/payments"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(300))
                .POST(HttpRequest.BodyPublishers.ofString(request("mpo_timeout", 100)))
                .build();
        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat(count("sim_bank.outgoing_payment")).isZero();
        assertThat(truth("mpo_timeout")).containsExactly("PAYMENT_REQUEST", "TIMEOUT");
        assertThat(post("mpo_other", 100).statusCode()).isEqualTo(201);
        assertThat(post("mpo_timeout", 100).statusCode()).isEqualTo(504);
        assertThat(count("sim_bank.outgoing_payment where request_id='mpo_timeout'"))
                .isZero();
    }

    @Test
    void ac_7_8_droppedResponseIsConnectionErrorAfterCommitAndRetryDoesNotDropAgain() throws Exception {
        fund(9680);
        plan("mpo_drop", "EXECUTE_DROP_RESPONSE", 0);
        assertThatThrownBy(() -> post("mpo_drop", 9680))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(HttpTimeoutException.class);
        assertThat(count("sim_bank.outgoing_payment")).isEqualTo(1);
        var retry = post("mpo_drop", 9680);
        assertThat(retry.statusCode()).isEqualTo(200);
        bank(execution(retry)).advance();
        assertThat(get("mpo_drop").path("status").asText()).isEqualTo("EXECUTED");
        assertThat(balance()).isZero();
        assertThat(truth("mpo_drop"))
                .containsExactly("PAYMENT_REQUEST", "EXECUTE_DROP_RESPONSE", "PAYMENT_REQUEST", "PAYMENT_EXECUTED");
    }

    @Test
    void ac_7_7_unavailableConsumesPostDetailAndListAttemptsThenRecovers() throws Exception {
        plan("*", "UNAVAILABLE", 3);
        assertThat(post("mpo_unavailable", 100).statusCode()).isEqualTo(503);
        assertThat(send("GET", "/sim/bank/payments/mpo_unavailable", "").statusCode())
                .isEqualTo(503);
        assertThat(send("GET", "/sim/bank/payments", "").statusCode()).isEqualTo(503);
        assertThat(count("sim_bank.outgoing_payment")).isZero();
        assertThat(count("sim_truth.injected_fault where fault='UNAVAILABLE'")).isEqualTo(3);
        assertThat(post("mpo_unavailable", 100).statusCode()).isEqualTo(201);
        assertThat(get("mpo_unavailable").path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(count("sim_truth.injected_fault where fault='UNAVAILABLE'")).isEqualTo(3);
        plan("mpo_specific", "UNAVAILABLE", 1);
        assertThat(post("mpo_specific", 100).statusCode()).isEqualTo(503);
        assertThat(post("mpo_specific", 100).statusCode()).isEqualTo(201);
    }

    @Test
    void ac_7_22_rejectReturns201AndNeverExecutes() throws Exception {
        fund(9680);
        plan("mpo_reject", "REJECT", 0);
        var created = post("mpo_reject", 9680);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(body(created).path("status").asText()).isEqualTo("REJECTED");
        assertThat(body(created).path("reason").asText()).isEqualTo("ACCOUNT_CLOSED");
        bank(execution(created).plusDays(7)).advance();
        assertThat(count("sim_bank.txn where direction='DEBIT'")).isZero();
        assertThat(balance()).isEqualTo(9680);
    }

    @Test
    void ac_7_9_rejectOnQueryNeverExecutesEvenBeforeQueryAndPlanIsFrozen() throws Exception {
        fund(9680);
        plan("mpo_query", "REJECT_ON_QUERY", 0);
        var created = post("mpo_query", 9680);
        assertThat(body(created).path("status").asText()).isEqualTo("ACCEPTED");
        plan("mpo_query", "NONE", 0);
        bank(execution(created).plusDays(7)).advance();
        assertThat(count("sim_bank.txn where direction='DEBIT'")).isZero();
        assertThat(get("mpo_query").path("status").asText()).isEqualTo("REJECTED");
        assertThat(get("mpo_query").path("reason").asText()).isEqualTo("ACCOUNT_CLOSED");
        bank(execution(created).plusDays(8)).advance();
        assertThat(count("sim_bank.txn where direction='DEBIT'")).isZero();
    }

    @Test
    void ac_7_11_executeAfterRejectCreatesConflictEvidenceExactlyOnce() throws Exception {
        fund(9680);
        plan("mpo_conflict", "EXECUTE_AFTER_REJECT", 0);
        var created = post("mpo_conflict", 9680);
        assertThat(body(created).path("status").asText()).isEqualTo("REJECTED");
        bank(execution(created)).advance();
        bank(execution(created).plusDays(7)).advance();
        assertThat(get("mpo_conflict").path("status").asText()).isEqualTo("EXECUTED");
        assertThat(count("sim_bank.txn where direction='DEBIT'")).isEqualTo(1);
    }

    @Test
    void ac_7_10_returnUsesSecondBusinessDayAndOriginalPlanAcrossRestart() throws Exception {
        fund(9680);
        plan("mpo_return", "RETURN_AFTER_DAYS", 2);
        var created = post("mpo_return", 9680);
        plan("mpo_return", "NONE", 0);
        var executed = execution(created);
        var returned = new BankCalendar().addBusinessDays(executed, 2);
        bank(executed).advance();
        bank(returned.minusDays(1)).advance();
        assertThat(get("mpo_return").path("status").asText()).isEqualTo("EXECUTED");
        assertThat(bank(returned).statement(returned, ""))
                .contains("D,bt_mpo_return_R,CREDIT,9680,CAD,BOOKED,mpo_return," + returned)
                .contains(",RETURN ACCOUNT_CLOSED\r\n")
                .endsWith("T,1,9680,0,0,9680\r\n");
        assertThat(get("mpo_return").path("status").asText()).isEqualTo("RETURNED");
        assertThat(get("mpo_return").path("reason").asText()).isEqualTo("ACCOUNT_CLOSED");
        bank(returned.plusDays(1)).advance();
        assertThat(count("sim_bank.txn where request_id='mpo_return'")).isEqualTo(2);
        assertThat(truth("mpo_return"))
                .containsExactly("PAYMENT_REQUEST", "RETURN_AFTER_DAYS", "PAYMENT_EXECUTED", "PAYMENT_RETURNED");
    }

    @ParameterizedTest
    @CsvSource({"2026-11-06,2026-11-09", "2026-11-10,2026-11-12", "2026-12-24,2026-12-29"})
    void ac_7_1_executionSkipsWeekendsAndHolidays(String submitted, String execution) {
        var result = bank(LocalDate.parse(submitted)).submitPayment(JSON.readTree(request("mpo_calendar", 100)));
        assertThat(result.body().path("executionDate").asText()).isEqualTo(execution);
    }

    @Test
    void ac_7_1_largeClockJumpCannotFundPastPaymentWithFutureCredit() {
        bank(LocalDate.parse("2026-11-06")).submitPayment(JSON.readTree(request("mpo_past", 100)));
        bank(LocalDate.parse("2026-11-09")).receive("po_future", 100);
        bank(LocalDate.parse("2026-11-13")).advance();
        assertThat(bank(today).payment("mpo_past").body().path("reason").asText())
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(count("sim_bank.txn where direction='DEBIT'")).isZero();
    }

    @Test
    void ac_7_10_largeClockJumpAppliesReturnBeforeLaterPayment() {
        var friday = bank(LocalDate.parse("2026-11-06"));
        friday.receive("po_fund", 100);
        friday.plan(JSON.readTree("{\"target_ref\":\"mpo_first\",\"fault\":\"RETURN_AFTER_DAYS\",\"parameter\":1}"));
        friday.submitPayment(JSON.readTree(request("mpo_first", 100)));
        bank(LocalDate.parse("2026-11-10")).submitPayment(JSON.readTree(request("mpo_second", 100)));
        bank(LocalDate.parse("2026-11-13")).advance();
        assertThat(bank(today).payment("mpo_first").body().path("status").asText())
                .isEqualTo("RETURNED");
        assertThat(bank(today).payment("mpo_second").body().path("status").asText())
                .isEqualTo("EXECUTED");
        assertThat(balance()).isZero();
    }

    @Test
    void ac_7_23_unknownRequestReturns404() throws Exception {
        var response = send("GET", "/sim/bank/payments/mpo_missing", "");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void ac_7_6_outgoingWildcardFaultDoesNotContaminateIncomingTruth() throws Exception {
        plan("*", "TIMEOUT", 0);
        fund(100);
        assertThat(balance()).isEqualTo(100);
        assertThat(truth("po_funding")).isEmpty();
    }

    @Test
    void ac_7_1_incomingWildcardFaultDoesNotContaminateOutgoingTruth() throws Exception {
        plan("*", "SHORT", 1);
        assertThat(post("mpo_isolated", 100).statusCode()).isEqualTo(201);
        assertThat(truth("mpo_isolated")).containsExactly("PAYMENT_REQUEST");
        assertThat(jdbc.sql("select plan->>'fault' from sim_bank.outgoing_payment")
                        .query(String.class)
                        .single())
                .isEqualTo("NONE");
    }

    @Test
    void ac_7_11_executeAfterRejectDoesNotConfuseConfiguredReasonWithExecutionFailure() throws Exception {
        fund(100);
        bank(today)
                .plan(
                        JSON.readTree(
                                "{\"target_ref\":\"mpo_reason\",\"fault\":\"EXECUTE_AFTER_REJECT\",\"memo\":\"INSUFFICIENT_FUNDS\"}"));
        var created = post("mpo_reason", 100);
        bank(execution(created)).advance();
        assertThat(get("mpo_reason").path("status").asText()).isEqualTo("EXECUTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "100000000", "100.5", "\"100\"", "null", "9223372036854775808"})
    void ac_7_20_invalidAmountsCreateNoPayment(String amount) throws Exception {
        assertThat(send(
                                "POST",
                                "/sim/bank/payments",
                                request("mpo_invalid", 100).replace(":100,", ":" + amount + ","))
                        .statusCode())
                .isEqualTo(400);
        assertThat(count("sim_bank.outgoing_payment")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "readonly", "migrator"})
    void ac_7_1_otherRolesCannotAccessOutgoingPayments(String role) throws Exception {
        try (var connection = TestDatabase.role(database.getJdbcUrl(), role).getConnection();
                var statement = connection.createStatement()) {
            for (String operation : List.of("select * from ", "delete from ", "truncate ", "insert into ")) {
                String sql = operation + "sim_bank.outgoing_payment"
                        + (operation.startsWith("insert") ? " default values" : "");
                assertThatThrownBy(() -> statement.execute(sql))
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("42501");
            }
        }
    }

    @Test
    void ac_7_1_databaseEnforcesOutgoingConstraintsAndSourceForeignKeys() throws Exception {
        fund(100);
        var created = post("mpo_constraints", 100);
        bank(execution(created)).advance();
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var sql = connection.createStatement()) {
            for (String invalid : List.of(
                    "amount=0",
                    "amount=100000000",
                    "currency='USD'",
                    "destination=' '",
                    "status='UNKNOWN'",
                    "plan='[]'",
                    "request_id='wrong'",
                    "payment_id='wrong'")) {
                assertThatThrownBy(() -> sql.executeUpdate("update sim_bank.outgoing_payment set " + invalid))
                        .as(invalid)
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("23514");
            }
            assertThatThrownBy(() ->
                            sql.executeUpdate("update sim_bank.txn set request_id=null where request_id is not null"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo("23514");
            assertThatThrownBy(() -> sql.executeUpdate(
                            "update sim_bank.txn set payout_ref='po_funding' where request_id is not null"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo("23514");
            for (String invalid : List.of(
                    "update sim_bank.txn set request_id='mpo_missing' where request_id is not null",
                    "update sim_bank.outgoing_payment set account_ref='missing'"))
                assertThatThrownBy(() -> sql.executeUpdate(invalid))
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("23503");
            assertThatThrownBy(() -> sql.executeUpdate(
                            "insert into sim_bank.outgoing_payment select * from sim_bank.outgoing_payment"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo("23505");
            assertThatThrownBy(
                            () -> sql.executeUpdate(
                                    "insert into sim_bank.outgoing_payment select 'mpo_duplicate',payment_id,account_ref,amount,currency,destination,status,reason,execution_date,return_due_date,plan,recorded_at from sim_bank.outgoing_payment"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo("23505");
            for (String column : List.of(
                    "request_id",
                    "payment_id",
                    "account_ref",
                    "amount",
                    "currency",
                    "destination",
                    "status",
                    "execution_date",
                    "plan",
                    "recorded_at"))
                assertThatThrownBy(() -> sql.executeUpdate("update sim_bank.outgoing_payment set " + column + "=null"))
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("23502");
        }
    }

    @Test
    void ac_7_1_executionFailureRollsBackStatusDebitAndTruthTogether() throws Exception {
        fund(100);
        var created = post("mpo_rollback", 99);
        jdbc.sql("alter table sim_bank.account add constraint test_payout_rollback check (balance<>1)")
                .update();
        try {
            assertThatThrownBy(() -> bank(execution(created)).advance())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(get("mpo_rollback").path("status").asText()).isEqualTo("ACCEPTED");
            assertThat(count("sim_bank.txn where request_id='mpo_rollback'")).isZero();
            assertThat(truth("mpo_rollback")).containsExactly("PAYMENT_REQUEST");
        } finally {
            jdbc.sql("alter table sim_bank.account drop constraint test_payout_rollback")
                    .update();
        }
        bank(execution(created)).advance();
        assertThat(get("mpo_rollback").path("status").asText()).isEqualTo("EXECUTED");
    }

    private Bank bank(LocalDate date) {
        return new Bank(jdbc, manager, Clock.fixed(Instant.parse(date + "T16:00:00Z"), ZoneOffset.UTC));
    }

    private void fund(long amount) {
        bank(today.minusDays(7)).receive("po_funding", amount);
        bank(today).advance();
    }

    private void plan(String id, String fault, long parameter) throws Exception {
        assertThat(send(
                                "PUT",
                                "/sim/bank/plan",
                                JSON.createObjectNode()
                                        .put("target_ref", id)
                                        .put("fault", fault)
                                        .put("parameter", parameter)
                                        .put("memo", "ACCOUNT_CLOSED")
                                        .toString())
                        .statusCode())
                .isEqualTo(200);
    }

    private static String request(String id, long amount) {
        return JSON.createObjectNode()
                .put("requestId", id)
                .put("amount", amount)
                .put("currency", "CAD")
                .put("destination", "MERCHANT-001")
                .toString();
    }

    private HttpResponse<String> post(String id, long amount) throws Exception {
        return send("POST", "/sim/bank/payments", request(id, amount));
    }

    private JsonNode get(String id) throws Exception {
        var response = send("GET", "/sim/bank/payments/" + id, "");
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    private static HttpResponse<String> send(String method, String path, String content) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                content.isEmpty()
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(content))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static LocalDate execution(HttpResponse<String> response) {
        return LocalDate.parse(body(response).path("executionDate").asText());
    }

    private static long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private static long balance() {
        return jdbc.sql("select balance from sim_bank.account")
                .query(Long.class)
                .single();
    }

    private static List<@org.jspecify.annotations.Nullable String> truth(String id) {
        return jdbc.sql("select fault from sim_truth.injected_fault where object_ref=? order by id")
                .param(id)
                .query(String.class)
                .list();
    }
}
