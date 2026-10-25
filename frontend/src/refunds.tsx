import { useId, useRef, useState } from "react";
import type {
  PaymentDetail,
  Refund,
  RefundDetail,
  RefundRequest,
  RefundResponse,
} from "./api";
import {
  enc,
  Field,
  get,
  href,
  JsonEvidence,
  Money,
  Postings,
  Resource,
  Snapshot,
  Table,
} from "./components";
import { parseCad } from "./money";

const labels: Record<string, string> = {
  AWAITING_FUNDS: "等待资金",
  READY: "待提交",
  SUBMITTED: "处理方已受理",
  UNKNOWN: "结果未知",
  SUCCEEDED: "已成功",
  FAILED: "已失败",
  CANCELLED: "已取消",
};
const errors: Record<string, string> = {
  REFUND_EXCEEDS_REMAINING: "超过可退余额",
  REFUND_NOT_CANCELLABLE: "仅等待资金的退款可以取消",
  IDEMPOTENCY_REQUEST_IN_PROGRESS: "同一请求仍在处理中，请用原键重试",
  INVALID_AMOUNT: "金额无效",
  PAYMENT_NOT_REFUNDABLE: "支付当前不可退款",
};
function message(error: unknown) {
  return error instanceof Error ? error.message : "请求失败，请重试";
}
class RefundHttpError extends Error {
  constructor(
    public status: number,
    public code: string,
  ) {
    super(`${status} ${code}：${errors[code] ?? "请求失败，请核对后重试"}`);
  }
}
export function RefundBadge({ refund }: { refund: Refund }) {
  return (
    <span className="refund-badge" data-status={refund.status}>
      {labels[refund.status] ?? refund.status}
      {refund.status === "AWAITING_FUNDS" &&
        ` · 排队第 ${refund.queue_position} 位`}
    </span>
  );
}
export function AmountInput({
  label,
  value,
  onChange,
  disabled = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  const errorId = useId();
  let error: string | undefined;
  try {
    parseCad(value);
  } catch (e) {
    error = message(e);
  }
  return (
    <label>
      {label}
      <input
        type="text"
        aria-label={label}
        aria-describedby={error ? errorId : undefined}
        inputMode="decimal"
        value={value}
        disabled={disabled}
        aria-invalid={!!error}
        onChange={(e) => onChange(e.target.value)}
      />
      {error && (
        <small id={errorId} role="alert">
          {error}
        </small>
      )}
    </label>
  );
}
function newRequest(payment: string, amount: number): RefundRequest {
  return {
    key: crypto.randomUUID(),
    body: {
      payment_ref: payment,
      merchant_refund_ref: `web-${crypto.randomUUID()}`,
      amount,
    },
  };
}
async function post(path: string, key: string, body: unknown) {
  const response = await fetch(path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": key,
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({}));
    const code =
      typeof detail.code === "string" ? detail.code : "REQUEST_FAILED";
    throw new RefundHttpError(response.status, code);
  }
  const result: RefundResponse = await response.json();
  return { status: response.status, result };
}
function submit(request: RefundRequest, path = "/api/refunds") {
  return post(path, request.key, request.body);
}

