package dev.ledgerlab.corrections;

public interface Corrections {
    CorrectionResult submit(CorrectionCommand command);
}
