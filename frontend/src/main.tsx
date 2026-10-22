import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import type { AsOf, PaymentDetail, PaymentList } from "./api";
import { formatCad } from "./money";
import "./style.css";

type QueryState<T> =
  | { kind: "loading" }
  | { kind: "error"; message: string }
  | { kind: "ready"; data: T };

function useQuery<T>(path: string): QueryState<T> {
  const [state, setState] = useState<QueryState<T>>({ kind: "loading" });
  useEffect(() => {
    const controller = new AbortController();
    setState({ kind: "loading" });
    fetch(path, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok)
          throw new Error(
            response.status === 404
              ? "找不到这笔支付"
              : `接口错误（HTTP ${response.status}），请刷新重试`,
          );
        const data: T = await response.json();
        setState({ kind: "ready", data });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted)
          setState({
            kind: "error",
            message:
              error instanceof Error ? error.message : "接口错误，请刷新重试",
          });
      });
    return () => controller.abort();
  }, [path]);
  return state;
}

function Snapshot({ asOf }: { asOf: AsOf }) {
  return (
    <footer>
      asOf · 截至账本序号 {asOf.seq} · <time dateTime={asOf.at}>{asOf.at}</time>
    </footer>
  );
}

function RecentPayments() {
  const state = useQuery<PaymentList>("/api/payments");
  if (state.kind === "loading") return <p role="status">加载中…</p>;
  if (state.kind === "error") return <p role="alert">{state.message}</p>;
  return (
    <>
      <h1>最近支付</h1>
      {state.data.payments.length === 0 ? (
        <p>暂无支付</p>
      ) : (
        <div className="table-scroll">
          <table>
            <caption>最近支付列表</caption>
            <thead>
              <tr>
                <th>处理方编号</th>
                <th>状态</th>
                <th className="money">金额</th>
              </tr>
            </thead>
            <tbody>
              {state.data.payments.map((payment) => (
                <tr key={payment.id}>
                  <td>
                    <a
                      href={`/payments/${encodeURIComponent(payment.processorPaymentRef)}`}
                    >
                      {payment.processorPaymentRef}
                    </a>
                  </td>
                  <td>{payment.status}</td>
                  <td className="money">{formatCad(payment.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Snapshot asOf={state.data.asOf} />
    </>
  );
}

function Detail({ reference }: { reference: string }) {
  const state = useQuery<PaymentDetail>(`/api/payments/${reference}`);
  if (state.kind === "loading") return <p role="status">加载中…</p>;
  if (state.kind === "error") return <p role="alert">{state.message}</p>;
  const payment = state.data;
  return (
    <>
      <h1>交易详情</h1>
      <dl className="summary">
        <div>
          <dt>金额</dt>
          <dd className="money primary-amount">{formatCad(payment.amount)}</dd>
        </div>
        <div>
          <dt>状态</dt>
          <dd>{payment.status}</dd>
        </div>
        <div>
          <dt>处理方编号</dt>
          <dd>{payment.processorPaymentRef}</dd>
        </div>
        <div>
          <dt>本地编号</dt>
          <dd>{payment.id}</dd>
        </div>
        <div>
          <dt>预期手续费</dt>
          <dd className="money">{formatCad(payment.expectedFee)}</dd>
        </div>
      </dl>
      <div className="table-scroll">
        <table>
          <caption>账本 posting（{payment.postings.length} 条）</caption>
          <thead>
            <tr>
              <th>分录类型</th>
              <th>账户</th>
              <th>方向</th>
              <th className="money">金额</th>
            </tr>
          </thead>
          <tbody>
            {payment.postings.map((posting) => (
              <tr key={`${posting.entryId}:${posting.lineNo}`}>
                <td>{posting.entryType}</td>
                <td>
                  {posting.accountCode} · {posting.accountName}
                </td>
                <td>{posting.side === "D" ? "借（D）" : "贷（C）"}</td>
                <td className="money">{formatCad(posting.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="table-scroll">
        <table>
          <caption>投递记录（{payment.deliveries.length} 条）</caption>
          <thead>
            <tr>
              <th>事件号</th>
              <th>事件类型</th>
              <th>投递结果</th>
              <th>事件状态</th>
              <th>事件结果</th>
            </tr>
          </thead>
          <tbody>
            {payment.deliveries.map((delivery, index) => (
              // biome-ignore lint/suspicious/noArrayIndexKey: Immutable snapshot rows have no delivery ID; event IDs and timestamps can repeat.
              <tr key={`${delivery.eventId}:${delivery.receivedAt}:${index}`}>
                <td>{delivery.eventId}</td>
                <td>{delivery.eventType}</td>
                <td>
                  {delivery.outcome === "DUPLICATE"
                    ? "重复投递"
                    : delivery.outcome}
                </td>
                <td>{delivery.status}</td>
                <td>{delivery.result ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Snapshot asOf={payment.asOf} />
    </>
  );
}

function App() {
  const path = window.location.pathname;
  const match = /^\/payments\/([^/]+)$/.exec(path);
  return (
    <main>
      <header>
        <a href="/">LedgerLab</a>
        <span>测试数据 · 支付查询</span>
      </header>
      {match ? (
        <Detail reference={match[1]} />
      ) : path === "/" ? (
        <RecentPayments />
      ) : (
        <p role="alert">找不到这个页面</p>
      )}
    </main>
  );
}

const root = document.getElementById("root");
if (root)
  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