export function RefundSection({
  payment,
  onChanged,
}: {
  payment: PaymentDetail;
  onChanged: () => void;
}) {
  const [amount, setAmount] = useState("20.00");
  const [pending, setPending] = useState<RefundRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const active = useRef(false);
  const [logs, setLogs] = useState<string[]>([]);
  async function accept() {
    if (active.current) return;
    active.current = true;
    setBusy(true);
    try {
      const request =
        pending ?? newRequest(payment.processorPaymentRef, parseCad(amount));
      setPending(request);
      const response = await submit(request);
      setLogs((old) => [
        ...old,
        `${old.length + 1}. ${response.status} · ${response.result.refund_id} · ${labels[response.result.status]}`,
      ]);
      setPending(null);
      onChanged();
    } catch (e) {
      setLogs((old) => [...old, `${old.length + 1}. 提交失败：${message(e)}`]);
      if (
        e instanceof RefundHttpError &&
        e.status < 500 &&
        e.code !== "IDEMPOTENCY_REQUEST_IN_PROGRESS"
      )
        setPending(null);
    } finally {
      active.current = false;
      setBusy(false);
    }
  }
  return (
    <section aria-label="退款">
      <h2>退款</h2>
      <p>
        剩余可退：
        <Money amount={payment.refundable_remaining} />
      </p>
      <Table
        caption={`退款列表（${payment.refunds.length} 条）`}
        columns={[
          "退款编号 / 商户退款编号",
          "金额",
          "状态",
          "受理时间 / 排队号",
          "处理方编号",
          "命令 / 次数",
          "操作",
        ]}
        empty={!payment.refunds.length}
      >
        {payment.refunds.map((r) => (
          <tr key={r.refund_id}>
            <td>
              <a href={href("refunds", r.refund_id)}>{r.refund_id}</a>
              <small>{r.merchant_refund_ref}</small>
            </td>
            <td>
              <Money amount={r.amount} />
            </td>
            <td>
              <RefundBadge refund={r} />
            </td>
            <td>
              {r.accepted_at}
              <small>queue_seq {r.queue_seq}</small>
            </td>
            <td>{r.processor_refund_ref ?? "—"}</td>
            <td>
              {r.command_status ?? "尚未创建命令"} · {r.command_attempts ?? 0}
            </td>
            <td>
              {r.status === "AWAITING_FUNDS" && (
                <CancelRefund id={r.refund_id} onChanged={onChanged} />
              )}
            </td>
          </tr>
        ))}
      </Table>
      <form
        aria-label="发起退款"
        onSubmit={(e) => {
          e.preventDefault();
          void accept();
        }}
      >
        <h3>发起退款</h3>
        <AmountInput
          label="退款金额（CAD 元）"
          value={amount}
          onChange={setAmount}
          disabled={busy || !!pending}
        />
        <button type="submit" disabled={busy}>
          {busy ? "提交中…" : pending ? "用原键重试退款" : "提交退款"}
        </button>
        {pending && (
          <p>
            请求号：{pending.body.merchant_refund_ref}
            。重试沿用本次金额与幂等键。
          </p>
        )}
        <ol aria-label="退款提交日志">
          {logs.map((log) => (
            <li key={log}>{log}</li>
          ))}
        </ol>
      </form>
      <ConcurrentDemo
        payment={payment.processorPaymentRef}
        onChanged={onChanged}
      />
      {import.meta.env.VITE_E2E_REFUNDS === "true" && (
        <RetryDemo
          payment={payment.processorPaymentRef}
          onChanged={onChanged}
        />
      )}
    </section>
  );
}
function CancelRefund({
  id,
  onChanged,
}: {
  id: string;
  onChanged: () => void;
}) {
  const [reason, setReason] = useState("");
  const [pending, setPending] = useState<{
    key: string;
    reason: string;
  } | null>(null);
  const [busy, setBusy] = useState(false);
  const active = useRef(false);
  const [error, setError] = useState("");
  return (
    <form
      aria-label={`取消退款 ${id}`}
      onSubmit={async (e) => {
        e.preventDefault();
        if (active.current) return;
        if (!reason.trim()) {
          setError("取消必须填写原因");
          return;
        }
        active.current = true;
        setBusy(true);
        setError("");
        const request = pending ?? {
          key: crypto.randomUUID(),
          reason: reason.trim(),
        };
        setPending(request);
        try {
          await post(`/api/refunds/${enc(id)}/cancel`, request.key, {
            reason: request.reason,
          });
          onChanged();
        } catch (e) {
          setError(message(e));
        } finally {
          active.current = false;
          setBusy(false);
        }
      }}
    >
      <label>
        取消原因
        <input
          value={reason}
          disabled={!!pending}
          onChange={(e) => setReason(e.target.value)}
        />
      </label>
      <button type="submit" disabled={busy}>
        取消（需填写原因）
      </button>
      {error && <p role="alert">{error}</p>}
    </form>
  );
}
function ConcurrentDemo({
  payment,
  onChanged,
}: {
  payment: string;
  onChanged: () => void;
}) {
  const [amount, setAmount] = useState("60.00");
  const [logs, setLogs] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const active = useRef(false);
  return (
    <section aria-label="并发演示">
      <h3>并发退款演示</h3>
      <p>同时提交两笔相同金额的退款，每笔使用独立的业务编号和幂等键。</p>
      <AmountInput
        label="每笔并发退款金额（CAD 元）"
        value={amount}
        onChange={setAmount}
        disabled={busy}
      />
      <button
        type="button"
        disabled={busy}
        onClick={async () => {
          if (active.current) return;
          active.current = true;
          setBusy(true);
          setLogs([]);
          try {
            const cents = parseCad(amount);
            const requests = [
              newRequest(payment, cents),
              newRequest(payment, cents),
            ];
            await Promise.all(
              requests.map(async (request, i) => {
                let log: string;
                try {
                  const r = await submit(request);
                  log = `${r.status} · ${r.result.refund_id} · ${labels[r.result.status]}`;
                } catch (e) {
                  log = message(e);
                }
                setLogs((old) => [
                  ...old,
                  `请求 ${i + 1} · ${request.key} · ${log}`,
                ]);
              }),
            );
            onChanged();
          } catch (e) {
            setLogs([message(e)]);
          } finally {
            active.current = false;
            setBusy(false);
          }
        }}
      >
        同时发出两笔退款
      </button>
      <ol aria-label="并发请求日志">
        {logs.map((log) => (
          <li key={log}>{log}</li>
        ))}
      </ol>
    </section>
  );
}
function RetryDemo({
  payment,
  onChanged,
}: {
  payment: string;
  onChanged: () => void;
}) {
  const [logs, setLogs] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const active = useRef(false);
  return (
    <section aria-label="故障重试演示">
      <h3>故障重试演示</h3>
      <p>退款 CAD 20.00，提交后模拟响应丢失，再用原键重试。</p>
      <button
        type="button"
        disabled={busy}
        onClick={async () => {
          if (active.current) return;
          active.current = true;
          setBusy(true);
          setLogs([]);
          const request = newRequest(payment, 2000);
          try {
            let lost = false;
            try {
              await submit(request, "/__e2e__/refund-response-loss");
            } catch (e) {
              lost = true;
              setLogs([`第一次失败：${message(e)} · ${request.key}`]);
            }
            if (!lost) throw new Error("本次未发生响应丢失，演示未完成");
            const observed = await get<PaymentDetail>(
              `/api/payments/${enc(payment)}`,
            );
            const original = observed.refunds.find(
              (r) => r.merchant_refund_ref === request.body.merchant_refund_ref,
            );
            if (!original)
              throw new Error("未查到第一次提交的退款，演示未完成");
            const second = await submit(request);
            if (second.result.refund_id !== original.refund_id)
              throw new Error("重试返回的退款号不一致");
            setLogs((old) => [
              ...old,
              `第二次返回原结果（同一退款号）：${second.result.refund_id} · HTTP ${second.status} · ${request.key}`,
            ]);
          } catch (e) {
            setLogs((old) => [...old, message(e)]);
          } finally {
            onChanged();
            active.current = false;
            setBusy(false);
          }
        }}
      >
        模拟响应丢失后重试
      </button>
      <ol aria-label="故障重试日志">
        {logs.map((log) => (
          <li key={log}>{log}</li>
        ))}
      </ol>
    </section>
  );
}

