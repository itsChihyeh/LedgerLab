package dev.ledgerlab.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ledger.LedgerAudit;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Shared by T04 and subsequent refund lifecycle tests. */
public final class RefundAssertions {
    private RefundAssertions() {}

    public static void noBankCashPostings(JdbcClient jdbc) {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ledger.posting p JOIN ledger.journal_entry e ON e.id = p.entry_id
                WHERE e.entry_type LIKE 'REFUND_%' AND p.account_code = 1103
                """).query(Long.class).single())
                .as("AC-3.14 refund never touches bank cash")
                .isZero();
    }

    public static void invariants(JdbcClient jdbc, LedgerAudit audit) {
        assertThat(audit.check()).as("INV-1..5 and INV-8").isEmpty();
        assertThat(jdbc.sql("""
                SELECT p.id FROM core.payment p LEFT JOIN core.refund r ON r.payment_id = p.id
                    AND r.status NOT IN ('FAILED','CANCELLED')
                GROUP BY p.id HAVING coalesce(sum(r.amount), 0) > p.amount
                    OR coalesce(sum(r.amount), 0) <> p.refunded_amount
                """).query(String.class).list())
                .as("INV-7 and reserved refund cache")
                .isEmpty();
        assertThat(jdbc.sql("""
                SELECT balance = (SELECT coalesce(sum(amount),0) FROM core.refund
                    WHERE status IN ('AWAITING_FUNDS','READY','SUBMITTED','UNKNOWN'))
                FROM ledger.account WHERE code = 2103
                """).query(Boolean.class).single())
                .as("INV-9 refund payable")
                .isTrue();
        noBankCashPostings(jdbc);
        assertThat(jdbc.sql("""
                SELECT subject_id FROM ledger.journal_entry WHERE subject_type='refund'
                GROUP BY subject_id HAVING count(*) FILTER (WHERE entry_type='REFUND_ACCEPTED') > 1
                    OR count(*) FILTER (WHERE entry_type IN ('REFUND_EXECUTED','REFUND_FAILED','REFUND_CANCELLED')) > 1
                """).query(String.class).list())
                .as("INV-10 each refund is reserved and settled or released at most once")
                .isEmpty();
    }
}
