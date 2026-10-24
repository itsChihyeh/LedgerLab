package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.payments.PaymentRefunds;
import dev.ledgerlab.refunds.RefundException;
import dev.ledgerlab.refunds.Refunds;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
final class RefundApi {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final PaymentRefunds payments;
    private final Refunds refunds;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final RefundApiProperties properties;
    private final Runnable beforeCommit;

    @Autowired
    RefundApi(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentRefunds payments,
            Refunds refunds,
            Clock clock,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            RefundApiProperties properties) {
        this(jdbc, ledger, payments, refunds, clock, mapper, manager, properties, () -> {});
    }

    /** Package-local fault seam; no production flag or HTTP-accessible fault injection. */
    RefundApi(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentRefunds payments,
            Refunds refunds,
            Clock clock,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            RefundApiProperties properties,
            Runnable beforeCommit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.payments = payments;
        this.refunds = refunds;
        this.clock = clock;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.properties = properties;
        this.beforeCommit = beforeCommit;
    }

    record Response(int status, String body) {}

    Response accept(String key, JsonNode body) {
        String fingerprint = RefundJson.fingerprint(mapper, "/api/refunds", body);
        return execute(key, fingerprint, () -> {
            String payment = payments.findIdByProcessorRef(
                            body.path("payment_ref").asString())
                    .orElseThrow(() -> new RefundException("PAYMENT_NOT_FOUND"));
            String ref = body.path("merchant_refund_ref").asString();
            long amount = body.path("amount").asLong();
            var existing = jdbc.sql("""
                    SELECT amount, initial_result::text FROM core.refund
                    WHERE payment_id = :payment AND merchant_refund_ref = :ref
                    """)
                    .param("payment", payment)
                    .param("ref", ref)
                    .query((rs, row) -> {
                        if (rs.getLong(1) != amount) throw new RefundException("BUSINESS_REF_CONFLICT");
                        return initial(rs.getString(2));
                    })
                    .optional();
            if (existing.isPresent()) return existing.get();
            var result = refunds.accept(payment, ref, amount, fingerprint);
            return initial(jdbc.sql("SELECT initial_result::text FROM core.refund WHERE id = :id")
                    .param("id", result.refundId())
                    .query(String.class)
                    .single());
        });
    }

    Response cancel(String key, String id, JsonNode body) {
        return execute(key, RefundJson.fingerprint(mapper, "/api/refunds/" + id + "/cancel", body), () -> {
            var result = refunds.cancel(id, body.path("reason").asString());
            return response(
                    200,
                    mapper.valueToTree(Map.of(
                            "refund_id", result.refundId(), "status", result.status(), "amount", result.amount())));
        });
    }

    private Response execute(String key, String fingerprint, Supplier<Response> action) {
        try {
            return Objects.requireNonNull(transaction.execute(tx -> {
                jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                        .param("timeout", properties.getIdempotencyLockTimeout().toMillis() + "ms")
                        .query(String.class)
                        .single();
                ledger.lockFunds();
                var now = clock.instant().atOffset(ZoneOffset.UTC);
                int inserted = jdbc.sql("""
                        INSERT INTO io.idempotency_record(client_id, idem_key, request_fingerprint, state,
                            locked_until, created_at, expires_at, recorded_at)
                        VALUES (:client, :key, :fingerprint, 'IN_PROGRESS', :locked, :now, :expires, :now)
                        ON CONFLICT DO NOTHING
                        """)
                        .param("client", properties.getClientId())
                        .param("key", key)
                        .param("fingerprint", fingerprint)
                        .param("now", now)
                        .param("locked", now.plus(properties.getIdempotencyLockTimeout()))
                        .param("expires", now.plus(Duration.ofHours(24)))
                        .update();
                if (inserted == 0) {
                    return jdbc.sql("""
                            SELECT request_fingerprint, state, response_status, response_body::text
                            FROM io.idempotency_record WHERE client_id = :client AND idem_key = :key
                            FOR UPDATE
                            """)
                            .param("client", properties.getClientId())
                            .param("key", key)
                            .query((rs, row) -> {
                                if (!fingerprint.equals(rs.getString(1)))
                                    throw new RefundException("IDEMPOTENCY_KEY_REUSED");
                                if (!"COMPLETED".equals(rs.getString(2)))
                                    throw new RefundException("IDEMPOTENCY_REQUEST_IN_PROGRESS");
                                return response(rs.getInt(3), mapper.readTree(rs.getString(4)));
                            })
                            .single();
                }
                Response result = action.get();
                jdbc.sql("""
                        UPDATE io.idempotency_record
                        SET state = 'COMPLETED', response_status = :status, response_body = CAST(:body AS jsonb)
                        WHERE client_id = :client AND idem_key = :key
                        """)
                        .param("status", result.status())
                        .param("body", result.body())
                        .param("client", properties.getClientId())
                        .param("key", key)
                        .update();
                beforeCommit.run();
                return result;
            }));
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && "55P03".equals(sql.getSQLState()))
                throw new RefundException("IDEMPOTENCY_REQUEST_IN_PROGRESS", e);
            throw e;
        }
    }

    private Response initial(String json) {
        JsonNode initial = mapper.readTree(json);
        return response(initial.path("status").asInt(), initial.path("body"));
    }

    private Response response(int status, JsonNode body) {
        // jsonb does not preserve whitespace/key order; serialize first responses and replays identically.
        return new Response(status, RefundJson.canonical(mapper, body));
    }
}
