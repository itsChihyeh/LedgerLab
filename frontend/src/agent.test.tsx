import { renderToStaticMarkup } from "react-dom/server";
import { expect, test } from "vitest";
import { AgentUnavailable, RunView } from "./agent";
import {
  ACTION_NAMES,
  acceptRequest,
  adoptionPlan,
  type Citation,
  citationHref,
  correctionRequest,
  type InvestigationRun,
  ROOT_CAUSE_NAMES,
} from "./agent-model";

const issue = { id: "iss_1", status: "OPEN" };

function makeRun(
  over: Partial<InvestigationRun["conclusion"]> = {},
  run: Partial<InvestigationRun> = {},
): InvestigationRun {
  return {
    runId: "inv_01ABC",
    issueId: "iss_1",
    model: "stub",
    modelIsStandIn: true,
    startedAt: "2026-11-06T10:00:00.000Z",
    finishedAt: "2026-11-06T10:00:01.000Z",
    status: "ok",
    conclusion: {
      issue_id: "iss_1",
      root_cause: "BANK_SPLIT_DEPOSIT",
      confidence: "high",
      abstain: false,
      explanation: "两笔入账合计等于批次净额",
      citations: [
        { kind: "issue", id: "iss_1" },
        { kind: "batch", id: "bat_1" },
        { kind: "bank_line", id: "bank_b" },
        { kind: "bank_line", id: "bank_a" },
      ],
      sop_clauses: ["SOP-4.2"],
      recommended_action: "PROPOSE_CORRECTION_FOR_REVIEW",
      ...over,
    },
    toolCalls: [
      { tool: "get_issue", args: {}, status: 200, ok: true, asOf: "x" },
      { tool: "get_batch", args: {}, status: 200, ok: true, asOf: "x" },
    ],
    asOf: ["x"],
    verification: {
      fabricatedCitations: [],
      fabricatedExplanationIds: [],
      supported: true,
    },
    usage: { inputTokens: 0, outputTokens: 0, isEstimate: true },
    ...run,
  };
}

test("ac_11_3 citationHref maps all 12 kinds", () => {
  const cases: [string, string, string | null][] = [
    ["issue", "iss_1", "#/recon/iss_1"],
    ["payment", "pay_1", "#/payments/pay_1"],
    ["event", "evt_1", "#/events/evt_1"],
    ["entry", "ent_1", "#/entries/ent_1"],
    ["batch", "bat_1", "#/batches/bat_1"],
    ["batch_line", "bat_1/3", "#/batch-lines/bat_1/3"],
    ["bank_line", "bank_a", "#/bank-lines/bank_a"],
    ["statement_line", "file 1/7", "#/statement-lines/file%201/7"],
    ["refund", "ref_1", "#/refunds/ref_1"],
    ["payout", "mpo_1", "#/payouts/mpo_1"],
    ["correction", "cor_1", "#/corrections/cor_1"],
    ["run", "run_1", null],
  ];
  for (const [kind, id, expected] of cases)
    expect(citationHref({ kind, id }), kind).toBe(expected);
  expect(citationHref({ kind: "mystery", id: "x" })).toBeNull();
  expect(citationHref({ kind: "batch_line", id: "no-slash" })).toBeNull();
});

test("ac_11_3 RunView shows names, enums, explanation, SOP, calls and the stand-in notice", () => {
  const html = renderToStaticMarkup(<RunView run={makeRun()} />);
  expect(html).toContain(ROOT_CAUSE_NAMES.BANK_SPLIT_DEPOSIT);
  expect(html).toContain("BANK_SPLIT_DEPOSIT");
  expect(html).toContain(ACTION_NAMES.PROPOSE_CORRECTION_FOR_REVIEW);
  expect(html).toContain("PROPOSE_CORRECTION_FOR_REVIEW");
  expect(html).toContain("两笔入账合计等于批次净额");
  expect(html).toContain("SOP-4.2");
  expect(html).toContain("2 次");
  expect(html).toContain("本地替身（规则基线），不代表大模型能力");
  expect(html).toContain('href="#/bank-lines/bank_a"');
});

test("ac_11_3 fabricated citations are marked unread and not linked; explanation is escaped", () => {
  const fake: Citation = { kind: "bank_line", id: "bank_fake" };
  const run = makeRun(
    {
      explanation: "<script>alert(1)</script>",
      citations: [{ kind: "issue", id: "iss_1" }, fake],
    },
    {
      verification: {
        fabricatedCitations: [fake],
        fabricatedExplanationIds: [],
        supported: false,
      },
    },
  );
  const html = renderToStaticMarkup(<RunView run={run} />);
  expect(html).toContain("未在本次调查中读到");
  expect(html).not.toContain("bank-lines/bank_fake");
  expect(html).toContain('href="#/recon/iss_1"');
  expect(html).not.toContain("<script>");
  expect(html).toContain("&lt;script&gt;");
});

