package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.ingestion.StripeReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class RestStripeReader implements StripeReader {
    private static final String VERSION = "2026-09-30.endive";
    private final RestClient client;
    private final StripeProperties credentials;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate outsideTransaction;
    private final TransactionTemplate archiveTransaction;

    RestStripeReader(
            RestClient client,
            StripeProperties credentials,
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            Clock clock) {
        this.client = client;
        this.credentials = credentials;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        outsideTransaction = new TransactionTemplate(manager);
        outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        archiveTransaction = new TransactionTemplate(manager);
        archiveTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public FetchResult<Payout> retrievePayout(String payoutId, FetchPurpose purpose, @Nullable String eventId) {
        return fetch(
                "/v1/payouts/" + payoutId,
                Map.of(),
                false,
                purpose,
                eventId,
                payoutId.matches("po_[A-Za-z0-9_]+") ? null : "INVALID_PAYOUT_ID",
                StripeReadParser::payout);
    }

    @Override
    public FetchResult<BalanceTransaction> listBalanceTransactionsForPayout(
            String payoutId, FetchPurpose purpose, @Nullable String eventId) {
        return fetch(
                "/v1/balance_transactions",
                Map.of("payout", payoutId),
                true,
                purpose,
                eventId,
                payoutId.matches("po_[A-Za-z0-9_]+") ? null : "INVALID_PAYOUT_ID",
                StripeReadParser::balanceTransaction);
    }

    @Override
    public FetchResult<Payout> listPayoutsCreated(
            Instant from, Instant to, FetchPurpose purpose, @Nullable String eventId) {
        String invalid =
                !from.isBefore(to) || from.getNano() != 0 || to.getNano() != 0 ? "INVALID_CREATED_INTERVAL" : null;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("created[gte]", Long.toString(from.getEpochSecond()));
        query.put("created[lt]", Long.toString(to.getEpochSecond()));
        return fetch("/v1/payouts", query, true, purpose, eventId, invalid, StripeReadParser::payout);
    }

    private <T> FetchResult<T> fetch(
            String path,
            Map<String, String> query,
            boolean paginated,
            FetchPurpose purpose,
            @Nullable String eventId,
            @Nullable String inputFailure,
            Function<JsonNode, T> parse) {
        return Objects.requireNonNull(outsideTransaction.execute(ignored -> {
            List<String> bodies = new ArrayList<>();
            List<Map<String, Object>> attempts = new ArrayList<>();
            List<T> items = new ArrayList<>();
            String failure =
                    inputFailure == null ? read(path, query, paginated, parse, bodies, attempts, items) : inputFailure;
            Instant fetchedAt = clock.instant();
            String body = paginated ? mapper.writeValueAsString(bodies) : bodies.isEmpty() ? "" : bodies.getFirst();
            String hash = sha256(body);
            String request = mapper.writeValueAsString(Map.of(
                    "method",
                    "GET",
                    "path",
                    path,
                    "query",
                    query,
                    "purpose",
                    purpose.name(),
                    "stripe_version",
                    VERSION,
                    "attempts",
                    attempts,
                    "outcome",
                    failure == null ? "COMPLETE" : failure));
            long id = archive(purpose, eventId, request, body, hash, failure == null, fetchedAt);
            return new FetchResult<>(id, hash, fetchedAt, items, failure);
        }));
    }

    private <T> @Nullable String read(
            String path,
            Map<String, String> originalQuery,
            boolean paginated,
            Function<JsonNode, T> parse,
            List<String> bodies,
            List<Map<String, Object>> attempts,
            List<T> items) {
        String key = credentials.apiKey();
        if (key == null || key.isEmpty()) {
            return "ledgerlab.stripe.api-key is required for Stripe reads";
        }
        Map<String, String> query = new LinkedHashMap<>(originalQuery);
        if (paginated) {
            query.put("limit", "100");
        }
        var cursors = new HashSet<String>();
        try {
            while (true) {
                Map<String, Object> attempt = new LinkedHashMap<>();
                attempt.put("query", Map.copyOf(query));
                attempts.add(attempt);
                Response response = Objects.requireNonNull(client.get()
                        .uri(builder -> {
                            builder.path(path);
                            query.forEach(builder::queryParam);
                            return builder.build();
                        })
                        .header("Authorization", "Bearer " + key)
                        .header("Stripe-Version", VERSION)
                        .exchange((request, reply) -> new Response(
                                reply.getStatusCode().value(),
                                new String(reply.getBody().readAllBytes(), StandardCharsets.UTF_8))));
                bodies.add(response.body());
                attempt.put("status", response.status());
                if (response.status() < 200 || response.status() >= 300) {
                    return "HTTP_STATUS_" + response.status();
                }
                JsonNode root = mapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(response.body());
                if (!paginated) {
                    items.add(parse.apply(root));
                    return null;
                }
                JsonNode data = root.path("data");
                if (!data.isArray()) {
                    return "INVALID_RESPONSE";
                }
                boolean more = StripeReadParser.bool(root, "has_more");
                for (JsonNode item : data) {
                    items.add(parse.apply(item));
                }
                if (!more) {
                    return null;
                }
                if (data.isEmpty()) {
                    return "INVALID_PAGINATION";
                }
                String cursor = StripeReadParser.text(data.path(data.size() - 1), "id");
                if (!cursors.add(cursor)) {
                    return "INVALID_PAGINATION";
                }
                query.put("starting_after", cursor);
            }
        } catch (RestClientException e) {
            // Never propagate HTTP diagnostics: an upstream can echo Authorization in its body.
            return "TRANSPORT_FAILURE";
        } catch (RuntimeException e) {
            return "INVALID_RESPONSE";
        }
    }

    private long archive(
            FetchPurpose purpose,
            @Nullable String eventId,
            String request,
            String body,
            String hash,
            boolean complete,
            Instant fetchedAt) {
        try {
            return Objects.requireNonNull(archiveTransaction.execute(ignored -> jdbc.sql("""
                    INSERT INTO io.processor_fetch
                        (event_id, purpose, request, response_body, response_sha256, complete, fetched_at, recorded_at)
                    VALUES (:event, :purpose, CAST(:request AS jsonb), :body, :hash, :complete, :fetched, :recorded)
                    RETURNING id
                    """)
                    .param("event", eventId)
                    .param("purpose", purpose.name())
                    .param("request", request)
                    .param("body", body)
                    .param("hash", hash)
                    .param("complete", complete)
                    .param("fetched", fetchedAt.atOffset(ZoneOffset.UTC))
                    .param("recorded", clock.instant().atOffset(ZoneOffset.UTC))
                    .query(Long.class)
                    .single()));
        } catch (DataAccessException e) {
            throw new IllegalStateException("Stripe fetch archive failed");
        }
    }

    private static String sha256(String body) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Response(int status, String body) {}
}
