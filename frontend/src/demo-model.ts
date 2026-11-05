/** Pure data for the demo page (#/demo): the action list, how the live feed folds into state, the report parser. */

export interface DemoAction {
  name: string;
  title: string;
  proves: string;
}

/** The eight whitelisted actions (demo-and-delivery.md section 2.1), each with what it proves. */
export const ACTIONS: DemoAction[] = [
  {
    name: "pay",
    title: "收一笔款",
    proves: "处理方的 webhook 带签名进来，请款只记一次账。",
  },
  {
    name: "replay-webhook",
    title: "重复投递 webhook",
    proves: "同一条事件被投递两次，系统认出是重复，不再记账。",
  },
  {
    name: "batch-first",
    title: "结算批次先于支付",
    proves: "批次比它的支付先到，先停放为 HELD，支付到了自动重核通过。",
  },
  {
    name: "double-refund",
    title: "同时发两笔退款",
    proves: "两笔各占 60% 的退款同一瞬间到达，只有一笔被受理，另一笔被拒。",
  },
  {
    name: "drop-refund-response",
    title: "丢掉退款的响应",
    proves: "处理方已建好退款却没回话：结果未知时先查后发，最终只有一笔。",
  },
  {
    name: "bank-drop-receipt",
    title: "银行丢回执",
    proves: "提现已执行但回执丢了：状态 UNKNOWN 不释放资金，查询后变为 PAID。",
  },
  {
    name: "kill-worker",
    title: "让 worker 中途崩溃",
    proves:
      "崩溃后租约过期、重新领取（代数 +1），处理方只收到一笔退款，不重复执行。",
  },
  {
    name: "bad-statement",
    title: "导入有错的银行流水",
    proves: "一行金额不符、一行引用未知：对账开出差异，不会去改账。",
  },
];

export const INVARIANTS: { id: string; title: string }[] = [
  { id: "INV-1", title: "每个分录借贷平衡" },
  { id: "INV-2", title: "账户余额等于 posting 重算" },
  { id: "INV-3", title: "资产等于负债" },
  { id: "INV-4", title: "分录提交后封存" },
  { id: "INV-5", title: "关键账户不为负" },
  { id: "INV-6", title: "可用余额与提现门槛" },
  { id: "INV-7", title: "退款不超过请款" },
  { id: "INV-8", title: "同一业务事实只记一次" },
  { id: "INV-9", title: "业务状态与过渡账户一致" },
  { id: "INV-10", title: "资金效果唯一" },
  { id: "INV-11", title: "银行余额核对" },
  { id: "INV-12", title: "待查收款余额一致" },
];

export interface CounterCard {
  key: string;
  label: string;
  note: string;
}

/** Counters that come from real queries on the ledger and inbox evidence. */
export const LEDGER_COUNTERS: CounterCard[] = [
  { key: "payments", label: "支付", note: "账本里的支付笔数" },
  { key: "entries", label: "分录", note: "只追加的分录条数" },
  { key: "refunds", label: "退款", note: "已受理的退款笔数" },
  { key: "batches", label: "结算批次", note: "处理方批次数" },
  { key: "duplicatesAbsorbed", label: "被吸收的重复投递", note: "重复事件" },
  {
    key: "parkedThenResolved",
    label: "停放后自动恢复",
    note: "进过 HELD／PARKED 又离开",
  },
  {
    key: "unknownRecovered",
    label: "结果未知后恢复",
    note: "退款与提现",
  },
  {
    key: "workerCrashesRecovered",
    label: "崩溃后接管",
    note: "租约代数 +1 后完成",
  },
  { key: "discrepanciesOpened", label: "对账差异", note: "开出的差异记录" },
];

/** Counters the demo actions observed themselves; the refused refund leaves no row to query. */
export const OBSERVED_COUNTERS: CounterCard[] = [
  {
    key: "overRefundsRejected",
    label: "被拒的超额退款",
    note: "演示动作观察到的被拒次数",
  },
];

export interface Step {
  actionId: string;
  action: string;
  n: number;
  label: string;
  at: string;
}
export interface TimelineEntry {
  kind: string;
  key: string;
  label: string;
  at: string;
}
export interface Verdict {
  actionId: string;
  checkedAt: string;
  ok: boolean;
  checks: { id: string; ok: boolean }[];
  failed: string[];
  violations: { invariant: string; detail: string }[];
}
export type Counters = Record<string, number>;

