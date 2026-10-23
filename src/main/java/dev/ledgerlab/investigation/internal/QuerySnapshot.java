package dev.ledgerlab.investigation.internal;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One connection and one MVCC snapshot for every query response. */
@Component
final class QuerySnapshot {
    final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    QuerySnapshot(@Qualifier("investigationDataSource") DataSource dataSource, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = clock;
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    <T> T read(Function<PaymentQueries.AsOf, T> query) {
        return Objects.requireNonNull(transaction.execute(tx -> {
            long seq = jdbc.sql("SELECT coalesce(max(seq), 0) FROM ledger.v_journal_entry")
                    .query(Long.class)
                    .single();
            return query.apply(new PaymentQueries.AsOf(seq, clock.instant()));
        }));
    }
}