test("ac_11_4 adoptionPlan: each reason for no adoption", () => {
  const why = (r: InvestigationRun, i = issue) => {
    const plan = adoptionPlan(r, i);
    return plan.kind === "none" ? plan.why : plan.kind;
  };
  expect(why(makeRun(), { id: "iss_1", status: "ACCEPTED" })).toBe(
    "issue_not_open",
  );
  expect(why(makeRun({}, { status: "budget_exhausted" }))).toBe("run_not_ok");
  expect(
    why(makeRun({ abstain: true, root_cause: "INSUFFICIENT_EVIDENCE" })),
  ).toBe("abstained");
  expect(
    why(
      makeRun(
        {},
        {
          verification: {
            fabricatedCitations: [{ kind: "batch", id: "x" }],
            fabricatedExplanationIds: [],
            supported: false,
          },
        },
      ),
    ),
  ).toBe("fabricated_citations");
  expect(
    why(
      makeRun(
        {},
        {
          verification: {
            fabricatedCitations: [],
            fabricatedExplanationIds: ["bank_zz"],
            supported: true,
          },
        },
      ),
    ),
  ).toBe("fabricated_citations");
  expect(why(makeRun({ root_cause: "WEBHOOK_LOST" }))).toBe(
    "no_adoptable_action",
  );
  expect(
    why(
      makeRun({
        citations: [
          { kind: "batch", id: "bat_1" },
          { kind: "batch", id: "bat_2" },
          { kind: "bank_line", id: "a" },
          { kind: "bank_line", id: "b" },
        ],
      }),
    ),
  ).toBe("insufficient_citations");
  expect(
    why(
      makeRun({
        citations: [
          { kind: "batch", id: "bat_1" },
          { kind: "bank_line", id: "a" },
        ],
      }),
    ),
  ).toBe("insufficient_citations");
});

test("ac_11_4 only SPLIT and FEE are adoptable among the 13 causes", () => {
  const causes = Object.keys(ROOT_CAUSE_NAMES).filter(
    (c) => c !== "INSUFFICIENT_EVIDENCE",
  );
  expect(causes).toHaveLength(13);
  const adoptable = causes.filter(
    (c) => adoptionPlan(makeRun({ root_cause: c }), issue).kind !== "none",
  );
  expect(adoptable.sort()).toEqual([
    "BANK_SPLIT_DEPOSIT",
    "PROCESSOR_FEE_DIFFERS",
  ]);
  expect(Object.keys(ACTION_NAMES)).toHaveLength(6);
});

test("ac_11_4 SPLIT plan prefills sorted bank lines, evidence and note; FEE plan accepts", () => {
  const plan = adoptionPlan(makeRun(), issue);
  if (plan.kind !== "apply_unapplied_cash") throw new Error(plan.kind);
  expect(plan.batchId).toBe("bat_1");
  expect(plan.bankTxnIds).toEqual(["bank_a", "bank_b"]);
  expect(plan.evidence).toEqual([
    { type: "issue", id: "iss_1" },
    { type: "bank_txn", id: "bank_a" },
    { type: "bank_txn", id: "bank_b" },
  ]);
  expect(plan.note.startsWith("采纳调查 inv_01ABC（stub）：")).toBe(true);
  expect(
    adoptionPlan(makeRun({ root_cause: "PROCESSOR_FEE_DIFFERS" }), issue),
  ).toEqual({
    kind: "accept",
    reason: "采纳调查 inv_01ABC（stub）：两笔入账合计等于批次净额",
  });
});

test("ac_11_4 correctionRequest and acceptRequest build the exact bodies", () => {
  const plan = adoptionPlan(makeRun(), issue);
  if (plan.kind !== "apply_unapplied_cash") throw new Error(plan.kind);
  expect(correctionRequest(plan, "  ops-1 ", "核对过")).toEqual({
    type: "APPLY_UNAPPLIED_CASH",
    batchId: "bat_1",
    bankTxnIds: ["bank_a", "bank_b"],
    reasonCode: "SPLIT_DEPOSIT",
    reasonNote: "核对过",
    evidence: plan.evidence,
    requestedBy: "ops-1",
  });
  expect(() => correctionRequest(plan, "   ", "x")).toThrow();
  expect(
    correctionRequest(plan, "o", "字".repeat(2001)).reasonNote,
  ).toHaveLength(2000);
  expect(correctionRequest(plan, "o", "😀".repeat(2001)).reasonNote).toBe(
    "😀".repeat(2000),
  );
  expect(acceptRequest({ kind: "accept", reason: "r" }, " 手续费 ")).toEqual({
    reason: "手续费",
  });
  expect(
    acceptRequest({ kind: "accept", reason: "r" }, "a".repeat(2500)).reason,
  ).toHaveLength(2000);
});

test("ac_11_5 unavailable agent shows only the not-started notice", () => {
  const html = renderToStaticMarkup(<AgentUnavailable />);
  expect(html).toContain("调查助手未启动");
});
