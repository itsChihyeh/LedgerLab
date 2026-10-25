package dev.ledgerlab.corrections.internal;

import dev.ledgerlab.corrections.CorrectionException;
import dev.ledgerlab.corrections.CorrectionResult;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class CorrectionQueries {
    private final JdbcClient jdbc;
    private final CorrectionReader reader;
    private final TransactionTemplate snapshot;

    CorrectionQueries(@Qualifier("correctionsDataSource") DataSource source, ObjectMapper mapper) {
        jdbc = JdbcClient.create(source);
        reader = new CorrectionReader(jdbc, mapper, true);
        snapshot = new TransactionTemplate(new JdbcTransactionManager(source));
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    CorrectionResult detail(String id) {
        return Objects.requireNonNull(snapshot.execute(tx -> reader.detail(id)));
    }

    List<CorrectionResult> recent() {
        return Objects.requireNonNull(snapshot.execute(tx -> jdbc.sql("""
                SELECT id FROM ledger.v_correction ORDER BY recorded_at DESC, id DESC LIMIT 50
                """).query(String.class).list().stream()
                .map(id -> reader.detail(Objects.requireNonNull(id)))
                .toList()));
    }

    List<CorrectionResult> list(@Nullable String subjectType, @Nullable String subjectId) {
        if ((subjectType != null
                        && !List.of("payment", "refund", "payout", "batch", "bank_txn")
                                .contains(subjectType))
                || (subjectId != null && subjectId.isBlank())) throw new CorrectionException(400, "INVALID_REQUEST");
        return Objects.requireNonNull(snapshot.execute(tx ->
                jdbc.sql("""
                SELECT id FROM ledger.v_correction
                WHERE (:type::text IS NULL OR subject_type=:type) AND (:id::text IS NULL OR subject_id=:id)
                ORDER BY recorded_at, id
                """).param("type", subjectType).param("id", subjectId).query(String.class).list().stream()
                        .map(id -> reader.detail(Objects.requireNonNull(id)))
                        .toList()));
    }
}
