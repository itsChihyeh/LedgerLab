package dev.ledgerlab.payments.internal;

import dev.ledgerlab.shared.InvariantCheck;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** INV-9, the payment part: the 2101 balance equals the gross of the payments still in {@code CAPTURED}. */
@Component
public final class PaymentInvariantCheck implements InvariantCheck {

    @Override
    public List<String> ids() {
        return List.of("INV-9");
    }

    @Override
    public Map<String, List<String>> check(JdbcClient snapshot) {
        List<String> found =
                snapshot
                        .sql("""
                        SELECT a.balance, coalesce((SELECT sum(p.amount) FROM core.v_payment p
                                                     WHERE p.status = 'CAPTURED'), 0) AS expected
                        FROM ledger.v_account a WHERE a.code = 2101
                        """)
                        .query((rs, row) -> new long[] {rs.getLong("balance"), rs.getLong("expected")})
                        .list()
                        .stream()
                        .filter(pair -> pair[0] != pair[1])
                        .map(pair -> "2101 balance %d, captured payments total %d".formatted(pair[0], pair[1]))
                        .toList();
        return Map.of("INV-9", found);
    }
}
