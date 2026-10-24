package dev.ledgerlab.refunds.internal;

import java.util.ArrayList;
import java.util.List;

/** Independent JDK-only oracle. Allocates a pool of settled funds to queued obligations. */
final class RefundReference {
    static final long CAPTURED = 10000;
    final List<Refund> refunds = new ArrayList<>();
    long reserved;
    long settled;
    long unallocated;
    long cancelled;

    boolean accept(long amount) {
        if (amount > CAPTURED - reserved) return false;
        reserved += amount;
        refunds.add(new Refund(amount));
        allocate();
        return true;
    }

    boolean cancel(int index) {
        Refund refund = refunds.get(index);
        if (!refund.state.equals("AWAITING_FUNDS")) return false;
        refund.state = "CANCELLED";
        reserved -= refund.amount;
        cancelled++;
        allocate();
        return true;
    }

    void settle(long net) {
        settled += net;
        unallocated += net;
        allocate();
    }

    private void allocate() {
        for (Refund refund : refunds) {
            if (!refund.state.equals("AWAITING_FUNDS")) continue;
            if (refund.amount > unallocated) return;
            unallocated -= refund.amount;
            refund.state = "READY";
        }
    }

    static final class Refund {
        final long amount;
        String state = "AWAITING_FUNDS";

        Refund(long amount) {
            this.amount = amount;
        }
    }
}
