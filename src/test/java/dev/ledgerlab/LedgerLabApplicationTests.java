package dev.ledgerlab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.LedgerAudit;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Walking skeleton: the real application context on a blank database. Flyway migrates it as {@code migrator},
 * the application then runs as {@code app}, and a posting goes through the whole chain (ADR-0014: test which
 * credential the call chain really uses).
 */
@SpringBootTest
class LedgerLabApplicationTests {

    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
        registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> "whsec_test_only");
        registry.add("STRIPE_API_KEY", () -> "");
    }

    @AfterAll
    static void dropDatabase() {
        DB.drop();
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Ledger ledger;

    @Autowired
    LedgerAudit audit;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void applicationRunsAsAppAfterFlywayMigratedAsMigrator() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("app");
        assertThat(JdbcClient.create(DB.superuser())
                        .sql("SELECT tableowner FROM pg_tables WHERE schemaname = 'ledger' AND tablename = 'posting'")
                        .query(String.class)
                        .single())
                .isEqualTo("migrator");
        assertThat(JdbcClient.create(DB.superuser())
                        .sql("SELECT count(*) FROM flyway.flyway_schema_history WHERE success")
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    @Test
    void postsS1ThroughTheApplicationContext() {
        transactions.executeWithoutResult(status -> {
            ledger.lockFunds();
            ledger.post(new EntryRequest(
                    EntryType.PAYMENT_CAPTURED,
                    "payment:ch_skeleton:captured",
                    Instant.parse("2026-10-21T14:00:00Z"),
                    SubjectType.PAYMENT,
                    "pay_skeleton",
                    List.of(
                            PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 10000),
                            PostingLine.credit(Account.MERCHANT_PENDING, 10000))));
        });

        assertThat(jdbc.sql("SELECT balance FROM ledger.account WHERE code = 1101")
                        .query(Long.class)
                        .single())
                .isEqualTo(10000);
        assertThat(audit.check()).isEmpty();
    }
}
