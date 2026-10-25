package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.ingestion.StripeReader;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
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
    private static final String VERSION = StripeHttpTransport.VERSION;
    private final StripeHttpTransport transport;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate outsideTransaction;
    private final StripeFetchArchive archive;

    RestStripeReader(
            RestClient client,
            StripeProperties credentials,
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            Clock clock) {
        transport = new StripeHttpTransport(client, credentials, clock);
        archive = new StripeFetchArchive(jdbc, manager, clock);
        this.mapper = mapper;
        this.clock = clock;
        outsideTransaction = new TransactionTemplate(manager);
        outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
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
            String hash = StripeFetchArchive.sha256(body);
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
            long id = archive.archive(purpose.name(), eventId, request, body, hash, failure == null, fetchedAt);
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
        if (!transport.hasKey()) {
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
                var response = transport.send("GET", path, query, Map.of(), null);
                if (response.failure() != null) return response.failure();
                bodies.add(response.body());
                int status = Objects.requireNonNull(response.status());
                attempt.put("status", status);
                if (status < 200 || status >= 300) {
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
}
