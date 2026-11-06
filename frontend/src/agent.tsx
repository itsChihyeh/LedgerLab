import { useCallback, useEffect, useState } from "react";
import {
  ACTION_NAMES,
  type AcceptPlan,
  ADVICE_NOTICE,
  type AdoptionPlan,
  type AgentHealth,
  acceptRequest,
  adoptionPlan,
  type CashPlan,
  type Citation,
  citationHref,
  correctionRequest,
  type InvestigationRun,
  NONE_MESSAGES,
  NOT_READ_NOTICE,
  ROOT_CAUSE_NAMES,
  STAND_IN_NOTICE,
  UNAVAILABLE_NOTICE,
} from "./agent-model";
import { enc } from "./components";

const named = (names: Record<string, string>, key: string) =>
  names[key] ? `${names[key]}（${key}）` : key;

export function AgentUnavailable() {
  return <p role="status">{UNAVAILABLE_NOTICE}</p>;
}

export function CitationList({
  citations,
  fabricated,
}: {
  citations: Citation[];
  fabricated: Citation[];
}) {
  const unread = new Set(fabricated.map((c) => `${c.kind}\u0000${c.id}`));
  if (!citations.length) return <p>无引用</p>;
  return (
    <ul className="agent-citations">
      {citations.map((c) => {
        const link = citationHref(c);
        const label = `${c.kind} · ${c.id}`;
        return (
          <li key={`${c.kind}:${c.id}`}>
            {unread.has(`${c.kind}\u0000${c.id}`) ? (
              <>
                {label} <mark>{NOT_READ_NOTICE}</mark>
              </>
            ) : link ? (
              <a href={link}>{label}</a>
            ) : (
              label
            )}
          </li>
        );
      })}
    </ul>
  );
}

export function RunView({ run }: { run: InvestigationRun }) {
  const c = run.conclusion;
  return (
    <article className="agent-run" data-run-id={run.runId}>
      <dl className="summary">
        <div>
          <dt>调查</dt>
          <dd>
            {run.runId} · {run.startedAt}
          </dd>
        </div>
        <div>
          <dt>模型</dt>
          <dd>
            {run.model}
            {run.modelIsStandIn && (
              <small className="agent-standin">{STAND_IN_NOTICE}</small>
            )}
          </dd>
        </div>
        <div>
          <dt>状态</dt>
          <dd>
            {run.status}
            {run.error ? ` · ${run.error}` : ""}
          </dd>
        </div>
        <div>
          <dt>原因</dt>
          <dd>{named(ROOT_CAUSE_NAMES, c.root_cause)}</dd>
        </div>
        <div>
          <dt>置信度</dt>
          <dd>{c.confidence}</dd>
        </div>
        <div>
          <dt>解释</dt>
          <dd className="agent-explanation">{c.explanation}</dd>
        </div>
        <div>
          <dt>建议动作</dt>
          <dd>{named(ACTION_NAMES, c.recommended_action)}</dd>
        </div>
        <div>
          <dt>SOP 条款</dt>
          <dd>{c.sop_clauses.length ? c.sop_clauses.join("、") : "—"}</dd>
        </div>
        <div>
          <dt>工具调用</dt>
          <dd>
            {run.toolCalls.length} 次
            {run.asOf.length ? ` · asOf ${run.asOf.join("、")}` : ""}
          </dd>
        </div>
      </dl>
      <h3>引用</h3>
      <CitationList
        citations={c.citations}
        fabricated={run.verification.fabricatedCitations}
      />
    </article>
  );
}

async function send(path: string, body: unknown): Promise<string | null> {
  try {
    const response = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (response.ok) return null;
    let code = "";
    try {
      const data = await response.json();
      if (typeof data?.code === "string") code = data.code;
    } catch {
      // not JSON: fall back to the status below
    }
    if (code) return `${code}（HTTP ${response.status}）`;
    return response.status === 409
      ? "差异状态已变化，请刷新后核对（HTTP 409）"
      : `HTTP ${response.status}`;
  } catch {
    return "网络错误，请重试";
  }
}

