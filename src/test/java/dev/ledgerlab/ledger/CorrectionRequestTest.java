package dev.ledgerlab.ledger;

import static dev.ledgerlab.testsupport.LedgerFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorrectionRequestTest {
    private static final String EVIDENCE = "[{\"type\":\"inbox_event\",\"id\":\"evt_test\"}]";

    private static CorrectionRequest request(
            String note,
            String evidence,
            String params,
            String operator,
            List<String> reversed,
            List<String> applied,
            List<PostingLine> lines) {
        return new CorrectionRequest(
                CorrectionType.VOID_CAPTURE,
                CorrectionReason.OTHER,
                note,
                evidence,
                params,
                SubjectType.PAYMENT,
                "pay_test",
                operator,
                reversed,
                applied,
                lines,
                T0);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "\t\n",
                "[]",
                "{}",
                "null",
                "[{}]",
                "[1]",
                "[{\"type\":\"unsupported\",\"id\":\"x\"}]",
                "[{\"type\":\"issue\",\"id\":\" \"}]"
            })
    void ac_6_7_evidenceRequiresNonemptyTypedReferences(String evidence) {
        assertThatThrownBy(
                        () -> request("reason", evidence, "{}", "operator", List.of("original"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_6_7_reasonAndOperatorAreRequiredAndNoteLengthCountsCharacters() {
        for (String text : List.of("", " ", "\t\n")) {
            assertThatThrownBy(() -> request(text, EVIDENCE, "{}", "operator", List.of("a"), List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> request("reason", EVIDENCE, "{}", text, List.of("a"), List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () -> request("x".repeat(2001), EVIDENCE, "{}", "operator", List.of("a"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(request("x".repeat(2000), EVIDENCE, "{}", "operator", List.of("a"), List.of(), List.of())
                        .reasonNote())
                .hasSize(2000);
    }

    @Test
    void ac_6_8_targetsCannotRepeatWithinOrAcrossRolesAndMustGenerateEntry() {
        assertThatThrownBy(() -> request("reason", EVIDENCE, "{}", "operator", List.of("a", "a"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("reason", EVIDENCE, "{}", "operator", List.of("a"), List.of("a"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("reason", EVIDENCE, "{}", "operator", List.of(), List.of("a"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("reason", EVIDENCE, "{}", "operator", List.of(" "), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_6_8_replacementMustBalanceWithExactArithmetic() {
        assertThatThrownBy(() -> request(
                        "reason",
                        EVIDENCE,
                        "{}",
                        "operator",
                        List.of(),
                        List.of(),
                        List.of(PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 50))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(
                        "reason",
                        EVIDENCE,
                        "{}",
                        "operator",
                        List.of(),
                        List.of(),
                        List.of(
                                PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 50),
                                PostingLine.credit(Account.MERCHANT_PENDING, 51))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(
                        "reason",
                        EVIDENCE,
                        "{}",
                        "operator",
                        List.of(),
                        List.of(),
                        List.of(
                                PostingLine.debit(Account.PROCESSOR_RECEIVABLE, Long.MAX_VALUE),
                                PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 1),
                                PostingLine.credit(Account.MERCHANT_PENDING, 50))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void ac_6_8_requestCopiesCallerLists() {
        var ids = new ArrayList<>(List.of("a"));
        var lines = new ArrayList<>(JdbcCorrectionTest.capture(100));
        var req = request("reason", EVIDENCE, "{}", "operator", ids, List.of(), lines);
        ids.clear();
        lines.clear();
        assertThat(req.reversedEntryIds()).containsExactly("a");
        assertThat(req.correctionLines()).hasSize(2);
    }
}
