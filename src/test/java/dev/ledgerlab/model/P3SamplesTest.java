package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.RefundAssertions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class P3SamplesTest {
    @Test
    void ac_1_4c_sampleAEveryCellAndPostingThroughStepEight() {
        try (var s = new Sample()) {
            s.charge("p1", 10000);
            s.check(10000, 0, 0, 10000, 0, 0, 0, 10000);
            s.batch("b1", 9680, "p1", 10000, 320, null, 0);
            s.check(0, 9680, 0, 0, 9680, 0, 0, 9680);
            // Step 2': only the P5 gate's arithmetic, no P5 endpoint or ledger fixture.
            assertThat(s.app.balances().get(1103)).isZero();
            s.check(0, 9680, 0, 0, 9680, 0, 0, 9680);
            s.bank("b1", 9680);
            s.check(0, 0, 9680, 0, 9680, 0, 0, 9680);
            s.charge("p2", 5000);
            s.check(5000, 0, 9680, 5000, 9680, 0, 0, 14680);
            String refund = s.accept("p1", "r1", 2000);
            s.check(5000, 0, 9680, 5000, 7680, 2000, 0, 14680);
            s.execute(refund, "r1", 2000, "succeeded");
            s.check(3000, 0, 9680, 5000, 7680, 0, 0, 12680);
            s.batch("b2", 2825, "p2", 5000, 175, "r1", 2000);
            s.check(0, 2825, 9680, 0, 12505, 0, 0, 12505);
            s.bank("b2", 2825);
            s.check(0, 0, 12505, 0, 12505, 0, 0, 12505);
        }
    }

    @Test
    void ac_1_4c_sampleDEveryCellAndPostingOnProcessorFailure() {
        try (var s = new Sample()) {
            // 5180 - (30 + round_half_up(5180 * 2.9%)) = 5000.
            s.charge("p3", 5180);
            s.check(5180, 0, 0, 5180, 0, 0, 0, 5180);
            s.batch("b3", 5000, "p3", 5180, 180, null, 0);
            s.check(0, 5000, 0, 0, 5000, 0, 0, 5000);
            s.bank("b3", 5000);
            s.check(0, 0, 5000, 0, 5000, 0, 0, 5000);
            String refund = s.accept("p3", "r3", 1000);
            s.check(0, 0, 5000, 0, 4000, 1000, 0, 5000);
            s.execute(refund, "r3", 1000, "failed");
            s.check(0, 0, 5000, 0, 5000, 0, 0, 5000);
        }
    }

    static final class Sample implements AutoCloseable {
        final ModelApplication app = new ModelApplication();
        final Map<String, List<String>> expected = new TreeMap<>();
        int event;
        int file;

        void charge(String alias, long amount) {
            String charge = "ch_sample_" + alias;
            app.deliver(new PaymentModel.Input(
                    "evt_sample_" + ++event,
                    charge,
                    "charge.succeeded",
                    Long.toString(amount),
                    Long.toString(amount),
                    "cad",
                    true,
                    false,
                    false));
            app.drain("sample-charge");
            entry("payment:" + charge + ":captured", "PAYMENT_CAPTURED", "1101:D:" + amount, "2101:C:" + amount);
        }

        void batch(String alias, long net, String payment, long gross, long fee, String refund, long refunded) {
            String payout = "po_sample_" + alias;
            String first = """
                    {"object":"list","has_more":true,"data":[{"id":"txn_%s_payment","type":"charge",
                    "source":"ch_sample_%s","amount":%d,"fee":%d,"net":%d,"currency":"cad","created":1792591200}]}
                    """.formatted(alias, payment, gross, fee, Math.subtractExact(gross, fee));
            String last = refund == null
                    ? "{\"object\":\"list\",\"has_more\":false,\"data\":[]}"
                    : """
                    {"object":"list","has_more":false,"data":[{"id":"txn_%s_refund","type":"refund",
                    "source":"re_sample_%s","amount":%d,"fee":0,"net":%d,"currency":"cad","created":1792591200}]}
                    """.formatted(alias, refund, Math.negateExact(refunded), Math.negateExact(refunded));
            app.processor(payout, new ProcessorReplay.Pages(first, last, false));
            app.deliverRaw("""
                    {"id":"evt_sample_%d","type":"payout.reconciliation_completed","api_version":"2026-09-30.endive",
                    "livemode":false,"created":1792591200,"data":{"object":{"id":"%s","amount":%d,
                    "currency":"cad","automatic":true,"method":"standard","status":"paid",
                    "reconciliation_status":"completed","arrival_date":1792677600,"created":1792591200}}}
                    """.formatted(++event, payout, net).getBytes(StandardCharsets.UTF_8));
            app.drain("sample-batch");
            entry(
                    "batch:" + payout + ":settled",
                    "BATCH_SETTLED",
                    "1102:D:" + net,
                    "2101:D:" + fee,
                    "1101:C:" + Math.addExact(net, fee),
                    "2101:D:" + Math.subtractExact(gross, fee),
                    "2102:C:" + Math.subtractExact(gross, fee));
        }

        void bank(String batch, long amount) {
            String id = "bank_sample_" + batch;
            app.bank(("H,BANK_STATEMENT,SIM-CAD-001,2026-10-21,2026-10-21,%d,CAD\r\n"
                            + "D,%s,CREDIT,%d,CAD,BOOKED,po_sample_%s,2026-10-21,2026-10-21,sample\r\n"
                            + "T,1,%d,0,0,%d\r\n")
                    .formatted(++file, id, amount, batch, amount, amount)
                    .getBytes(StandardCharsets.UTF_8));
            entry("bank_txn:" + id, "BANK_DEPOSIT_RECEIVED", "1103:D:" + amount, "1102:C:" + amount);
        }

        String accept(String payment, String alias, long amount) {
            var response = app.api("/api/refunds", "sample-" + alias, """
                    {"payment_ref":"ch_sample_%s","merchant_refund_ref":"%s","amount":%d}
                    """.formatted(payment, alias, amount));
            assertThat(response.status()).as(response.body()).isEqualTo(201);
            var json = new ObjectMapper().readTree(response.body());
            assertThat(json.path("status").asString()).isEqualTo("READY");
            String id = json.path("refund_id").asString();
            entry("refund:" + id + ":accepted", "REFUND_ACCEPTED", "2102:D:" + amount, "2103:C:" + amount);
            return id;
        }

        void execute(String id, String alias, long amount, String status) {
            app.refunds.replies.add(new RefundReplay.Reply(
                    "CREATE", 200, "{\"id\":\"re_sample_" + alias + "\",\"status\":\"" + status + "\"}", null));
            app.outbound();
            assertThat(app.refunds.replies).isEmpty();
            boolean success = status.equals("succeeded");
            entry(
                    "refund:" + id + (success ? ":executed" : ":failed"),
                    success ? "REFUND_EXECUTED" : "REFUND_FAILED",
                    "2103:D:" + amount,
                    (success ? "1101:C:" : "2102:C:") + amount);
            assertThat(app.jdbc
                            .sql("SELECT status FROM core.refund WHERE id=:id")
                            .param("id", id)
                            .query(String.class)
                            .single())
                    .isEqualTo(success ? "SUCCEEDED" : "FAILED");
        }

        void entry(String key, String type, String... postings) {
            expected.put(
                    key,
                    java.util.Arrays.stream(postings)
                            .map(p -> type + ":" + p)
                            .sorted()
                            .toList());
        }

        void check(long pr, long sit, long cash, long mp, long ma, long rp, long pit, long total) {
            assertThat(app.balances())
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of(1101, pr, 1102, sit, 1103, cash, 2101, mp, 2102, ma, 2103, rp, 2104, pit, 2105, 0L));
            assertThat(Math.addExact(Math.addExact(pr, sit), cash)).isEqualTo(total);
            assertThat(Math.addExact(Math.addExact(mp, ma), Math.addExact(rp, pit)))
                    .isEqualTo(total);
            Map<String, List<String>> actual = new TreeMap<>();
            app.jdbc
                    .sql("""
                    SELECT e.source_key, e.entry_type, p.account_code, p.side, p.amount
                    FROM ledger.journal_entry e JOIN ledger.posting p ON p.entry_id=e.id
                    """)
                    .query((rs, n) -> {
                        actual.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(rs.getString(2) + ":" + rs.getInt(3) + ":" + rs.getString(4) + ":"
                                        + rs.getLong(5));
                        return 1;
                    })
                    .list();
            actual.values().forEach(list -> list.sort(String::compareTo));
            assertThat(actual)
                    .as("every entry and posting, including multiplicity")
                    .isEqualTo(expected);
            RefundAssertions.invariants(app.jdbc, app.audit);
        }

        @Override
        public void close() {
            app.close();
        }
    }
}
