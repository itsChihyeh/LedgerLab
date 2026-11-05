import { type ReactNode, useEffect, useState } from "react";
import { href } from "./components";
import {
  ACTIONS,
  type Counters,
  type DemoAction,
  type DemoState,
  emptyState,
  applyEvent as foldEvent,
  INVARIANTS,
  LEDGER_COUNTERS,
  OBSERVED_COUNTERS,
  parseSandboxReport,
  type SandboxReport,
  SOURCE_LABELS,
  type Verdict,
  violationObject,
} from "./demo-model";

const STREAM_EVENTS = ["step", "timeline", "invariants", "counters"] as const;
const ROUTES: Record<string, string> = {
  payment: "payments",
  refund: "refunds",
  payout: "payouts",
  batch: "batches",
  entry: "entries",
};

let visited = false;

function clock(at: string) {
  const time = new Date(at);
  return Number.isNaN(time.getTime())
    ? at
    : time.toLocaleTimeString("zh-CN", { hour12: false });
}

/** The live feed: the page state plus whether the stream is currently connected. */
export function useDemoStream() {
  const [state, setState] = useState<DemoState>(emptyState);
  const [connected, setConnected] = useState(false);
  useEffect(() => {
    const source = new EventSource("/api/demo/stream");
    source.onopen = () => setConnected(true);
    source.onerror = () => setConnected(false);
    for (const type of STREAM_EVENTS) {
      source.addEventListener(type, (event) =>
        setState((now) =>
          foldEvent(now, type, (event as MessageEvent<string>).data),
        ),
      );
    }
    return () => source.close();
  }, []);
  return { state, connected, setState };
}

