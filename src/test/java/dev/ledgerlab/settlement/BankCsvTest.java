package dev.ledgerlab.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BankCsvTest {
    private static final String HEADER = "H,BANK_STATEMENT,SIM-CAD-001,2026-11-09,2026-11-09,1,CAD\r\n";
    private static final String ROW = "D,t1,CREDIT,9680,CAD,BOOKED,po_b1,2026-11-09,2026-11-09,memo\r\n";
    private static final String TAIL = "T,1,9680,0,0,9680\r\n";

    @Test
    void ac_5_10_quotedMultilineUnicodeMemoCountsAsOneRecord() {
        var parsed = parse(HEADER + ROW.replace("memo", "\"到账, \"\"quoted\"\"\r\nnext\nline\"") + TAIL);
        assertThat(parsed.lines()).hasSize(1);
        assertThat(parsed.lines().getFirst().memo()).isEqualTo("到账, \"quoted\"\r\nnext\nline");
        assertThat(parse(HEADER + "T,0,0,0,42,42\r\n").lines()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "T,2,9680,0,0,9680\r\n",
                "T,1,9681,0,0,9681\r\n",
                "T,1,9680,1,0,9679\r\n",
                "T,1,9680,0,0\r\n",
                "T,1,9680,0,0,9680",
                "",
                "T,1,9680,0,0,9680\n"
            })
    void ac_5_10_badTrailerRejectsWholeFile(String tail) {
        rejects(HEADER + ROW + tail, "FILE_INCOMPLETE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "100000000", "1.2", "+10", "9223372036854775808", "9007199254740992"})
    void ac_5_10_invalidAmountsRejectWholeFile(String amount) {
        rejects((HEADER + ROW + TAIL).replace("9680", amount), "FILE_INCOMPLETE");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"unterminated",
                "bad\"quote",
                "\"closed\"bad",
                "\"\"\"",
                "bad\nrecord",
                "bad\rrecord",
                "bad\u0000"
            })
    void ac_5_10_badQuotingRejectsWholeFile(String memo) {
        rejects(HEADER + ROW.replace("memo", memo) + TAIL, "FILE_INCOMPLETE");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SIM-CAD-001|wrong",
                "BANK_STATEMENT|OTHER",
                "2026-11-09|2026-02-30",
                "2026-11-09|2028-11-09",
                "2026-11-09|2026-1-9",
                "CREDIT|OTHER",
                "BOOKED|OTHER",
                "CAD|cad",
                ",1,CAD|,0,CAD",
                "t1| "
            })
    void ac_5_10_badMetadataRejectsWholeFile(String change) {
        String[] parts = change.split("\\|", -1);
        rejects((HEADER + ROW + TAIL).replace(parts[0], parts[1]), "FILE_INCOMPLETE");
    }

    @Test
    void ac_5_10_strictRecordsAndUtf8() {
        for (String text : new String[] {
            "",
            "\ufeff" + HEADER + ROW + TAIL,
            HEADER + "\r\n" + ROW + TAIL,
            (HEADER + ROW + TAIL).replace("H,BANK", "H,extra,BANK"),
            HEADER + ROW.replace("D,t1", "D,t1,extra") + TAIL,
            HEADER.replace("2026-11-09,1", "2026-11-10,1") + ROW + TAIL
        }) {
            rejects(text, "FILE_INCOMPLETE");
        }
        assertThatThrownBy(() -> BankCsv.parse(new byte[] {(byte) 0xc3, (byte) 0x28}))
                .isInstanceOf(BankCsv.InvalidFile.class)
                .hasMessage("FILE_INCOMPLETE");
    }

    @Test
    void ac_5_12_balanceAndPendingTotals() {
        rejects(HEADER + ROW + TAIL.replace("0,9680", "0,9681"), "BALANCE_MISMATCH");
        assertThat(parse(HEADER + ROW.replace("BOOKED", "PENDING") + "T,1,0,0,0,0\r\n")
                        .closing())
                .isZero();
        assertThat(parse(HEADER + ROW.replace("CREDIT", "DEBIT") + "T,1,0,9680,9680,0\r\n")
                        .closing())
                .isZero();
    }

    private static BankCsv.Statement parse(String csv) {
        return BankCsv.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static void rejects(String csv, String reason) {
        assertThatThrownBy(() -> parse(csv))
                .isInstanceOf(BankCsv.InvalidFile.class)
                .hasMessage(reason);
    }
}
