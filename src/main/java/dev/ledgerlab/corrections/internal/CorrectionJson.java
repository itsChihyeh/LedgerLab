package dev.ledgerlab.corrections.internal;

import dev.ledgerlab.corrections.CorrectionCommand;
import dev.ledgerlab.corrections.CorrectionException;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class CorrectionJson {
    private static final Set<String> FIELDS = Set.of(
            "type",
            "originalEntryId",
            "newAmount",
            "batchId",
            "bankTxnIds",
            "bankTxnId",
            "reasonCode",
            "reasonNote",
            "evidence",
            "requestedBy");

    private CorrectionJson() {}

    static CorrectionCommand parse(ObjectMapper mapper, @Nullable String raw) {
        if (raw == null) throw invalid();
        try (var parser = mapper.createParser(raw)) {
            // Reject duplicate fields at every depth and all floating-point tokens before tree conversion.
            var objects = new ArrayDeque<Set<String>>();
            while (parser.nextToken() != null) {
                JsonToken token = parser.currentToken();
                if (token == JsonToken.START_OBJECT) objects.push(new HashSet<>());
                else if (token == JsonToken.END_OBJECT) objects.pop();
                else if (token == JsonToken.PROPERTY_NAME && !objects.peek().add(parser.currentName())) throw invalid();
                else if (token == JsonToken.VALUE_NUMBER_FLOAT) throw invalid();
            }
            JsonNode root;
            try (var single = mapper.createParser(raw)) {
                root = mapper.readTree(single);
                if (single.nextToken() != null) throw invalid();
            }
            if (root == null
                    || !root.isObject()
                    || root.properties().stream().anyMatch(e -> !FIELDS.contains(e.getKey()))) throw invalid();
            var refs = root.path("evidence");
            if (!refs.isArray()) throw invalid();
            var evidence = new ArrayList<CorrectionCommand.Evidence>();
            for (var ref : refs) {
                if (!ref.isObject() || ref.size() != 2) throw invalid();
                evidence.add(new CorrectionCommand.Evidence(text(ref, "type"), text(ref, "id")));
            }
            Long amount = null;
            if (root.hasNonNull("newAmount")) {
                var value = root.path("newAmount");
                if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
                amount = value.longValue();
            }
            List<String> banks = null;
            if (root.hasNonNull("bankTxnIds")) {
                if (!root.path("bankTxnIds").isArray()) throw invalid();
                banks = new ArrayList<>();
                for (var id : root.path("bankTxnIds")) {
                    if (!id.isString() || !CorrectionCommand.text(id.asString())) throw invalid();
                    banks.add(id.asString());
                }
            }
            var type = CorrectionType.valueOf(text(root, "type"));
            if (type == CorrectionType.RESTORE_FAILED_PAYOUT) {
                if (root.has("bankTxnIds")) throw invalid();
                banks = List.of(text(root, "bankTxnId"));
            } else if (root.has("bankTxnId")) throw invalid();
            return new CorrectionCommand(
                    type,
                    optional(root, "originalEntryId"),
                    amount,
                    optional(root, "batchId"),
                    banks,
                    CorrectionReason.valueOf(text(root, "reasonCode")),
                    text(root, "reasonNote"),
                    evidence,
                    text(root, "requestedBy"));
        } catch (JacksonException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static @Nullable String optional(JsonNode root, String field) {
        return root.hasNonNull(field) ? text(root, field) : null;
    }

    private static String text(JsonNode root, String field) {
        var node = root.path(field);
        if (!node.isString() || !CorrectionCommand.text(node.asString())) throw invalid();
        return node.asString();
    }

    private static CorrectionException invalid() {
        return new CorrectionException(400, "INVALID_REQUEST");
    }
}
