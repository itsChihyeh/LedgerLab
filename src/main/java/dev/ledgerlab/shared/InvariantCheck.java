package dev.ledgerlab.shared;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A read-only runtime check of the money invariants (money-model section 5). Each module that owns the data
 * implements the checks for its part; the investigation module runs all of them inside one
 * {@code REPEATABLE READ} read-only transaction, so a concurrent write cannot produce a false alarm.
 *
 * <p>Implementations read only through the {@code v_*} views the {@code readonly} role may see, and must use
 * the given {@link JdbcClient} for every query (it is bound to that one snapshot).
 */
public interface InvariantCheck {

    /** The invariants this check covers, for example {@code INV-7}. The runner reports each as ok or violated. */
    List<String> ids();

    /** Violations by invariant id; an id that is missing or has an empty list is ok. */
    Map<String, List<String>> check(JdbcClient snapshot);
}
