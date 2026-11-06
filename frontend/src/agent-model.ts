// Pure model for the investigation panel (docs/design/agent-workbench-and-mcp.md section 3).
// No React, no fetch: everything here is testable with plain calls.

export type Citation = { kind: string; id: string };

export interface Conclusion {
  issue_id: string;
  root_cause: string;
  confidence: string;
  abstain: boolean;
  explanation: string;
  citations: Citation[];
  sop_clauses: string[];
  recommended_action: string;
}

export interface InvestigationRun {
  runId: string;
  issueId: string;
  model: string;
  modelIsStandIn: boolean;
  startedAt: string;
  finishedAt: string;
  status: "ok" | "budget_exhausted" | "invalid_output" | "error";
  error?: string;
  conclusion: Conclusion;
  toolCalls: {
    tool: string;
    args: Record<string, unknown>;
    status: number | null;
    ok: boolean;
    asOf: string | null;
  }[];
  asOf: string[];
  verification: {
    fabricatedCitations: Citation[];
    fabricatedExplanationIds: string[];
    supported: boolean | null;
  };
  usage: { inputTokens: number; outputTokens: number; isEstimate: boolean };
}

export interface AgentHealth {
  status: string;
  model: string;
  modelIsStandIn: boolean;
  source: "base-url" | "snapshot";
}

export const MAX_NOTE_CHARS = 2000;

export const ROOT_CAUSE_NAMES: Record<string, string> = {
  BANK_SHORT_DEPOSIT: "银行少入账",
  BANK_SPLIT_DEPOSIT: "银行拆分入账",
  BANK_DUPLICATE_CREDIT: "银行重复入账",
  BANK_FOREIGN_CURRENCY_LABEL: "银行外币标签",
  BANK_UNKNOWN_CREDIT: "银行不明入账",
  BANK_DEPOSIT_DELAYED: "银行入账延迟",
  BANK_PENDING_FIRST: "银行先挂起后入账",
  PROCESSOR_DUPLICATE_LINE: "处理方批次重复行",
  PROCESSOR_UNKNOWN_CHARGE_LINE: "处理方批次出现未知扣款行",
  PROCESSOR_UNSUPPORTED_ADJUSTMENT: "处理方不支持的调整项",
  PROCESSOR_FEE_DIFFERS: "处理方手续费不一致",
  PROCESSOR_PAYOUT_AMOUNT_DIFFERS: "处理方打款金额不一致",
  WEBHOOK_LOST: "webhook 丢失",
  INSUFFICIENT_EVIDENCE: "证据不足",
};

export const ACTION_NAMES: Record<string, string> = {
  WAIT_FOR_NEXT_STATEMENT: "等待下一期银行对账单",
  REQUEST_BANK_TRACE: "向银行发起查询",
  CONTACT_PROCESSOR: "联系处理方",
  PROPOSE_CORRECTION_FOR_REVIEW: "提出纠错供复核",
  MATCH_MANUALLY_AFTER_REVIEW: "复核后人工匹配",
  ESCALATE: "升级处理",
};

export const STAND_IN_NOTICE = "本地替身（规则基线），不代表大模型能力";
export const ADVICE_NOTICE = "结论由 Agent 给出，未经人工确认前不改变任何数据";
export const UNAVAILABLE_NOTICE = "调查助手未启动";
export const NOT_READ_NOTICE = "未在本次调查中读到";
export const NO_ACTION_NOTICE = "没有可采纳的系统操作，请按建议线下处理";
export const INSUFFICIENT_CITATIONS_NOTICE = "引用不足以预填，请人工处理";

const enc = encodeURIComponent;

const SIMPLE_PAGES: Record<string, string> = {
  issue: "recon",
  payment: "payments",
  event: "events",
  entry: "entries",
  batch: "batches",
  bank_line: "bank-lines",
  refund: "refunds",
  payout: "payouts",
  correction: "corrections",
};

const splitId = (id: string): [string, string] | null => {
  const slash = id.lastIndexOf("/");
  if (slash <= 0 || slash === id.length - 1) return null;
  return [id.slice(0, slash), id.slice(slash + 1)];
};