export function InvariantPanel({ verdict }: { verdict: Verdict | null }) {
  const byId = new Map(verdict?.checks.map((check) => [check.id, check.ok]));
  return (
    <section aria-label="不变量面板" className="demo-panel">
      <h2>
        12 条资金不变量
        {verdict && (
          <span
            className="demo-verdict"
            data-ok={verdict.ok}
            role="status"
            aria-live="polite"
          >
            {verdict.ok ? "全部通过" : `${verdict.failed.length} 条失败`}
          </span>
        )}
      </h2>
      {!verdict && <p role="status">正在检查…</p>}
      <ul className="demo-invariants">
        {INVARIANTS.map(({ id, title }) => {
          const ok = byId.get(id);
          const status = ok === undefined ? "unknown" : ok ? "ok" : "failed";
          return (
            <li key={id} data-invariant={id} data-status={status}>
              <b>{id}</b>
              <span>{title}</span>
              <em>{{ ok: "通过", failed: "失败", unknown: "—" }[status]}</em>
            </li>
          );
        })}
      </ul>
      {verdict && verdict.violations.length > 0 && (
        <div role="alert" className="demo-violations">
          <p>账本现在违反了下面这些不变量：</p>
          <ul>
            {verdict.violations.map((violation) => {
              const object = violationObject(violation.detail);
              const route = object && ROUTES[object.kind];
              return (
                <li key={`${violation.invariant}:${violation.detail}`}>
                  <b>{violation.invariant}</b> {violation.detail}
                  {object && route && (
                    <small>
                      对象：{object.kind}{" "}
                      <a href={href(route, object.id)}>{object.id}</a>
                    </small>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      )}
      {verdict && (
        <small>
          最近一次检查：
          <time dateTime={verdict.checkedAt}>{verdict.checkedAt}</time>
        </small>
      )}
    </section>
  );
}

export function CounterCards({ counters }: { counters: Counters | null }) {
  const card = (key: string, label: string, note: string) => (
    <div key={key} className="demo-counter" data-counter={key}>
      <dt>{label}</dt>
      <dd>{counters ? (counters[key] ?? 0) : "—"}</dd>
      <small>{note}</small>
    </div>
  );
  return (
    <section aria-label="计数卡片" className="demo-panel">
      <h2>计数</h2>
      <h3>来自账本查询</h3>
      <dl className="demo-counters">
        {LEDGER_COUNTERS.map((c) => card(c.key, c.label, c.note))}
      </dl>
      <h3>演示动作观察到的</h3>
      <dl className="demo-counters">
        {OBSERVED_COUNTERS.map((c) => card(c.key, c.label, c.note))}
      </dl>
    </section>
  );
}

export function TimelineList({ feed }: { feed: DemoState["feed"] }) {
  return (
    <section aria-label="事件时间线" className="demo-panel">
      <h2>事件时间线</h2>
      {feed.length === 0 ? (
        <p>还没有事件。点一个按钮试试。</p>
      ) : (
        <ol className="demo-timeline">
          {feed.map((item) => (
            <li key={item.id} data-feed-id={item.id} data-source={item.source}>
              <time dateTime={item.at}>{clock(item.at)}</time>
              <span className="demo-source">
                {SOURCE_LABELS[item.source] ?? item.source}
              </span>
              <span>{item.text}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

type Notice = { kind: "info" | "error"; text: string } | null;

export function ActionsPanel({
  running,
  onRun,
  notice,
}: {
  running: string | null;
  onRun: (action: DemoAction) => void;
  notice: Notice;
}) {
  return (
    <section aria-label="演示动作" className="demo-panel">
      <h2>让系统出点状况</h2>
      <ul className="demo-actions">
        {ACTIONS.map((action) => (
          <li key={action.name}>
            <button
              type="button"
              data-action={action.name}
              disabled={running !== null}
              onClick={() => onRun(action)}
            >
              {running === action.name ? "进行中…" : action.title}
            </button>
            <small>{action.proves}</small>
          </li>
        ))}
      </ul>
      {notice && (
        <p
          role={notice.kind === "error" ? "alert" : "status"}
          data-notice={notice.kind}
        >
          {notice.text}
        </p>
      )}
    </section>
  );
}

interface ArchiveReply {
  lastReset: string | null;
  archive: {
    file: string;
    summary: {
      day?: string;
      counters?: Counters;
      invariants?: { ok?: boolean };
    };
  }[];
}

export function ArchivePanel({ data }: { data: ArchiveReply | null }) {
  return (
    <section aria-label="重置与存档" className="demo-panel">
      <h2>每日重置与 7 天存档</h2>
      <p>
        账本只追加，所以每天由外部脚本把演示库整个重建一遍；重建前会把当天的摘要存档。
      </p>
      <p data-last-reset>
        上次重置：
        {data?.lastReset ? (
          <time dateTime={data.lastReset}>{data.lastReset}</time>
        ) : (
          "暂无记录"
        )}
      </p>
      {data && data.archive.length > 0 ? (
        <ul className="demo-archive">
          {data.archive.map((item) => (
            <li key={item.file}>
              <b>{item.summary.day ?? item.file.replace(/\.json$/, "")}</b>
              <span>
                支付 {item.summary.counters?.payments ?? "—"} · 分录{" "}
                {item.summary.counters?.entries ?? "—"} · 不变量
                {item.summary.invariants?.ok === undefined
                  ? "未知"
                  : item.summary.invariants.ok
                    ? "全部通过"
                    : "有失败"}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p>暂无存档。</p>
      )}
    </section>
  );
}

export type ReportState = "loading" | "none" | SandboxReport;

export function SandboxPanel({ report }: { report: ReportState }) {
  return (
    <section aria-label="真实沙箱报告" className="demo-panel">
      <h2>真实 Stripe 沙箱运行报告</h2>
      <p>Stripe 测试模式、由本地运行产生；下面的数字只读取报告文件。</p>
      {report === "loading" && <p role="status">加载中…</p>}
      {report === "none" && <p data-report="none">尚无运行报告</p>}
      {typeof report === "object" && (
        <div data-report="present">
          <p>
            运行编号：{report.runId ?? "—"} · 运行时间：
            {report.generatedAt ? (
              <time dateTime={report.generatedAt}>{report.generatedAt}</time>
            ) : (
              "—"
            )}
          </p>
          <dl className="demo-counters">
            {report.facts.map((fact) => (
              <div key={fact.name} className="demo-counter">
                <dt>{fact.name}</dt>
                <dd>{fact.value}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}
    </section>
  );
}

function useFetched<T>(path: string, read: (body: unknown) => T | null) {
  const [value, setValue] = useState<T | null | undefined>(undefined);
  useEffect(() => {
    const controller = new AbortController();
    fetch(path, { signal: controller.signal })
      .then((response) => {
        const json = response.headers.get("content-type")?.includes("json");
        return response.ok && json ? response.json() : null;
      })
      .then((body) => setValue(body === null ? null : read(body)))
      .catch(() => {
        if (!controller.signal.aborted) setValue(null);
      });
    return () => controller.abort();
  }, [path, read]);
  return value;
}

const readArchive = (body: unknown) => body as ArchiveReply;

function failureText(
  status: number,
  body: Record<string, unknown>,
  wait: string | null,
) {
  if (status === 409) return "上一个动作还在进行，请等它结束再点。";
  if (status === 429)
    return `操作太频繁了${wait ? `，请 ${wait} 秒后再试` : "，请稍后再试"}。`;
  if (status === 502 && typeof body.message === "string")
    return `动作没有走完：${body.message}。下面的不变量面板显示了此刻账本的真实状态。`;
  return `动作失败（HTTP ${status}），请稍后再试。`;
}

export function DemoPage(): ReactNode {
  const { state, connected, setState } = useDemoStream();
  const [running, setRunning] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const archive = useFetched("/api/demo/archive", readArchive);
  const report = useFetched("/sandbox-report.json", parseSandboxReport);

  useEffect(() => {
    if (visited) return;
    visited = true;
    fetch("/api/demo/visit", { method: "POST" }).catch(() => undefined);
  }, []);

  async function run(action: DemoAction) {
    setRunning(action.name);
    setNotice(null);
    try {
      const response = await fetch(`/api/demo/actions/${action.name}`, {
        method: "POST",
      });
      const body = (await response.json().catch(() => ({}))) as Record<
        string,
        unknown
      >;
      if (body.invariants)
        setState((now) => ({ ...now, verdict: body.invariants as Verdict }));
      setNotice(
        response.ok
          ? { kind: "info", text: `「${action.title}」已完成。` }
          : {
              kind: "error",
              text: failureText(
                response.status,
                body,
                response.headers.get("Retry-After"),
              ),
            },
      );
    } catch {
      setNotice({ kind: "error", text: "网络出错了，请刷新页面重试。" });
    } finally {
      setRunning(null);
    }
  }

  return (
    <>
      <h1>破坏它试试</h1>
      <p>
        这是一个模拟的支付平台：处理方是本地的 fake
        Stripe，银行是模拟银行，数据全部为合成数据，不涉及真实资金。点下面的按钮让系统遇到重复、乱序、崩溃或错误数据，再看账本的
        12 条资金不变量是否仍然成立。
      </p>
      <p role="status" data-stream={connected ? "open" : "closed"}>
        {connected ? "实时连接正常" : "正在连接实时事件…"}
      </p>
      <ActionsPanel running={running} onRun={run} notice={notice} />
      <InvariantPanel verdict={state.verdict} />
      <CounterCards counters={state.counters} />
      <TimelineList feed={state.feed} />
      <ArchivePanel data={archive ?? null} />
      <SandboxPanel
        report={report === undefined ? "loading" : (report ?? "none")}
      />
    </>
  );
}