export function RefundPage({ id }: { id: string }) {
  return (
    <Resource<RefundDetail> path={`/api/refunds/${enc(id)}`}>
      {(d) => (
        <>
          <h1>退款详情</h1>
          <dl className="summary">
            <Field name="退款编号">{d.refund.refund_id}</Field>
            <Field name="商户退款编号">{d.refund.merchant_refund_ref}</Field>
            <Field name="支付">
              <a href={href("payments", d.refund.payment_ref)}>
                {d.refund.payment_ref}
              </a>
            </Field>
            <Field name="退款金额">
              <Money amount={d.refund.amount} />
            </Field>
            <Field name="状态">
              <RefundBadge refund={d.refund} />
            </Field>
            <Field name="受理时间">{d.refund.accepted_at}</Field>
            <Field name="排队号">{d.refund.queue_seq}</Field>
            <Field name="处理方退款号">{d.refund.processor_refund_ref}</Field>
            <Field name="取消 / 失败原因">
              {d.refund.cancel_reason ?? d.refund.failure_reason}
            </Field>
          </dl>
          <Table
            caption="退款状态历史"
            columns={["状态变化", "来源", "证据编号", "业务时间", "记录时间"]}
          >
            {d.history.map((h) => (
              <tr key={h.id}>
                <td>
                  {h.from_status ?? "—"} → {h.to_status}
                </td>
                <td>{h.evidence_source}</td>
                <td>
                  {h.evidence_source === "WEBHOOK" ? (
                    <a href={href("events", h.evidence_ref)}>
                      {h.evidence_ref}
                    </a>
                  ) : (
                    h.evidence_ref
                  )}
                </td>
                <td>{h.occurred_at}</td>
                <td>{h.recorded_at}</td>
              </tr>
            ))}
          </Table>
          <Table
            caption="出站命令"
            columns={[
              "编号",
              "状态",
              "尝试次数",
              "首次发送",
              "下次尝试",
              "结果码",
            ]}
            empty={!d.commands.length}
          >
            {d.commands.map((c) => (
              <tr key={c.command_id}>
                <td>{c.command_id}</td>
                <td>{c.status}</td>
                <td>{c.attempts}</td>
                <td>{c.first_sent_at ?? "尚未发送"}</td>
                <td>{c.next_attempt_at}</td>
                <td>{c.last_outcome ?? "—"}</td>
              </tr>
            ))}
          </Table>
          <Table
            caption="每次尝试摘要"
            columns={["命令 / 次数", "开始", "结束", "HTTP 码", "结果码"]}
            empty={!d.attempts.length}
          >
            {d.attempts.map((a) => (
              <tr key={`${a.command_id}:${a.attempt_no}`}>
                <td>
                  {a.command_id} · {a.attempt_no}
                </td>
                <td>{a.sent_at}</td>
                <td>{a.finished_at}</td>
                <td>{a.response_status ?? "无响应"}</td>
                <td>{a.result_code}</td>
              </tr>
            ))}
          </Table>
          <Postings postings={d.postings} />
          <details>
            <summary>相关分录（{d.entries.length} 条）</summary>
            <JsonEvidence value={d.entries} />
          </details>
          <Snapshot asOf={d.asOf} />
        </>
      )}
    </Resource>
  );
}
