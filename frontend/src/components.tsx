import { type ReactNode, useEffect, useState } from "react";
import type { AsOf, Delivery, PaymentDetail } from "./api";
import { formatCad } from "./money";

export const enc = encodeURIComponent;
export const href = (kind: string, id: string) => `#/${kind}/${enc(id)}`;
export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, { signal });
  if (!response.ok)
    throw new Error(
      response.status === 404
        ? path.startsWith("/api/payments/")
          ? "找不到这笔支付"
          : "找不到这条记录"
        : `接口错误（HTTP ${response.status}），请刷新重试`,
    );
  return response.json();
}
export function Resource<T>({
  path,
  children,
  loader = get<T>,
}: {
  path: string;
  children: (data: T) => ReactNode;
  loader?: (path: string, signal?: AbortSignal) => Promise<T>;
}) {
  const [state, setState] = useState<{ data?: T; error?: string }>({});
  useEffect(() => {
    const controller = new AbortController();
    setState({});
    loader(path, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setState({ data });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted)
          setState({
            error:
              error instanceof Error ? error.message : "接口错误，请刷新重试",
          });
      });
    return () => controller.abort();
  }, [path, loader]);
  if (state.error) return <p role="alert">{state.error}</p>;
  if (!state.data) return <p role="status">加载中…</p>;
  return children(state.data);
}
export function Snapshot({ asOf }: { asOf: AsOf }) {
  return (
    <footer>
      asOf · 截至账本序号 {asOf.seq} · <time dateTime={asOf.at}>{asOf.at}</time>
    </footer>
  );
}
export function Field({
  name,
  children,
}: {
  name: string;
  children: ReactNode;
}) {
  return (
    <div>
      <dt>{name}</dt>
      <dd>{children ?? "—"}</dd>
    </div>
  );
}
export function Money({ amount }: { amount: number | null }) {
  return (
    <span className="money">
      {amount === null ? "待确认" : formatCad(amount)}
    </span>
  );
}
export function Table({
  caption,
  columns,
  children,
  empty = false,
}: {
  caption: string;
  columns: string[];
  children: ReactNode;
  empty?: boolean;
}) {
  return (
    <div className="table-scroll">
      <table>
        <caption>{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column}>{column}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {empty ? (
            <tr>
              <td colSpan={columns.length}>暂无记录</td>
            </tr>
          ) : (
            children
          )}
        </tbody>
      </table>
    </div>
  );
}
export function Postings({
  postings,
}: {
  postings: PaymentDetail["postings"];
}) {
  return (
    <Table
      caption={`账本 posting（${postings.length} 条）`}
      columns={["分录类型 / 编号", "账户", "方向", "金额"]}
      empty={!postings.length}
    >
      {postings.map((p) => (
        <tr key={`${p.entryId}:${p.lineNo}`}>
          <td>
            {p.entryType}
            <small>
              {p.entryId} · 行 {p.lineNo}
            </small>
          </td>
          <td>
            {p.accountCode} · {p.accountName}
          </td>
          <td>{p.side === "D" ? "借（D）" : "贷（C）"}</td>
          <td>
            <Money amount={p.amount} />
          </td>
        </tr>
      ))}
    </Table>
  );
}
export function Deliveries({ deliveries }: { deliveries: Delivery[] }) {
  return (
    <Table
      caption={`投递记录（${deliveries.length} 条）`}
      columns={["事件号", "事件类型", "投递结果", "事件状态", "事件结果"]}
      empty={!deliveries.length}
    >
      {deliveries.map((d) => (
        <tr key={d.id}>
          <td>
            <a href={href("events", d.eventId)}>{d.eventId}</a>
          </td>
          <td>{d.eventType}</td>
          <td>{d.outcome === "DUPLICATE" ? "重复投递" : d.outcome}</td>
          <td>{d.status}</td>
          <td>
            {d.result === "DUPLICATE_FACT"
              ? "重复（DUPLICATE_FACT）"
              : (d.result ?? "—")}
          </td>
        </tr>
      ))}
    </Table>
  );
}
export function JsonEvidence({ value }: { value: unknown }) {
  return <pre>{JSON.stringify(value, null, 2)}</pre>;
}
