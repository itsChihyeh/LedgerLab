package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.sim.bank.Bank;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class BankTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static PostgreSQLContainer database;
    private static JdbcClient jdbc;
    private static JdbcTransactionManager manager;

    @BeforeAll
    static void start() {
        database = TestDatabase.start();
        var source = TestDatabase.role(database.getJdbcUrl(), "simulator");
        Flyway.configure()
                .dataSource(source)
                .defaultSchema("sim_processor")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(source);
        manager = new JdbcTransactionManager(source);
    }

    @AfterAll
    static void stop() {
        if (database != null) database.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.sql("truncate sim_bank.account, sim_bank.fault_plan, sim_truth.injected_fault cascade")
                .update();
    }

    private Bank bank(String date) {
        return new Bank(jdbc, manager, Clock.fixed(Instant.parse(date + "T16:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void ac_5_4_weekendDepositUsesExecutedBankRecordsAndBalances() {
        bank("2026-11-06").receive("po_weekend", 9680);
        assertThat(bank("2026-11-08").transactions()).isEmpty();
        String csv = bank("2026-11-09").statement(LocalDate.parse("2026-11-09"), "");
        assertThat(csv)
                .startsWith("H,BANK_STATEMENT,SIM-CAD-001,2026-11-09,2026-11-09,1,CAD\r\n")
                .contains(",CREDIT,9680,CAD,BOOKED,po_weekend,2026-11-09,2026-11-09,")
                .endsWith("T,1,9680,0,0,9680\r\n");
        assertThat(jdbc.sql("select balance from sim_bank.account")
                        .query(Long.class)
                        .single())
                .isEqualTo(9680);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-10,2026-11-12",
        "2026-10-30,2026-11-02",
        "2026-03-06,2026-03-09",
        "2026-12-24,2026-12-29",
        "2027-12-24,2027-12-29"
    })
    void ac_5_4_calendarSkipsHolidaysWeekendsAndDst(String received, String booked) {
        bank(received).receive("po_calendar", 9680);
        var day = LocalDate.parse(booked);
        assertThat(bank(day.minusDays(1).toString()).transactions()).isEmpty();
        assertThat(bank(booked).transactions()).singleElement().satisfies(line -> {
            assertThat(line.booking_date()).isEqualTo(day);
            assertThat(line.amount()).isEqualTo(9680);
        });
    }

    @Test
    void ac_5_4_torontoDateRatherThanUtcControlsSchedule() {
        new Bank(jdbc, manager, Clock.fixed(Instant.parse("2026-11-10T02:00:00Z"), ZoneOffset.UTC))
                .receive("po_toronto", 9680);
        assertThat(bank("2026-11-10").transactions())
                .singleElement()
                .satisfies(line -> assertThat(line.booking_date()).isEqualTo(LocalDate.parse("2026-11-10")));
    }

    @ParameterizedTest
    @CsvSource({
        "SHORT,1,1,9679,DEPOSIT_AMOUNT_MISMATCH",
        "DUPLICATE,0,2,19360,UNMATCHED_BANK_LINE",
        "SPLIT,0,2,9680,DEPOSIT_AMOUNT_MISMATCH",
        "FOREIGN_CURRENCY,0,1,9680,UNMATCHED_BANK_LINE",
        "UNKNOWN_CREDIT,321,2,10001,UNMATCHED_BANK_LINE"
    })
    void ac_5_5_faultsChangeActualExecutionAndRecordIndependentTruth(
            String fault, long parameter, int count, long total, String category) {
        var bank = bank("2026-11-06");
        bank.plan(JSON.createObjectNode()
                .put("fault", fault)
                .put("parameter", parameter)
                .put("scenario", "scenario-7"));
        bank.receive("po_fault", 9680);
        // Later plan changes cannot alter the accepted transfer's frozen execution plan.
        bank.plan(JSON.createObjectNode());
        var lines = bank("2026-11-09").transactions();
        assertThat(lines).hasSize(count);
        assertThat(lines.stream().mapToLong(Bank.Line::amount).sum()).isEqualTo(total);
        assertThat(lines.stream().map(Bank.Line::bank_txn_id).distinct().count())
                .isEqualTo(count);
        assertThat(jdbc.sql("select balance from sim_bank.account")
                        .query(Long.class)
                        .single())
                .isEqualTo(total);
        assertThat(jdbc.sql("select expected_category from sim_truth.injected_fault")
                        .query(String.class)
                        .list())
                .containsExactly(category);
        assertThat(jdbc.sql("select scenario from sim_truth.injected_fault")
                        .query(String.class)
                        .single())
                .isEqualTo("scenario-7");
        if (fault.equals("SPLIT"))
            assertThat(lines).extracting(Bank.Line::amount).containsExactly(4840L, 4840L);
        if (fault.equals("FOREIGN_CURRENCY"))
            assertThat(lines).extracting(Bank.Line::currency).containsExactly("USD");
        if (fault.equals("UNKNOWN_CREDIT"))
            assertThat(lines).extracting(Bank.Line::reference).containsExactly("po_fault", "unknown_po_fault");
        if (fault.equals("DUPLICATE"))
            assertThat(lines).extracting(Bank.Line::reference).containsOnly("po_fault");
    }

    @Test
    void ac_5_11_delayCountsAdditionalBusinessDaysAndSpecificRuleWins() {
        var bank = bank("2026-11-06");
        bank.plan(JSON.readTree("{\"fault\":\"SHORT\",\"parameter\":1}"));
        bank.plan(JSON.readTree("{\"payout_ref\":\"po_late\",\"fault\":\"DELAY_DAYS\",\"parameter\":2}"));
        bank.receive("po_late", 9680);
        assertThat(bank("2026-11-11").transactions()).isEmpty();
        assertThat(bank("2026-11-12").transactions()).singleElement().satisfies(line -> {
            assertThat(line.booking_date()).isEqualTo(LocalDate.parse("2026-11-12"));
            assertThat(line.amount()).isEqualTo(9680);
        });
    }

    @Test
    void ac_5_20_pendingAdvancesAcrossDaysWithSameIdentityAndBothFilesStartAtOne() {
        var bank = bank("2026-11-06");
        bank.plan(JSON.readTree("{\"fault\":\"PENDING_FIRST\"}"));
        bank.receive("po_pending", 9680);
        var firstBank = bank("2026-11-09");
        var pending = firstBank.transactions().getFirst();
        String first = firstBank.statement(LocalDate.parse("2026-11-09"), "");
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(first).contains(",2026-11-09,2026-11-09,1,CAD\r\n").endsWith("T,1,0,0,0,0\r\n");
        assertThat(jdbc.sql("select balance from sim_bank.account")
                        .query(Long.class)
                        .single())
                .isZero();
        var nextBank = bank("2026-11-10");
        var booked = nextBank.transactions().getFirst();
        assertThat(booked).usingRecursiveComparison().ignoringFields("status").isEqualTo(pending);
        assertThat(booked.status()).isEqualTo("BOOKED");
        assertThat(nextBank.statement(LocalDate.parse("2026-11-10"), ""))
                .contains(",2026-11-10,2026-11-10,1,CAD\r\n")
                .endsWith("T,1,9680,0,0,9680\r\n");
        assertThat(nextBank.statement(LocalDate.parse("2026-11-09"), "")).isEqualTo(first);
        assertThat(bank("2026-11-12").statement(LocalDate.parse("2026-11-12"), ""))
                .endsWith("T,0,0,0,9680,9680\r\n");
        assertThat(jdbc.sql("select balance from sim_bank.account")
                        .query(Long.class)
                        .single())
                .isEqualTo(9680);
    }

    @Test
    void ac_5_10_csvEscapesMemoAndVersionsSurviveServiceRestart() {
        bank("2026-11-06").plan(JSON.createObjectNode().put("memo", "银行, \"memo\"\r\nnext"));
        bank("2026-11-06").receive("po_csv", 9680);
        LocalDate date = LocalDate.parse("2026-11-09");
        String first = bank("2026-11-09").statement(date, "");
        assertThat(first).contains("\"银行, \"\"memo\"\"\r\nnext\"\r\nT,1,");
        assertThat(bank("2026-11-09").statement(date, "")).isEqualTo(first);
        // An explicit bank-side correction fixture changes content, requiring a new file version.
        jdbc.sql("update sim_bank.txn set memo='corrected'").update();
        String second = bank("2026-11-09").statement(date, "");
        assertThat(second).contains(",2026-11-09,2026-11-09,2,CAD\r\n");
        assertThat(bank("2026-11-09").statement(date, "")).isEqualTo(second);
        assertThat(jdbc.sql("select content from sim_bank.statement order by version")
                        .query(String.class)
                        .list())
                .containsExactly(first, second);
        assertThat(bank("2026-11-10").statement(date.plusDays(1), "")).contains(",2026-11-10,2026-11-10,1,CAD\r\n");
    }

    @ParameterizedTest
    @CsvSource({"trailer_count,T;2;9680;0;0;9680", "trailer_sum,T;1;9681;0;0;9680", "balance,T;1;9680;0;0;9681"})
    void ac_5_12_corruptOnlyDamagesResponseAndRecordsTruth(String corrupt, String trailer) {
        bank("2026-11-06").receive("po_csv", 9680);
        var bank = bank("2026-11-09");
        var date = LocalDate.parse("2026-11-09");
        String clean = bank.statement(date, "");
        assertThat(bank.statement(date, corrupt)).endsWith(trailer.replace(';', ',') + "\r\n");
        assertThat(bank.statement(date, "")).isEqualTo(clean);
        assertThat(jdbc.sql("select count(*) from sim_bank.statement")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select fault from sim_truth.injected_fault")
                        .query(String.class)
                        .single())
                .isEqualTo(corrupt);
    }

    @Test
    void ac_5_6_duplicateNotificationsAndConcurrentExportsHaveOneEffect() throws Exception {
        var barrier = new CyclicBarrier(4);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 4; i++)
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    bank("2026-11-06").receive("po_race", 9680);
                    return bank("2026-11-09").statement(LocalDate.parse("2026-11-09"), "");
                }));
            String expected = results.getFirst().get(20, TimeUnit.SECONDS);
            for (var result : results)
                assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(expected);
        }
        assertThat(jdbc.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from sim_bank.txn")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from sim_bank.statement")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThatThrownBy(() -> bank("2026-11-09").receive("po_race", 9679))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void ac_5_4_executionRollsBackWithBalanceFailureAndCanRecover() {
        bank("2026-11-06").receive("po_rollback", 9680);
        jdbc.sql("alter table sim_bank.account add constraint test_balance_failure check (balance=0)")
                .update();
        try {
            assertThatThrownBy(() -> bank("2026-11-09").advance())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.sql("select count(*) from sim_bank.txn")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("select executed from sim_bank.incoming_transfer")
                            .query(Boolean.class)
                            .single())
                    .isFalse();
        } finally {
            jdbc.sql("alter table sim_bank.account drop constraint test_balance_failure")
                    .update();
        }
        assertThat(bank("2026-11-09").transactions()).hasSize(1);
    }

    @Test
    void ac_5_13_bankBytecodeCannotDependOnProcessorOrMainApplication() throws Exception {
        try (var paths = Files.walk(Path.of("target/classes/dev/ledgerlab/sim/bank"))) {
            var files = paths.filter(path -> path.toString().endsWith(".class")).toList();
            assertThat(files).isNotEmpty();
            for (Path path : files) {
                String bytecode = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertThat(bytecode)
                        .as(path.toString())
                        .doesNotContain(
                                "dev/ledgerlab/sim/processor",
                                "sim_processor",
                                "dev/ledgerlab/shared",
                                "dev/ledgerlab/ledger",
                                "dev/ledgerlab/settlement");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "readonly", "migrator"})
    void ac_5_13_otherRolesCannotReadWriteOrTruncateBankAndTruth(String role) throws Exception {
        try (var connection = TestDatabase.role(database.getJdbcUrl(), role).getConnection();
                var sql = connection.createStatement()) {
            for (String table : List.of(
                    "sim_bank.account",
                    "sim_bank.incoming_transfer",
                    "sim_bank.txn",
                    "sim_bank.fault_plan",
                    "sim_bank.statement",
                    "sim_truth.injected_fault")) {
                for (String operation : List.of("select * from ", "delete from ", "truncate ")) {
                    assertThatThrownBy(() -> sql.execute(operation + table))
                            .isInstanceOf(SQLException.class)
                            .extracting(error -> ((SQLException) error).getSQLState())
                            .isEqualTo("42501");
                }
            }
            assertThatThrownBy(() -> sql.execute("select nextval('sim_truth.injected_fault_id_seq')"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo("42501");
        }
    }

    @Test
    void ac_5_13_databaseRejectsEachInvalidValueAndForeignKey() throws Exception {
        bank("2026-11-06").plan(JSON.readTree("{\"fault\":\"SHORT\",\"parameter\":1}"));
        bank("2026-11-06").receive("po_constraints", 9680);
        bank("2026-11-09").statement(LocalDate.parse("2026-11-09"), "");
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var sql = connection.createStatement()) {
            for (String invalid : List.of(
                    "sim_bank.account set currency='USD'",
                    "sim_bank.account set balance=-1",
                    "sim_bank.account set balance=9007199254740992",
                    "sim_bank.fault_plan set fault='BAD'",
                    "sim_bank.fault_plan set parameter=-1",
                    "sim_bank.fault_plan set parameter=100000000",
                    "sim_bank.incoming_transfer set claimed_amount=0",
                    "sim_bank.incoming_transfer set claimed_amount=9007199254740992",
                    "sim_bank.incoming_transfer set plan='[]'",
                    "sim_bank.txn set direction='OTHER'",
                    "sim_bank.txn set amount=0",
                    "sim_bank.txn set amount=100000000",
                    "sim_bank.txn set currency='EUR'",
                    "sim_bank.txn set status='OTHER'",
                    "sim_bank.txn set booking_date=visible_date-1",
                    "sim_bank.statement set version=0")) {
                assertThatThrownBy(() -> sql.executeUpdate("update " + invalid))
                        .as(invalid)
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("23514");
            }
            for (String invalid : List.of(
                    "sim_bank.incoming_transfer set account_ref='missing'",
                    "sim_bank.txn set account_ref='missing'",
                    "sim_bank.txn set payout_ref='missing'",
                    "sim_bank.statement set account_ref='missing'"))
                assertThatThrownBy(() -> sql.executeUpdate("update " + invalid))
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("23503");
            for (String table : List.of(
                    "sim_bank.account",
                    "sim_bank.incoming_transfer",
                    "sim_bank.txn",
                    "sim_bank.fault_plan",
                    "sim_bank.statement",
                    "sim_truth.injected_fault")) {
                assertThatThrownBy(() -> sql.executeUpdate(
                                "insert into " + table + " overriding system value select * from " + table))
                        .isInstanceOf(SQLException.class)
                        .extracting(error -> ((SQLException) error).getSQLState())
                        .isEqualTo("23505");
                int separator = table.indexOf('.');
                var columns = jdbc.sql(
                                "select column_name from information_schema.columns where table_schema=? and table_name=? and is_nullable='NO'")
                        .param(table.substring(0, separator))
                        .param(table.substring(separator + 1))
                        .query(String.class)
                        .list();
                for (String column : columns) {
                    if (table.equals("sim_truth.injected_fault") && "id".equals(column)) continue;
                    assertThatThrownBy(() -> sql.executeUpdate("update " + table + " set " + column + "=null"))
                            .isInstanceOf(SQLException.class)
                            .extracting(error -> ((SQLException) error).getSQLState())
                            .isEqualTo("23502");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "{\"extra\":1}",
                "{\"fault\":\"BAD\"}",
                "{\"payout_ref\":\"bad\"}",
                "{\"scenario\":\"\"}",
                "{\"fault\":1}",
                "{\"fault\":\"SHORT\"}",
                "{\"parameter\":1.5}",
                "{\"parameter\":\"2\"}",
                "{\"parameter\":9223372036854775808}",
                "{\"parameter\":-1}",
                "{\"fault\":\"DELAY_DAYS\",\"parameter\":731}",
                "{\"fault\":\"DUPLICATE\",\"parameter\":1}"
            })
    void ac_5_10_badPlansHaveNoWrites(String request) {
        assertThatThrownBy(() -> bank("2026-11-06").plan(JSON.readTree(request)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("select count(*) from sim_bank.fault_plan")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void ac_5_10_invalidDatesAndZeroLinesRejectWithoutPartialEffects() {
        var bank = bank("2026-11-06");
        bank.plan(JSON.readTree("{\"fault\":\"SHORT\",\"parameter\":9680}"));
        assertThatThrownBy(() -> bank.receive("po_zero", 9680)).isInstanceOf(RuntimeException.class);
        bank.plan(JSON.readTree("{\"fault\":\"SPLIT\"}"));
        assertThatThrownBy(() -> bank.receive("po_one", 1)).isInstanceOf(RuntimeException.class);
        for (long amount : new long[] {0, -1, Long.MAX_VALUE})
            assertThatThrownBy(() -> bank.receive("po_invalid", amount)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bank.receive("invalid", 100)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bank.statement(LocalDate.parse("2026-11-07"), ""))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bank.statement(LocalDate.parse("2025-12-31"), ""))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bank.statement(LocalDate.parse("2026-11-06"), "unknown"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bank("2027-12-31").receive("po_outside", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from sim_truth.injected_fault")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void ac_5_10_largePayoutProducesValidIntegerLinesAndDebitTrailerUsesBookedOnly() {
        bank("2026-11-06").receive("po_large", 100000000);
        var bank = bank("2026-11-09");
        assertThat(bank.transactions()).extracting(Bank.Line::amount).containsExactly(99999999L, 1L);
        jdbc.sql("update sim_bank.txn set direction='DEBIT' where amount=1").update();
        assertThat(bank.statement(LocalDate.parse("2026-11-09"), "")).endsWith("T,2,99999999,1,0,99999998\r\n");
        assertThat(bank("2026-11-10").statement(LocalDate.parse("2026-11-10"), ""))
                .endsWith("T,0,0,0,99999998,99999998\r\n");
    }
}
