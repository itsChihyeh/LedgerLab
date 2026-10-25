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
    long failed;
    long executed;
    long successes;
    long batchedRefunds;

    void observe(int index, String observation) {
        Refund refund = refunds.get(index);
        if (!List.of("READY", "SUBMITTED", "UNKNOWN").contains(refund.state)) return;
        if (observation.equals("TIMEOUT")) {
            refund.state = "UNKNOWN";
        } else if (observation.equals("PENDING")) {
            refund.state = "SUBMITTED";
        } else if (observation.equals("SUCCEEDED")) {
            refund.state = "SUCCEEDED";
            executed += refund.amount;
            successes++;
        } else if (observation.equals("FAILED")) {
            refund.state = "FAILED";
            reserved -= refund.amount;
            unallocated += refund.amount;
            failed++;
            allocate();
        } else throw new IllegalArgumentException("Unknown observation");
    }

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

    boolean batchRefund(int index) {
        Refund refund = refunds.get(index);
        if (refund.batched
                || !List.of("READY", "SUBMITTED", "UNKNOWN", "SUCCEEDED").contains(refund.state)) return false;
        observe(index, "SUCCEEDED");
        refund.batched = true;
        batchedRefunds += refund.amount;
        settle(19900);
        return true;
    }

    private void allocate() {
        for (Refund refund : refunds) {
            if (!refund.state.equals("AWAITING_FUNDS")) continue;
            if (refund.amount > unallocated) return;
            unallocated -= refund.amount;
            refund.state = "READY";
            refund.commandCreated = true;
        }
    }

    static final class Refund {
        final long amount;
        String state = "AWAITING_FUNDS";
        boolean commandCreated;
        boolean batched;

        Refund(long amount) {
            this.amount = amount;
        }
    }
}
