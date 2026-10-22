package dev.ledgerlab.ledger;

import java.util.Map;

/** A recorded entry: its identifier, ledger sequence number and the resulting balances of the touched accounts. */
public record PostedEntry(String entryId, long seq, Map<Account, Long> balancesAfter) {

    public PostedEntry {
        balancesAfter = Map.copyOf(balancesAfter);
    }
}
