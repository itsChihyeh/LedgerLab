package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InvariantQueryTest {
    private TestDatabase.Handle db;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        db = TestDatabase.freshMigrated();
        mvc = MockMvcBuilders.standaloneSetup(new InvariantQueryController(
                        db.readonly(),
                        Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC),
                        dev.ledgerlab.testsupport.InvariantChecks.all()))
                .build();
    }

    @AfterEach
    void close() {
        db.drop();
    }

    @Test
    void ac_8_4_cleanBooksUseReadonlyViewsAndInjectedClock() throws Exception {
        mvc.perform(get("/api/audit/invariants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").value("2026-11-02T00:00:00Z"))
                .andExpect(jsonPath("$.violations").isEmpty());
        try (var connection = db.readonly().getConnection();
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_user")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("readonly");
            }
            assertThat(assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM ledger.account"))
                            .getSQLState())
                    .isEqualTo("42501");
            assertThat(assertThrows(
                                    SQLException.class,
                                    () -> statement.executeUpdate(
                                            "UPDATE ledger.v_account SET balance = 1 WHERE code = 1101"))
                            .getSQLState())
                    .isEqualTo("42501");
        }
        mvc.perform(post("/api/audit/invariants")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void ac_8_4_validPostedBooksAlsoPassThroughReadonlyViews() throws Exception {
        new LedgerFixture(db).s1();
        // This fixture posts a capture without a payment row, so only the payment-vs-2101 check (INV-9) can differ.
        mvc.perform(get("/api/audit/invariants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].invariant").value("INV-9"));
    }

    @Test
    void ac_8_4_corruptStoredBalanceReportsExistingAuditViolations() throws Exception {
        Sql.execute(db.migrator(), "UPDATE ledger.account SET balance = 1 WHERE code = 1101");
        mvc.perform(get("/api/audit/invariants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[0].invariant").value("INV-2"))
                .andExpect(jsonPath("$.violations[0].detail")
                        .value("account 1101: stored balance 1 version 0, postings give 0 over 0 postings"))
                .andExpect(jsonPath("$.violations[1].invariant").value("INV-3"));
    }
}