function AdoptForm({
  plan,
  issueId,
  onChanged,
}: {
  plan: CashPlan | AcceptPlan;
  issueId: string;
  onChanged: () => void;
}) {
  const [text, setText] = useState(
    plan.kind === "accept" ? plan.reason : plan.note,
  );
  const [operator, setOperator] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const cash = plan.kind === "apply_unapplied_cash";
  return (
    <form
      className="accept agent-adopt"
      aria-label="采纳调查结论"
      onSubmit={async (event) => {
        event.preventDefault();
        if (busy) return;
        let path: string;
        let body: unknown;
        try {
          if (plan.kind === "accept") {
            path = `/api/recon/issues/${enc(issueId)}/accept`;
            body = acceptRequest(plan, text);
          } else {
            path = "/api/corrections";
            body = correctionRequest(plan, operator, text);
          }
        } catch (e: unknown) {
          setError(e instanceof Error ? e.message : "表单不完整");
          return;
        }
        setBusy(true);
        setError("");
        const failure = await send(path, body);
        if (failure) {
          setError(`提交失败：${failure}`);
          setBusy(false);
        } else onChanged();
      }}
    >
      <h3>{cash ? "待查收款归位（APPLY_UNAPPLIED_CASH）" : "接受差异"}</h3>
      {plan.kind === "apply_unapplied_cash" && (
        <dl className="summary">
          <div>
            <dt>类型</dt>
            <dd>APPLY_UNAPPLIED_CASH</dd>
          </div>
          <div>
            <dt>批次</dt>
            <dd>{plan.batchId}</dd>
          </div>
          <div>
            <dt>银行流水</dt>
            <dd>{plan.bankTxnIds.join("、")}</dd>
          </div>
          <div>
            <dt>原因码</dt>
            <dd>{plan.reasonCode}</dd>
          </div>
          <div>
            <dt>证据</dt>
            <dd>{plan.evidence.map((e) => `${e.type}:${e.id}`).join("、")}</dd>
          </div>
        </dl>
      )}
      <label>
        {cash ? "备注（可编辑）" : "接受原因（可编辑）"}
        <textarea
          required
          maxLength={2000}
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
      </label>
      {cash && (
        <label>
          操作人
          <input
            required
            value={operator}
            onChange={(e) => setOperator(e.target.value)}
          />
        </label>
      )}
      <button type="submit" disabled={busy}>
        {busy ? "提交中…" : "提交"}
      </button>
      {error && <p role="alert">{error}</p>}
    </form>
  );
}

export function Adoption({
  run,
  issue,
  onChanged,
}: {
  run: InvestigationRun;
  issue: { id: string; status: string };
  onChanged: () => void;
}) {
  const [open, setOpen] = useState(false);
  const plan: AdoptionPlan = adoptionPlan(run, issue);
  if (plan.kind === "none")
    return plan.why === "no_adoptable_action" ||
      plan.why === "insufficient_citations" ? (
      <p className="agent-none">{NONE_MESSAGES[plan.why]}</p>
    ) : null;
  if (!open)
    return (
      <button type="button" onClick={() => setOpen(true)}>
        采纳
      </button>
    );
  return <AdoptForm plan={plan} issueId={issue.id} onChanged={onChanged} />;
}

type Health = "loading" | "down" | AgentHealth;

export function AgentPanel({
  issue,
  onChanged,
}: {
  issue: { id: string; status: string };
  onChanged: () => void;
}) {
  const [health, setHealth] = useState<Health>("loading");
  const [runs, setRuns] = useState<InvestigationRun[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const load = useCallback(
    async (signal: AbortSignal) => {
      try {
        const h = await fetch("/agent/health", { signal });
        if (!h.ok) throw new Error("down");
        const data: AgentHealth = await h.json();
        const list = await fetch(
          `/agent/investigations?issueId=${enc(issue.id)}`,
          { signal },
        );
        const body = list.ok ? await list.json() : { investigations: [] };
        if (signal.aborted) return;
        setRuns(body.investigations ?? []);
        setHealth(data);
      } catch {
        if (!signal.aborted) setHealth("down");
      }
    },
    [issue.id],
  );
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);
  if (health === "loading") return null;
  return (
    <section aria-label="调查助手" className="agent-panel">
      <h2>调查助手</h2>
      {health === "down" ? (
        <AgentUnavailable />
      ) : (
        <>
          <p>{ADVICE_NOTICE}</p>
          <button
            type="button"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              setError("");
              try {
                const response = await fetch("/agent/investigations", {
                  method: "POST",
                  headers: { "Content-Type": "application/json" },
                  body: JSON.stringify({ issueId: issue.id }),
                });
                if (response.status === 409) setError("已有调查在进行，请稍后");
                else if (!response.ok)
                  setError(`调查失败（HTTP ${response.status}）`);
                else {
                  const run: InvestigationRun = await response.json();
                  setRuns((prev) => [run, ...prev]);
                }
              } catch {
                setError("调查助手未响应，请稍后重试");
              }
              setBusy(false);
            }}
          >
            {busy ? "调查中…" : "让 Agent 调查"}
          </button>
          {error && <p role="alert">{error}</p>}
          {runs.length === 0 ? (
            <p>还没有调查记录</p>
          ) : (
            <>
              <RunView run={runs[0]} />
              <Adoption run={runs[0]} issue={issue} onChanged={onChanged} />
              {runs.length > 1 && (
                <details>
                  <summary>更早的调查（{runs.length - 1} 次）</summary>
                  {runs.slice(1).map((r) => (
                    <RunView key={r.runId} run={r} />
                  ))}
                </details>
              )}
            </>
          )}
        </>
      )}
    </section>
  );
}
