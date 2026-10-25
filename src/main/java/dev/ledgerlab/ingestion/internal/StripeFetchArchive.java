package dev.ledgerlab.ingestion.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

final class StripeFetchArchive {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate archiveTransaction;

    StripeFetchArchive(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        archiveTransaction = new TransactionTemplate(manager);
        archiveTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    long archive(
            String purpose,
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
                    .param("purpose", purpose)
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

    static String sha256(String body) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
