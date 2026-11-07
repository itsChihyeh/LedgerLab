package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ReconMetricsCategoriesTest {
    @Test
    void ac_12_5_categoryLabelsAreExactlyTheDatabaseConstraintSet() {
        var db = TestDatabase.freshMigrated();
        try {
            String definition = JdbcClient.create(db.superuser())
                    .sql("""
                            SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
                            WHERE c.conname = 'issue_category' AND c.conrelid = 'recon.issue'::regclass
                            """)
                    .query(String.class)
                    .single();
            List<String> inDatabase = new ArrayList<>();
            Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(definition);
            while (quoted.find()) inDatabase.add(quoted.group(1));

            assertThat(inDatabase).isNotEmpty();
            assertThat(ReconMetrics.CATEGORIES).containsExactlyInAnyOrderElementsOf(inDatabase);
        } finally {
            db.drop();
        }
    }
}
