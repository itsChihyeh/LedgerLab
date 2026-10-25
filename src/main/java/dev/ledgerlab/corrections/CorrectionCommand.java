package dev.ledgerlab.corrections;

import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionType;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record CorrectionCommand(
        CorrectionType type,
        @Nullable String originalEntryId,
        @Nullable Long newAmount,
        @Nullable String batchId,
        @Nullable List<String> bankTxnIds,
        CorrectionReason reasonCode,
        String reasonNote,
        List<Evidence> evidence,
        String requestedBy) {
    public CorrectionCommand {
        if (type == null
                || reasonCode == null
                || !text(reasonNote)
                || !text(requestedBy)
                || reasonNote.codePointCount(0, reasonNote.length()) > 2000
                || evidence == null
                || evidence.isEmpty()) throw new CorrectionException(400, "INVALID_REQUEST");
        for (Evidence ref : evidence) {
            if (ref == null
                    || !text(ref.type())
                    || !text(ref.id())
                    || !List.of("issue", "statement_line", "bank_txn", "inbox_event", "processor_fetch")
                            .contains(ref.type())) throw new CorrectionException(400, "INVALID_REQUEST");
        }
        evidence = List.copyOf(evidence);
        if (bankTxnIds != null) {
            if (bankTxnIds.stream().anyMatch(id -> !text(id))) throw new CorrectionException(400, "INVALID_REQUEST");
            bankTxnIds = List.copyOf(bankTxnIds);
        }
    }

    public static boolean text(@Nullable String value) {
        return value != null && !value.isBlank() && value.indexOf('\0') < 0;
    }

    public record Evidence(String type, String id) {}
}
