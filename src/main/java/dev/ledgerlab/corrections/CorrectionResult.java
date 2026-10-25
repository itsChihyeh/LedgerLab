package dev.ledgerlab.corrections;

import java.util.List;
import java.util.Map;

/** Immutable audit data plus identifiers of business objects affected by this correction. */
public record CorrectionResult(
        Map<String, Object> correction,
        List<Map<String, Object>> targets,
        List<Map<String, Object>> entries,
        Affected affected) {
    public record Affected(List<String> payments, List<String> batches, List<String> bankLines, List<String> issues) {}
}
