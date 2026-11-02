package dev.ledgerlab.model;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Every application-owned base table, including audit/ingestion rows; excludes sequences and views. */
final class DatabaseSnapshot {
    private DatabaseSnapshot() {}

    static List<String> rows(JdbcClient jdbc) {
        return jdbc
                .sql(
                        "SELECT schemaname || '.' || tablename FROM pg_tables WHERE schemaname IN ('core','ledger','io','recon') ORDER BY schemaname,tablename")
                .query(String.class)
                .list()
                .stream()
                .map(table -> table + ":"
                        + jdbc.sql(
                                        "SELECT coalesce(jsonb_agg(r ORDER BY r::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) r FROM "
                                                + table + " t) s")
                                .query(String.class)
                                .single())
                .toList();
    }
}
