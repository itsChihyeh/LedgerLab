package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class P5SamplesTest {
    @Test
    void ac_1_4e_sampleAEveryCellIncludingRealBankUnknownRecoveryAndProcessorPending() throws Exception {
        try (var bank = new SampleBankProcess();
                var s = new P3SamplesTest.Sample(new ModelApplication(bank.url()))) {
            throughEight(s);
            String payout = reserve(s, 12505, "a");
            s.check(0, 0, 12505, 0, 0, 0, 12505, 12505);
            bank.plan(payout, "EXECUTE_DROP_RESPONSE", 0);
            s.app.payoutOutbound();
            status(s, payout, "UNKNOWN");
            s.check(0, 0, 12505, 0, 0, 0, 12505, 12505);
            s.app.advance(3601);
            s.app.payoutOutbound();
            status(s, payout, "ACCEPTED");
            s.check(0, 0, 12505, 0, 0, 0, 12505, 12505);
            assertThat(bank.json("GET", "/sim/bank/payments", "").size()).isEqualTo(1);
            bank.advance(86400);
            s.app.advance(86400);
            s.app.bank(bank.statement("2026-10-22"));
            s.entry("bank_txn:bt_" + payout + "_1", "PAYOUT_PAID", "2104:D:12505", "1103:C:12505");
            status(s, payout, "PAID");
            s.check(0, 0, 0, 0, 0, 0, 0, 0);
            String refund = s.accept("p2", "r2", 3000, "AWAITING_FUNDS");
            s.check(0, 0, 0, 0, -3000, 3000, 0, 0);
            rejected(s, 1, "debt", "MERCHANT_BALANCE");
            s.check(0, 0, 0, 0, -3000, 3000, 0, 0);
            s.charge("p3", 4000);
            s.check(4000, 0, 0, 4000, -3000, 3000, 0, 4000);
            s.batch("b3", 3854, "p3", 4000, 146, null, 0);
            s.check(0, 3854, 0, 0, 854, 3000, 0, 3854);
            s.bank("b3", 3854);
            s.check(0, 0, 3854, 0, 854, 3000, 0, 3854);
            s.app.refunds.replies.add(
                    new RefundReplay.Reply("CREATE", 200, "{\"id\":\"re_sample_r2\",\"status\":\"pending\"}", null));
            s.app.outbound();
            assertThat(s.app.jdbc
                            .sql("SELECT status FROM core.refund WHERE id=?")
                            .param(refund)
                            .query(String.class)
                            .single())
                    .isEqualTo("SUBMITTED");
            s.check(0, 0, 3854, 0, 854, 3000, 0, 3854);
            s.app.advance(86400);
            s.check(0, 0, 3854, 0, 854, 3000, 0, 3854);
            assertThat(freeCash(s)).isEqualTo(3854);
            rejected(s, 855, "step17", "MERCHANT_BALANCE");
            s.check(0, 0, 3854, 0, 854, 3000, 0, 3854);
            s.charge("p4", 5000);
            s.check(5000, 0, 3854, 5000, 854, 3000, 0, 8854);
            s.app.deliverRaw(("""
                    {"id":"evt_pending_succeeded","type":"refund.updated","api_version":"2026-09-30.endive","livemode":false,
                    "created":1792591200,"data":{"object":{"id":"re_sample_r2","object":"refund","charge":"ch_sample_p2",
                    "amount":3000,"currency":"cad","status":"succeeded","created":1792591200,
                    "metadata":{"ledgerlab_refund_id":"%s"}}}}
                    """).formatted(refund).getBytes(StandardCharsets.UTF_8));
            s.app.drain("pending-now-succeeded");
            s.entry("refund:" + refund + ":executed", "REFUND_EXECUTED", "2103:D:3000", "1101:C:3000");
            s.check(2000, 0, 3854, 5000, 854, 0, 0, 5854);
            s.batch("b4", 1825, "p4", 5000, 175, "r2", 3000);
            s.check(0, 1825, 3854, 0, 5679, 0, 0, 5679);
            assertThat(freeCash(s)).isEqualTo(3854);
            rejected(s, 3855, "step19", "FREE_CASH");
            s.check(0, 1825, 3854, 0, 5679, 0, 0, 5679);
        }
    }

    @Test
    void ac_1_4e_sampleBUnknownHoldsReservationUntilBankRejects() {
        try (var s = new P3SamplesTest.Sample()) {
            throughEight(s);
            String id = reserve(s, 12505, "b");
            s.check(0, 0, 12505, 0, 0, 0, 12505, 12505);
            receipt(s, id, "SUBMIT", "TIMEOUT");
            status(s, id, "UNKNOWN");
            s.check(0, 0, 12505, 0, 0, 0, 12505, 12505);
            rejected(s, 100, "b-again", "MERCHANT_BALANCE");
            s.check(0, 0, 12505, 0, 0, 0, 12505, 12505);
            s.app.advance(3601);
            receipt(s, id, "QUERY", "REJECTED");
            s.entry("payout:" + id + ":failed", "PAYOUT_FAILED", "2104:D:12505", "2102:C:12505");
            status(s, id, "FAILED");
            s.check(0, 0, 12505, 0, 12505, 0, 0, 12505);
            assertThat(s.app.jdbc
                            .sql("SELECT count(*) FROM recon.bank_line WHERE direction='DEBIT'")
                            .query(Long.class)
                            .single())
                    .isZero();
        }
    }

    @Test
    void ac_1_4e_sampleCBankReturnsPreviouslyPaidPayout() throws Exception {
        try (var bank = new SampleBankProcess();
                var s = new P3SamplesTest.Sample(new ModelApplication(bank.url()))) {
            throughEight(s);
            String id = reserve(s, 12505, "c");
            s.check(0, 0, 12505, 0, 0, 0, 12505, 12505);
            bank.plan(id, "RETURN_AFTER_DAYS", 2);
            s.app.payoutOutbound();
            status(s, id, "ACCEPTED");
            s.check(0, 0, 12505, 0, 0, 0, 12505, 12505);
            bank.advance(86400);
            s.app.advance(86400);
            s.app.bank(bank.statement("2026-10-22"));
            s.entry("bank_txn:bt_" + id + "_1", "PAYOUT_PAID", "2104:D:12505", "1103:C:12505");
            s.check(0, 0, 0, 0, 0, 0, 0, 0);
            bank.advance(4 * 86400);
            s.app.advance(4 * 86400);
            s.app.bank(bank.statement("2026-10-26"));
            s.entry("bank_txn:bt_" + id + "_R", "PAYOUT_RETURNED", "1103:D:12505", "2102:C:12505");
            status(s, id, "RETURNED");
            s.check(0, 0, 12505, 0, 12505, 0, 0, 12505);
            assertThat(s.app.jdbc
                            .sql("SELECT return_reason FROM core.payout")
                            .query(String.class)
                            .single())
                    .isEqualTo("ACCOUNT_CLOSED");
        }
    }

    @Test
    void ac_1_4e_sampleFDuplicateRealCreditCannotBeWithdrawn() {
        try (var s = new P3SamplesTest.Sample()) {
            s.charge("p1", 10000);
            s.check(10000, 0, 0, 10000, 0, 0, 0, 10000);
            s.batch("b1", 9680, "p1", 10000, 320, null, 0);
            s.check(0, 9680, 0, 0, 9680, 0, 0, 9680);
            s.bank("b1", 9680);
            s.check(0, 0, 9680, 0, 9680, 0, 0, 9680);
            s.app.bank(("H,BANK_STATEMENT,SIM-CAD-001,2026-10-21,2026-10-21,2,CAD\r\n"
                            + "D,bt_duplicate,CREDIT,9680,CAD,BOOKED,po_sample_b1,2026-10-21,2026-10-21,duplicate\r\n"
                            + "T,1,9680,0,9680,19360\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            s.entry("bank_txn:bt_duplicate", "UNAPPLIED_CASH_RECEIVED", "1103:D:9680", "2105:C:9680");
            s.check(0, 0, 19360, 0, 9680, 0, 0, 19360, 9680);
            assertThat(freeCash(s)).isEqualTo(9680);
            rejected(s, 9681, "f-excess", "MERCHANT_BALANCE");
            s.check(0, 0, 19360, 0, 9680, 0, 0, 19360, 9680);
            reserve(s, 9680, "f-max");
            s.check(0, 0, 19360, 0, 0, 0, 9680, 19360, 9680);
            assertThat(freeCash(s)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_1_4e_sampleGBothSerialResults(boolean payoutFirst) {
        try (var s = new P3SamplesTest.Sample()) {
            s.charge("g", 1061);
            s.check(1061, 0, 0, 1061, 0, 0, 0, 1061);
            s.batch("g", 1000, "g", 1061, 61, null, 0);
            s.check(0, 1000, 0, 0, 1000, 0, 0, 1000);
            s.bank("g", 1000);
            s.check(0, 0, 1000, 0, 1000, 0, 0, 1000);
            if (payoutFirst) {
                reserve(s, 1000, "g");
                s.check(0, 0, 1000, 0, 0, 0, 1000, 1000);
                s.accept("g", "gr", 800, "AWAITING_FUNDS");
                s.check(0, 0, 1000, 0, -800, 800, 1000, 1000);
            } else {
                s.accept("g", "gr", 800);
                s.check(0, 0, 1000, 0, 200, 800, 0, 1000);
                rejected(s, 1000, "g", "MERCHANT_BALANCE");
                s.check(0, 0, 1000, 0, 200, 800, 0, 1000);
            }
        }
    }

    private static void throughEight(P3SamplesTest.Sample s) {
        s.charge("p1", 10000);
        s.check(10000, 0, 0, 10000, 0, 0, 0, 10000);
        s.batch("b1", 9680, "p1", 10000, 320, null, 0);
        s.check(0, 9680, 0, 0, 9680, 0, 0, 9680);
        rejected(s, 9680, "a-early", "FREE_CASH");
        s.check(0, 9680, 0, 0, 9680, 0, 0, 9680);
        s.bank("b1", 9680);
        s.check(0, 0, 9680, 0, 9680, 0, 0, 9680);
        s.charge("p2", 5000);
        s.check(5000, 0, 9680, 5000, 9680, 0, 0, 14680);
        String r = s.accept("p1", "r1", 2000);
        s.check(5000, 0, 9680, 5000, 7680, 2000, 0, 14680);
        s.execute(r, "r1", 2000, "succeeded");
        s.check(3000, 0, 9680, 5000, 7680, 0, 0, 12680);
        s.batch("b2", 2825, "p2", 5000, 175, "r1", 2000);
        s.check(0, 2825, 9680, 0, 12505, 0, 0, 12505);
        s.bank("b2", 2825);
        s.check(0, 0, 12505, 0, 12505, 0, 0, 12505);
    }

    private static String reserve(P3SamplesTest.Sample s, long amount, String ref) {
        var response =
                s.app.api("/api/payouts", ref, "{\"merchantPayoutRef\":\"" + ref + "\",\"amount\":" + amount + "}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        String id =
                new ObjectMapper().readTree(response.body()).path("payoutId").asString();
        s.entry("payout:" + id + ":reserved", "PAYOUT_RESERVED", "2102:D:" + amount, "2104:C:" + amount);
        return id;
    }

    private static void rejected(P3SamplesTest.Sample s, long amount, String ref, String reason) {
        var before = DatabaseSnapshot.rows(s.app.jdbc);
        var response =
                s.app.api("/api/payouts", ref, "{\"merchantPayoutRef\":\"" + ref + "\",\"amount\":" + amount + "}");
        assertThat(response.status()).as(response.body()).isEqualTo(409);
        assertThat(response.body()).contains(reason);
        assertThat(DatabaseSnapshot.rows(s.app.jdbc)).isEqualTo(before);
    }

    private static long freeCash(P3SamplesTest.Sample s) {
        var b = s.app.balances();
        return Math.subtractExact(
                Math.subtractExact(Math.subtractExact(b.get(1103), b.get(2104)), b.get(2105)),
                Math.max(0, Math.negateExact(b.get(1101))));
    }

    private static void status(P3SamplesTest.Sample s, String id, String expected) {
        assertThat(s.app.jdbc
                        .sql("SELECT status FROM core.payout WHERE id=?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo(expected);
    }

    private static void receipt(P3SamplesTest.Sample s, String id, String operation, String status) {
        s.app.payouts.replies.add(new PayoutReplay.Reply(operation, id, status));
        s.app.payoutOutbound();
        assertThat(s.app.payouts.replies).isEmpty();
    }
}
