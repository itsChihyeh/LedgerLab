package dev.ledgerlab.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.corrections.CorrectionCommand;
import dev.ledgerlab.corrections.Corrections;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionType;
import dev.ledgerlab.payments.ChargeEvidence;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.settlement.BatchSettlement;
import dev.ledgerlab.settlement.PayoutEvidence;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Synthetic external evidence plus real capture, settlement and correction services for sample E. */
public final class CorrectionPageFixture {
    private CorrectionPageFixture() {}

    public record Sample(String correctionId, String issueId, String entryId, String fileId, String paymentId) {}

    public static Sample seed(ApplicationContext app) {
        var jdbc = app.getBean(JdbcClient.class);
        var json = app.getBean(ObjectMapper.class);
        var tx = app.getBean(TransactionTemplate.class);
        var now = app.getBean(Clock.class).instant();
        var at = now.atOffset(ZoneOffset.UTC);
        for (String event : List.of("evt_t07_p5", "evt_t07_b6")) {
            jdbc.sql("""
                    INSERT INTO io.inbox_event(event_id,event_type,api_version,livemode,payload,payload_sha256,
                    signed_at,occurred_at,received_at,recorded_at,status,next_attempt_at,result)
                    VALUES (?,?,'2026-09-30.endive',false,'{}'::bytea,encode(sha256('{}'::bytea),'hex'),
                    ?,?,?,?,'PROCESSED',?,'APPLIED')
                    """)
                    .params(
                            event,
                            event.endsWith("p5") ? "charge.succeeded" : "payout.reconciliation_completed",
                            at,
                            at,
                            at,
                            at,
                            at)
                    .update();
        }
        tx.executeWithoutResult(ignored -> app.getBean(PaymentCapture.class)
                .capture(new ChargeEvidence(
                        "evt_t07_p5", "ch_t07_p5", null, 4500L, 4500L, "cad", true, "succeeded", false, now, now)));
        String entry = jdbc.sql("SELECT id FROM ledger.journal_entry WHERE source_key='payment:ch_t07_p5:captured'")
                .query(String.class)
                .single();
        String payment = jdbc.sql("SELECT id FROM core.payment WHERE processor_payment_ref='ch_t07_p5'")
                .query(String.class)
                .single();
        String page = json.writeValueAsString(Map.of(
                "object",
                "list",
                "has_more",
                false,
                "data",
                List.of(Map.of(
                        "id",
                        "txn_t07_b6",
                        "type",
                        "charge",
                        "source",
                        "ch_t07_p5",
                        "amount",
                        5400,
                        "fee",
                        187,
                        "net",
                        5213,
                        "currency",
                        "cad",
                        "created",
                        now.getEpochSecond()))));
        String archive = json.writeValueAsString(List.of(page));
        long fetch =
                jdbc.sql("""
                INSERT INTO io.processor_fetch(purpose,request,response_body,response_sha256,complete,fetched_at,recorded_at)
                VALUES ('BATCH_LINES','{"query":{"payout":"po_t07_b6"}}'::jsonb,?,
                encode(sha256(convert_to(?,'UTF8')),'hex'),true,?,?) RETURNING id
                """).params(archive, archive, at, at).query(Long.class).single();
        var payout = new PayoutEvidence(
                "evt_t07_b6",
                "inbox_event",
                "evt_t07_b6",
                "payout.reconciliation_completed",
                "po_t07_b6",
                5213,
                "cad",
                true,
                "standard",
                "completed",
                now,
                now,
                new PayoutEvidence.Lines(
                        fetch,
                        List.of(new PayoutEvidence.Line(
                                "txn_t07_b6", "charge", "ch_t07_p5", 5400, 187, 5213, "cad", now))));
        tx.executeWithoutResult(ignored -> app.getBean(BatchSettlement.class).process(payout));
        assertThat(jdbc.sql("SELECT status FROM core.settlement_batch WHERE batch_id='po_t07_b6'")
                        .query(String.class)
                        .single())
                .isEqualTo("HELD");
        String issue = jdbc.sql("SELECT id FROM recon.issue WHERE subject_id='po_t07_b6' AND status='OPEN'")
                .query(String.class)
                .single();
        String file = app.getBean(IdGenerator.class).next("file");
        tx.executeWithoutResult(ignored -> {
            jdbc.sql("""
                    INSERT INTO io.imported_file(id,kind,source_name,sha256,statement_date,version,status,raw,received_at,recorded_at)
                    VALUES (?,'PROCESSOR_STATEMENT','synthetic sample E',encode(sha256(?),'hex'),?,1,'ACCEPTED',?,?,?)
                    """)
                    .params(
                            file,
                            archive.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            at.toLocalDate(),
                            archive.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            at,
                            at)
                    .update();
            jdbc.sql("""
                    INSERT INTO recon.statement_line(file_id,line_seq,statement_line_id,line_type,processor_ref,batch_id,
                    gross,fee,net,occurred_at,settlement_date,memo,recorded_at)
                    VALUES (?,1,'txn_t07_b6','PAYMENT','ch_t07_p5','po_t07_b6',5400,187,5213,?,?,'sample E',?)
                    """).params(file, at, at.toLocalDate(), at).update();
        });
        var result = app.getBean(Corrections.class)
                .submit(new CorrectionCommand(
                        CorrectionType.RESTATE_CAPTURE_AMOUNT,
                        entry,
                        5400L,
                        null,
                        null,
                        CorrectionReason.CAPTURE_AMOUNT_ERROR,
                        "样例 E：P5 误记 4500，按 B6 结算单修正为 5400",
                        List.of(
                                new CorrectionCommand.Evidence("issue", issue),
                                new CorrectionCommand.Evidence("statement_line", "txn_t07_b6"),
                                new CorrectionCommand.Evidence("inbox_event", "evt_t07_p5"),
                                new CorrectionCommand.Evidence("processor_fetch", Long.toString(fetch))),
                        "synthetic-operator"));
        assertThat(jdbc.sql("SELECT status FROM core.settlement_batch WHERE batch_id='po_t07_b6'")
                        .query(String.class)
                        .single())
                .isEqualTo("CONFIRMED");
        return new Sample((String) result.correction().get("id"), issue, entry, file, payment);
    }
}
