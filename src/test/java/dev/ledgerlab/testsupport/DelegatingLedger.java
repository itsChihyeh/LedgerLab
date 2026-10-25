package dev.ledgerlab.testsupport;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.CorrectionDetails;
import dev.ledgerlab.ledger.CorrectionRequest;
import dev.ledgerlab.ledger.CorrectionTarget;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostedEntry;
import dev.ledgerlab.ledger.RecordedCorrection;
import java.util.Optional;

/** Transparent adapter for tests that intercept just one ledger operation. */
public class DelegatingLedger implements Ledger {
    private final Ledger delegate;

    public DelegatingLedger(Ledger delegate) {
        this.delegate = delegate;
    }

    @Override
    public void lockFunds() {
        delegate.lockFunds();
    }

    @Override
    public void assertFundsLocked() {
        delegate.assertFundsLocked();
    }

    @Override
    public long balance(Account account) {
        return delegate.balance(account);
    }

    @Override
    public PostedEntry post(EntryRequest request) {
        return delegate.post(request);
    }

    @Override
    public RecordedCorrection recordCorrection(CorrectionRequest request) {
        return delegate.recordCorrection(request);
    }

    @Override
    public Optional<CorrectionDetails> findCorrection(String id) {
        return delegate.findCorrection(id);
    }

    @Override
    public Optional<CorrectionTarget> findCorrectionForEntry(String id) {
        return delegate.findCorrectionForEntry(id);
    }
}