/** Workbench route for a citation; null for `run`, unknown kinds and malformed composite ids. */
export function citationHref(citation: Citation): string | null {
  const page = SIMPLE_PAGES[citation.kind];
  if (page) return `#/${page}/${enc(citation.id)}`;
  if (citation.kind === "batch_line" || citation.kind === "statement_line") {
    const parts = splitId(citation.id);
    if (!parts) return null;
    const route =
      citation.kind === "batch_line" ? "batch-lines" : "statement-lines";
    return `#/${route}/${enc(parts[0])}/${enc(parts[1])}`;
  }
  return null;
}

export type NoneReason =
  | "issue_not_open"
  | "run_not_ok"
  | "abstained"
  | "fabricated_citations"
  | "no_adoptable_action"
  | "insufficient_citations";

export const NONE_MESSAGES: Record<NoneReason, string> = {
  issue_not_open: "差异已不是 OPEN，不能采纳",
  run_not_ok: "本次调查没有正常完成，不能采纳",
  abstained: "Agent 拒答，没有可采纳的结论",
  fabricated_citations: "有引用未通过核验，不能采纳",
  no_adoptable_action: NO_ACTION_NOTICE,
  insufficient_citations: INSUFFICIENT_CITATIONS_NOTICE,
};

export type CashPlan = {
  kind: "apply_unapplied_cash";
  batchId: string;
  bankTxnIds: string[];
  evidence: { type: string; id: string }[];
  reasonCode: "SPLIT_DEPOSIT";
  note: string;
};
export type AcceptPlan = { kind: "accept"; reason: string };
export type AdoptionPlan =
  | CashPlan
  | AcceptPlan
  | { kind: "none"; why: NoneReason };

/** Counts code points, not UTF-16 units, so a surrogate pair is never cut in half. */
export function truncate(text: string, max = MAX_NOTE_CHARS): string {
  const points = Array.from(text);
  return points.length > max ? points.slice(0, max).join("") : text;
}

const adoptionNote = (run: InvestigationRun) =>
  truncate(
    `采纳调查 ${run.runId}（${run.model}）：${run.conclusion.explanation}`,
  );

/** The closed mapping of section 3.2: all conditions, in the documented order. */
export function adoptionPlan(
  run: InvestigationRun,
  issue: { id: string; status: string },
): AdoptionPlan {
  const c = run.conclusion;
  if (issue.status !== "OPEN") return { kind: "none", why: "issue_not_open" };
  if (run.status !== "ok") return { kind: "none", why: "run_not_ok" };
  if (c.abstain) return { kind: "none", why: "abstained" };
  if (
    run.verification.fabricatedCitations.length ||
    run.verification.fabricatedExplanationIds.length
  )
    return { kind: "none", why: "fabricated_citations" };
  if (c.root_cause === "PROCESSOR_FEE_DIFFERS")
    return { kind: "accept", reason: adoptionNote(run) };
  if (c.root_cause !== "BANK_SPLIT_DEPOSIT")
    return { kind: "none", why: "no_adoptable_action" };
  const batches = [
    ...new Set(c.citations.filter((x) => x.kind === "batch").map((x) => x.id)),
  ];
  const bankTxnIds = [
    ...new Set(
      c.citations.filter((x) => x.kind === "bank_line").map((x) => x.id),
    ),
  ].sort();
  if (batches.length !== 1 || bankTxnIds.length < 2)
    return { kind: "none", why: "insufficient_citations" };
  return {
    kind: "apply_unapplied_cash",
    batchId: batches[0],
    bankTxnIds,
    evidence: [
      { type: "issue", id: issue.id },
      ...bankTxnIds.map((id) => ({ type: "bank_txn", id })),
    ],
    reasonCode: "SPLIT_DEPOSIT",
    note: adoptionNote(run),
  };
}

const requireText = (value: string, what: string) => {
  const text = value.trim();
  if (!text) throw new Error(`${what}必填`);
  return text;
};

/** Body for POST /api/corrections; field names follow CorrectionJson. */
export function correctionRequest(
  plan: CashPlan,
  requestedBy: string,
  note: string,
) {
  return {
    type: "APPLY_UNAPPLIED_CASH",
    batchId: plan.batchId,
    bankTxnIds: plan.bankTxnIds,
    reasonCode: plan.reasonCode,
    reasonNote: truncate(requireText(note, "备注")),
    evidence: plan.evidence,
    requestedBy: requireText(requestedBy, "操作人"),
  };
}

/** Body for POST /api/recon/issues/{id}/accept. */
export function acceptRequest(_plan: AcceptPlan, reason: string) {
  return { reason: truncate(requireText(reason, "接受原因")) };
}
