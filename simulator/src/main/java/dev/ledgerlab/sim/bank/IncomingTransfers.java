package dev.ledgerlab.sim.bank;

/** The only processor-to-bank notification; no processor objects or queries cross this boundary. */
public interface IncomingTransfers {
    void receive(String payoutRef, long amount);
}
