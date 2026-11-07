package dev.ledgerlab.sim.sandbox;

import dev.ledgerlab.sim.bank.IncomingTransfers;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("sandbox")
public final class SandboxBankSync {
    private final SandboxStripe stripe;
    private final IncomingTransfers bank;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final JsonMapper json = new JsonMapper();

    public SandboxBankSync(
            SandboxStripe stripe,
            IncomingTransfers bank,
            JdbcClient jdbc,
            Clock clock,
            PlatformTransactionManager manager) {
        this.stripe = stripe;
        this.bank = bank;
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public Result sync() {
        var payouts = new ArrayList<JsonNode>();
        var cursors = new HashSet<String>();
        int pages = 0;
        String cursor = "";
        while (true) {
            var reply = stripe.get("/v1/payouts?status=paid&created%5Bgte%5D="
                    + stripe.windowStart().getEpochSecond() + "&limit=100"
                    + (cursor.isEmpty() ? "" : "&starting_after=" + SandboxStripe.encode(cursor)));
            JsonNode body = reply.body();
            if (!reply.error().isEmpty()) return failed(pages, payouts.size(), reply.error());
            if (!body.path("data").isArray() || !body.path("has_more").isBoolean())
                return failed(pages, payouts.size(), "INVALID_PAGE");
            pages = Math.incrementExact(pages);
            body.path("data").forEach(payouts::add);
            if (!body.path("has_more").asBoolean()) break;
            if (body.path("data").isEmpty()) return failed(pages, payouts.size(), "INVALID_CURSOR");
            cursor = SandboxStripe.objectId(
                    body.path("data").get(body.path("data").size() - 1).path("id"), "po_");
            if (cursor.isEmpty() || !cursors.add(cursor)) return failed(pages, payouts.size(), "INVALID_CURSOR");
        }
        int completedPages = pages;
        try {
            return Objects.requireNonNull(transaction.execute(tx -> receive(payouts, completedPages)));
        } catch (org.springframework.dao.DataAccessException
                | org.springframework.web.server.ResponseStatusException
                | ArithmeticException e) {
            return failed(pages, payouts.size(), "BANK_RECEIVE_FAILED");
        }
    }

    private Result receive(List<JsonNode> payouts, int pages) {
        // Shared by all sandbox processes; the bank also retains its own independent idempotency.
        jdbc.sql("select pg_advisory_xact_lock(8303001)").query().singleRow();
        var known = new HashSet<>(jdbc.sql("""
                select distinct payout from sim_sandbox.bank_sync,
                lateral jsonb_array_elements_text(received_payouts) as payout
                """).query(String.class).list());
        var received = new ArrayList<String>();
        var skipped = new ArrayList<Map<String, String>>();
        for (JsonNode payout : payouts) {
            String id = SandboxStripe.objectId(payout.path("id"), "po_");
            String reason = rejection(payout, id);
            if (reason.isEmpty() && known.contains(id)) reason = "ALREADY_RECEIVED";
            if (!reason.isEmpty()) skipped.add(Map.of("payout", id, "reason", reason));
            else {
                bank.receive(id, payout.path("amount").asLong());
                known.add(id);
                received.add(id);
            }
        }
        var result = new Result(payouts.size(), received.size(), skipped, pages, 200, "");
        append(result, received);
        return result;
    }

    private String rejection(JsonNode p, String id) {
        if (id.isEmpty()) return "INVALID_ID";
        if (!p.path("livemode").isBoolean() || p.path("livemode").asBoolean()) return "LIVEMODE";
        if (!p.path("created").isIntegralNumber()
                || !p.path("created").canConvertToLong()
                || p.path("created").asLong() < stripe.windowStart().getEpochSecond()) return "OUTSIDE_WINDOW";
        if (!p.path("status").asText().equals("paid")) return "NOT_PAID";
        if (!p.path("automatic").isBoolean() || !p.path("automatic").asBoolean()) return "MANUAL";
        if (!p.path("method").asText().equals("standard")) return "INSTANT";
        if (!p.path("currency").asText().equals("cad")) return "CURRENCY";
        if (!p.path("amount").isIntegralNumber()
                || !p.path("amount").canConvertToLong()
                || p.path("amount").asLong() <= 0
                || p.path("amount").asLong() >= 9007199254740992L) return "AMOUNT";
        return "";
    }

    private Result failed(int pages, int seen, String error) {
        var result = new Result(seen, 0, List.of(), pages, 502, error);
        append(result, List.of());
        return result;
    }

    private void append(Result result, List<String> received) {
        jdbc.sql("""
                insert into sim_sandbox.bank_sync
                  (run_id,pages,seen,received,http_status,received_payouts,skipped,error_code,recorded_at)
                values (?,?,?,?,?,?::jsonb,?::jsonb,?,?)
                """)
                .param(stripe.runId())
                .param(result.pages())
                .param(result.seen())
                .param(result.received())
                .param(result.httpStatus())
                .param(json.writeValueAsString(received))
                .param(json.writeValueAsString(result.skipped()))
                .param(result.errorCode())
                .param(Timestamp.from(clock.instant()))
                .update();
    }

    public record Result(
            int seen, int received, List<Map<String, String>> skipped, int pages, int httpStatus, String errorCode) {}
}
