package dev.ledgerlab.ledger;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * A restricted correction. The business caller validates evidence existence and type-specific eligibility.
 * JSON evidence is a nonempty array of {type, id}; params preserves the business caller's audit data.
 * An empty correctionLines list means no CORRECTION entry. Reversal lines are never supplied by callers.
 */
public record CorrectionRequest(
        CorrectionType type,
        CorrectionReason reasonCode,
        String reasonNote,
        String evidenceRefs,
        String params,
        SubjectType subjectType,
        String subjectId,
        String requestedBy,
        List<String> reversedEntryIds,
        List<String> appliedEntryIds,
        List<PostingLine> correctionLines,
        Instant occurredAt) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> EVIDENCE_TYPES =
            Set.of("issue", "statement_line", "bank_txn", "inbox_event", "processor_fetch");

    public CorrectionRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(reasonNote, "reasonNote");
        if (reasonNote.codePointCount(0, reasonNote.length()) > 2000) {
            throw new IllegalArgumentException("reasonNote exceeds 2000 characters");
        }
        requireText(subjectId, "subjectId");
        requireText(requestedBy, "requestedBy");
        requireText(evidenceRefs, "evidenceRefs");
        requireText(params, "params");
        var evidence = JSON.readTree(evidenceRefs);
        if (!evidence.isArray() || evidence.isEmpty()) {
            throw new IllegalArgumentException("evidenceRefs must be a nonempty array");
        }
        for (var ref : evidence) {
            if (!ref.isObject()
                    || !ref.path("type").isString()
                    || !EVIDENCE_TYPES.contains(ref.path("type").asString())
                    || !ref.path("id").isString()
                    || ref.path("id").asString().isBlank()) {
                throw new IllegalArgumentException("evidence requires a supported type and nonblank id");
            }
        }
        JSON.readTree(params);
        reversedEntryIds = List.copyOf(reversedEntryIds);
        appliedEntryIds = List.copyOf(appliedEntryIds);
        correctionLines = List.copyOf(correctionLines);
        Set<String> targets = new HashSet<>();
        for (var group : List.of(reversedEntryIds, appliedEntryIds)) {
            for (String id : group) {
                requireText(id, "target entry id");
                if (!targets.add(id)) {
                    throw new IllegalArgumentException("duplicate target entry: " + id);
                }
            }
        }
        if (reversedEntryIds.isEmpty() && correctionLines.isEmpty()) {
            throw new IllegalArgumentException("a correction must generate at least one entry");
        }
        if (!correctionLines.isEmpty()) {
            // Reuse the business-entry balance validation without exposing a correction bypass in EntryRequest.
            new EntryRequest(
                    EntryType.PAYMENT_CAPTURED, "validation", occurredAt, subjectType, subjectId, correctionLines);
        }
    }

    private static void requireText(String text, String name) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(name + " is blank");
    }
}
