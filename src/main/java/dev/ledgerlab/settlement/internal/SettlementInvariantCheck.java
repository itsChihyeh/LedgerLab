package dev.ledgerlab.settlement.internal;

import dev.ledgerlab.shared.InvariantCheck;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** INV-9, the settlement part: the 1102 balance equals the net of the {@code CONFIRMED} batches. */
@Component
public final class SettlementInvariantCheck implements InvariantCheck {

    @Override
    public List<String> ids() {
        return List.of("INV-9");
    }

    @Override
    public Map<String, List<String>> check(JdbcClient snapshot) {
        List<String> found =
                snapshot
                        .sql("""
                        SELECT a.balance, coalesce((SELECT sum(b.net) FROM core.v_settlement_batch b
                                                     WHERE b.status = 'CONFIRMED'), 0) AS expected
                        FROM ledger.v_account a WHERE a.code = 1102
                        """)
                        .query((rs, row) -> new long[] {rs.getLong("balance"), rs.getLong("expected")})
                        .list()
                        .stream()
                        .filter(pair -> pair[0] != pair[1])
                        .map(pair -> "1102 balance %d, confirmed batches net %d".formatted(pair[0], pair[1]))
                        .toList();
        return Map.of("INV-9", found);
    }
}
