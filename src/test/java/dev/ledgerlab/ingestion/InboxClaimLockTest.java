package dev.ledgerlab.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;

/** Verifies non-key takeover despite the payment foreign-key lock, including stale-epoch fencing. */
class InboxClaimLockTest {
    @Test
    void ac_4_6_noKeyUpdateTakesOverDespitePaymentForeignKeyAndFencesOldEpoch() throws Exception {
        var db = TestDatabase.freshMigrated();
        try (var a = db.app().getConnection();
                var b = db.app().getConnection()) {
            try (var setup = a.createStatement()) {
                setup.executeUpdate("""
                        INSERT INTO io.inbox_event
                            (event_id, event_type, api_version, livemode, payload, payload_sha256,
                             signed_at, occurred_at, received_at, recorded_at, status, next_attempt_at,
                             lease_owner, lease_until, lease_epoch)
                        VALUES ('evt_lock', 'charge.succeeded', '2026-09-30.endive', false,
                            '{}'::bytea, encode(sha256('{}'::bytea), 'hex'),
                            '2026-10-21Z', '2026-10-21Z', '2026-10-21Z', '2026-10-21Z',
                            'RECEIVED', '2026-10-21Z', 'worker-a', '2026-10-21T00:00:30Z', 1)
                        """);
            }
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            try (var business = a.createStatement();
                    var claim = b.createStatement()) {
                business.executeQuery("SELECT * FROM ledger.funds_lock FOR UPDATE")
                        .close();
                business.executeUpdate("""
                        INSERT INTO core.payment
                            (id, processor_payment_ref, amount, expected_fee, status, source_event_id,
                             occurred_at, received_at, recorded_at)
                        VALUES ('pay_00000000000000000000000001', 'ch_lock', 10000, 320, 'CAPTURED',
                            'evt_lock', '2026-10-21Z', '2026-10-21Z', '2026-10-21Z')
                        """);
                String eligible = """
                        SELECT event_id FROM io.inbox_event
                        WHERE status = 'RECEIVED' AND next_attempt_at <= '2026-10-21T00:01:00Z'
                            AND lease_until < '2026-10-21T00:01:00Z'
                        ORDER BY received_at LIMIT 1
                        """;
                try (var rows = claim.executeQuery(eligible + " FOR UPDATE SKIP LOCKED")) {
                    assertThat(rows.next())
                            .as("expired lease is skipped because of payment's FK lock")
                            .isFalse();
                }
                try (var rows = claim.executeQuery(eligible + " FOR NO KEY UPDATE SKIP LOCKED")) {
                    assertThat(rows.next())
                            .as("non-key lock is compatible with the FK lock")
                            .isTrue();
                    assertThat(rows.getString(1)).isEqualTo("evt_lock");
                }
                assertThat(claim.executeUpdate("""
                        UPDATE io.inbox_event SET lease_owner = 'worker-b', lease_epoch = lease_epoch + 1,
                            lease_until = '2026-10-21T00:01:30Z'
                        WHERE event_id = 'evt_lock' AND lease_epoch = 1
                        """)).isEqualTo(1);
                b.commit();
                assertThat(business.executeUpdate("""
                        UPDATE io.inbox_event SET status = 'PROCESSED', result = 'APPLIED'
                        WHERE event_id = 'evt_lock' AND lease_epoch = 1 AND status = 'RECEIVED'
                        """))
                        .as("old worker is fenced after a non-key takeover")
                        .isZero();
            } finally {
                a.rollback();
                b.rollback();
            }
        } finally {
            db.drop();
        }
    }
}