export interface FeedItem {
  id: string;
  source: "step" | TimelineEntry["kind"];
  text: string;
  at: string;
}
export interface DemoState {
  feed: FeedItem[];
  verdict: Verdict | null;
  counters: Counters | null;
}

export const FEED_LIMIT = 300;
export const emptyState: DemoState = {
  feed: [],
  verdict: null,
  counters: null,
};

/** Folds one server-sent event into the page state; unknown or malformed events change nothing. */
export function applyEvent(
  state: DemoState,
  type: string,
  raw: string,
): DemoState {
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return state;
  }
  if (typeof data !== "object" || data === null) return state;
  if (type === "invariants") {
    return { ...state, verdict: data as Verdict };
  }
  if (type === "counters") {
    return { ...state, counters: data as Counters };
  }
  if (type === "step") {
    const step = data as Step;
    return addFeed(state, [
      {
        id: `step:${step.actionId}:${step.n}`,
        source: "step",
        text: step.label,
        at: step.at,
      },
    ]);
  }
  if (type === "timeline") {
    const items = (data as { items?: TimelineEntry[] }).items ?? [];
    return addFeed(
      state,
      items.map((item) => ({
        id: `${item.kind}:${item.key}`,
        source: item.kind,
        text: item.label,
        at: item.at,
      })),
    );
  }
  return state;
}

function addFeed(state: DemoState, added: FeedItem[]): DemoState {
  const known = new Set(state.feed.map((item) => item.id));
  const fresh = added.filter((item) => !known.has(item.id));
  if (!fresh.length) return state;
  // Newest first: the last of a batch is the newest.
  const feed = [...fresh.reverse(), ...state.feed].slice(0, FEED_LIMIT);
  return { ...state, feed };
}

export const SOURCE_LABELS: Record<string, string> = {
  step: "动作",
  inbox: "入站事件",
  delivery: "投递",
  entry: "分录",
  payment: "支付状态",
  refund: "退款状态",
  batch: "批次状态",
  payout: "提现状态",
  issue: "对账差异",
};

/** The object a violation names, for example "payment pay_x" in "payment pay_x: refunds 1 exceed captured 2". */
export function violationObject(
  detail: string,
): { kind: string; id: string } | null {
  const found = detail.match(
    /\b(payment|refund|payout|batch|entry)\s+([A-Za-z0-9][A-Za-z0-9_-]*)/,
  );
  return found ? { kind: found[1], id: found[2] } : null;
}

export interface SandboxReport {
  runId: string | null;
  generatedAt: string | null;
  facts: { name: string; value: number }[];
}

/**
 * Reads the redacted sandbox report (docs/runs/sandbox-*.json) for display. The report's exact layout belongs to
 * the sandbox tooling, so this keeps the run id and time when present and lists every number it contains, with
 * the path to the number as its name. Anything that is not a JSON object is "no report".
 */
export function parseSandboxReport(value: unknown): SandboxReport | null {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return null;
  const record = value as Record<string, unknown>;
  const text = (...keys: string[]) => {
    for (const key of keys) {
      const found = record[key];
      if (typeof found === "string" && found) return found;
    }
    return null;
  };
  const facts: { name: string; value: number }[] = [];
  const walk = (node: unknown, path: string, depth: number) => {
    if (facts.length >= 60) return;
    if (typeof node === "number" && Number.isFinite(node)) {
      facts.push({ name: path, value: node });
    } else if (depth < 3 && node && typeof node === "object") {
      if (Array.isArray(node)) {
        facts.push({ name: `${path}（条数）`, value: node.length });
      } else {
        for (const [key, child] of Object.entries(node))
          walk(child, path ? `${path} · ${key}` : key, depth + 1);
      }
    }
  };
  walk(record, "", 0);
  const runId = text("run_id", "runId");
  const generatedAt = text(
    "generated_at",
    "generatedAt",
    "finished_at",
    "finishedAt",
    "started_at",
    "startedAt",
  );
  return { runId, generatedAt, facts };
}
