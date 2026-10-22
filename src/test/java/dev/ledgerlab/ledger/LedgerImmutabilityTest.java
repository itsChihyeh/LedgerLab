package dev.ledgerlab.ledger;

import static dev.ledgerlab.testsupport.LedgerFixture.only;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AC-1.8: entries cannot be changed, removed or truncated, and account attributes cannot change. Permission
 * refusals (42501) are proven as {@code app}; the triggers behind them are proven as the owner {@code migrator},
 * because {@code app} never reaches them.
 */
class LedgerImmutabilityTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String ACCOUNT_ATTRIBUTE_IMMUTABLE = "LL001";
    private static final String LEDGER_REWRITE = "LL004";

    private LedgerFixture fixture;
    private String entryId;

    @BeforeEach
    void givenS1() {
        fixture = new LedgerFixture(TestDatabase.freshMigrated());
        entryId = fixture.s1().entryId();
    }

    @AfterEach
    void tearDown() {
        fixture.db().drop();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE ledger.journal_entry SET total_amount = 1",
                "UPDATE ledger.journal_entry SET source_key = 'payment:ch_other:captured'",
                "UPDATE ledger.posting SET amount = 1",
                "DELETE FROM ledger.journal_entry",
                "DELETE FROM ledger.posting",
                "TRUNCATE ledger.posting",
                "TRUNCATE ledger.journal_entry CASCADE",
                "TRUNCATE ledger.account CASCADE",
                "DELETE FROM ledger.account WHERE code = 2105",
                "INSERT INTO ledger.account (code, name, kind, normal_side, allow_negative)"
                        + " VALUES (1999, 'extra', 'ASSET', 'D', true)",
                "UPDATE ledger.account SET kind = 'LIABILITY' WHERE code = 1103",
                "UPDATE ledger.account SET normal_side = 'C' WHERE code = 1103",
                "UPDATE ledger.account SET allow_negative = true WHERE code = 1103",
                "UPDATE ledger.account SET code = 1199 WHERE code = 1103",
                "UPDATE ledger.account SET name = 'renamed' WHERE code = 1103"
            })
    void ac_1_8_appCannotRewriteEntriesOrAccountAttributes(String statement) {
        Sql.assertRejected(INSUFFICIENT_PRIVILEGE, fixture.db().app(), statement);

        assertS1Unchanged();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE ledger.account SET kind = 'LIABILITY' WHERE code = 1103",
                "UPDATE ledger.account SET normal_side = 'C' WHERE code = 1103",
                "UPDATE ledger.account SET allow_negative = true WHERE code = 1103",
                "UPDATE ledger.account SET code = 1199 WHERE code = 1103",
                "UPDATE ledger.account SET name = 'renamed' WHERE code = 1103"
            })
    void ac_1_8_accountAttributeTriggerStopsTheOwnerToo(String statement) {
        Sql.assertRejected(ACCOUNT_ATTRIBUTE_IMMUTABLE, fixture.db().migrator(), statement);

        assertS1Unchanged();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE ledger.journal_entry SET total_amount = 1",
                "UPDATE ledger.posting SET amount = 1",
                "DELETE FROM ledger.posting",
                "TRUNCATE ledger.posting",
                "TRUNCATE ledger.journal_entry CASCADE"
            })
    void ownerCannotRewriteEntriesEither(String statement) {
        Sql.assertRejected(LEDGER_REWRITE, fixture.db().migrator(), statement);

        assertS1Unchanged();
    }

    @Test
    void ac_1_8_wrongButLegalBalanceIsAcceptedAndThenCaughtByTheInv2Check() throws SQLException {
        Sql.execute(fixture.db().app(), "UPDATE ledger.account SET balance = 9999 WHERE code = 1101");

        assertThat(fixture.balances().get(Account.PROCESSOR_RECEIVABLE)).isEqualTo(9999);
        assertThat(fixture.audit().check())
                .extracting(LedgerAudit.InvariantViolation::invariant)
                .contains("INV-2", "INV-3");
        assertThat(fixture.audit().check())
                .filteredOn(v -> v.invariant().equals("INV-2"))
                .singleElement()
                .satisfies(v -> assertThat(v.detail()).contains("account 1101", "9999", "10000"));
    }

    @Test
    void ac_1_8_versionTamperingIsCaughtByTheInv2Check() throws SQLException {
        Sql.execute(fixture.db().app(), "UPDATE ledger.account SET version = 7 WHERE code = 2101");

        assertThat(fixture.audit().check())
                .extracting(LedgerAudit.InvariantViolation::invariant)
                .containsExactly("INV-2");
    }

    private void assertS1Unchanged() {
        assertThat(fixture.count("ledger.journal_entry")).isEqualTo(1);
        assertThat(fixture.count("ledger.posting")).isEqualTo(2);
        Map<Account, Long> s1 = only(Account.PROCESSOR_RECEIVABLE, 10000, Account.MERCHANT_PENDING, 10000);
        assertThat(fixture.balances()).isEqualTo(s1);
        assertThat(fixture.count("ledger.account WHERE code = 1103 AND kind = 'ASSET' AND normal_side = 'D'"
                        + " AND NOT allow_negative AND name = 'bank_cash'"))
                .isEqualTo(1);
        assertThat(fixture.audit().check()).isEmpty();
        assertThat(entryId).isNotBlank();
    }
}
