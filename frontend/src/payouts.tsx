import { useRef, useState } from "react";
import type { Overview, PayoutDetail, PayoutList } from "./api";
import {
  enc,
  Field,
  href,
  MerchantBalance,
  Money,
  Postings,
  Resource,
  Snapshot,
  Table,
} from "./components";
import { issueCategoryLabel } from "./issue-categories";
import { parseCad } from "./money";
import { AmountInput } from "./refunds";

export function PayoutBalances({
  inTransit,
  freeCash,
}: {
  inTransit: number;
  freeCash: number;
}) {
  return (
    <>
      <Field name="提现在途">
        <Money amount={inTransit} />
      </Field>
      <Field name="可支配现金（free_cash）">
        <Money amount={freeCash} />
        {freeCash === 0 && (
          <small>暂无可用于新提现的现金；已预留的提现资金继续保留。</small>
        )}
      </Field>
    </>
  );
}

export function payoutError(
  status: number,
  code: string,
  reason?: string,
): string {
  const reasons: Record<string, string> = {
    MERCHANT_BALANCE: "商户可用余额不足",
    FREE_CASH: "可支配现金不足",
    PAYOUT_REF_CONFLICT: "业务编号已用于不同金额，请核对原提现",
    IDEMPOTENCY_REQUEST_IN_PROGRESS: "请求仍在处理中，请用原键重试",
    IDEMPOTENCY_KEY_REUSED: "幂等键已用于不同请求",
    INVALID_MERCHANT_PAYOUT_REF: "业务编号须为 1～64 位可见 ASCII 字符",
    INVALID_AMOUNT: "金额须在 CAD 0.01 至 CAD 999999.99 之间",
  };
  return `${status} ${code}${reason ? ` (${reason})` : ""}：${reasons[reason ?? code] ?? "请求失败，请核对后重试"}`;
}

function PayoutForm({ onChanged }: { onChanged: () => void }) {
  const [amount, setAmount] = useState("20.00");
  const [ref, setRef] = useState("");
  const [pending, setPending] = useState<{
    key: string;
    body: { amount: number; merchantPayoutRef: string };
  } | null>(null);
  const [busy, setBusy] = useState(false);
  const active = useRef(false);
  const [error, setError] = useState("");
  const [created, setCreated] = useState<string | null>(null);
  async function submit() {
    if (active.current) return;
    active.current = true;
    setBusy(true);
    setError("");
    setCreated(null);
    try {
      if (!pending && !/^[!-~]{1,64}$/.test(ref))
        throw new Error("业务编号须为 1～64 位可见 ASCII 字符");
      const request = pending ?? {
        key: crypto.randomUUID(),
        body: { amount: parseCad(amount), merchantPayoutRef: ref },
      };
      setPending(request);
      const response = await fetch("/api/payouts", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": request.key,
        },
        body: JSON.stringify(request.body),
      });
      if (!response.ok) {
        const detail = await response.json().catch(() => ({}));
        if (
          response.status < 500 &&
          detail.code !== "IDEMPOTENCY_REQUEST_IN_PROGRESS"
        )
          setPending(null);
        throw new Error(
          payoutError(
            response.status,
            detail.code ?? "REQUEST_FAILED",
            detail.reason,
          ),
        );
      }
      const result: { payoutId: string } = await response.json();
      setCreated(result.payoutId);
      setPending(null);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "请求失败，请用原键重试");
    } finally {
      active.current = false;
      setBusy(false);
    }
  }
  return (
    <form
      aria-label="发起提现"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <h2>发起提现</h2>
      <AmountInput
        label="提现金额（CAD 元）"
        value={amount}
        onChange={setAmount}
        disabled={busy || !!pending}
      />
      <label>
        业务编号
        <input
          aria-label="业务编号"
          value={ref}
          maxLength={64}
          disabled={busy || !!pending}
          onChange={(e) => setRef(e.target.value)}
        />
      </label>
      <p>业务编号永久标识同一笔提现。响应丢失时请用原键重试。</p>
      <button type="submit" disabled={busy}>
        {busy ? "提交中…" : pending ? "用原键重试提现" : "提交提现"}
      </button>
      {error && <p role="alert">{error}</p>}
      {created && (
        <p role="status">
          已受理：<a href={href("payouts", created)}>{created}</a>
        </p>
      )}
    </form>
  );
}

export function PayoutsPage() {
  const [offset, setOffset] = useState(0);
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((n) => n + 1);
  return (
    <>
      <h1>提现</h1>
      <button type="button" onClick={refresh}>
        刷新提现
      </button>
      <Resource<Overview> path="/api/overview" revision={revision}>
        {(d) => (
          <dl className="summary balances">
            <MerchantBalance available={d.merchantAvailable} />
            <PayoutBalances
              inTransit={d.payoutInTransit}
              freeCash={d.freeCash}
            />
          </dl>
        )}
      </Resource>
      {import.meta.env.VITE_SIMULATOR_MODE === "true" ? (
        <PayoutForm
          onChanged={() => {
            setOffset(0);
            refresh();
          }}
        />
      ) : (
        <p>当前为只读模式；模拟器模式可发起提现。</p>
      )}
      <Resource<PayoutList>
        path={`/api/payouts?limit=20&offset=${offset}`}
        revision={revision}
      >
        {(d) => (
          <>
            <Table
              caption="提现列表"
              columns={[
                "提现编号 / 业务编号",
                "金额",
                "状态",
                "银行请求号",
                "预留时间",
              ]}
              empty={!d.payouts.length}
            >
              {d.payouts.map((p) => (
                <tr key={p.payoutId}>
                  <td>
                    <a href={href("payouts", p.payoutId)}>{p.payoutId}</a>
                    <small>{p.merchantPayoutRef}</small>
                  </td>
                  <td>
                    <Money amount={p.amount} />
                  </td>
                  <td>{p.status}</td>
                  <td>{p.bankRequestId}</td>
                  <td>{p.reservedAt}</td>
                </tr>
              ))}
            </Table>
            <nav aria-label="提现分页">
              <button
                type="button"
                disabled={offset === 0}
                onClick={() => setOffset(Math.max(0, offset - 20))}
              >
                上一页
              </button>
              <span>第 {offset / 20 + 1} 页</span>
              <button
                type="button"
                disabled={d.payouts.length < 20}
                onClick={() => setOffset(offset + 20)}
              >
                下一页
              </button>
            </nav>
            <Snapshot asOf={d.asOf} />
          </>
        )}
      </Resource>
    </>
  );
}

export function PayoutContent({ data: d }: { data: PayoutDetail }) {
  const p = d.payout;
  return (
    <>
      <dl className="summary">
        <Field name="提现编号">{p.payoutId}</Field>
        <Field name="业务编号">{p.merchantPayoutRef}</Field>
        <Field name="状态">{p.status}</Field>
        <Field name="金额">
          <Money amount={p.amount} />
        </Field>
        <Field name="银行请求号">{p.bankRequestId}</Field>
        <Field name="银行确认号">{p.bankAckRef}</Field>
        <Field name="银行执行日">{p.bankExecutionDate}</Field>
        <Field name="失败原因">{p.failureReason}</Field>
        <Field name="退回原因">{p.returnReason}</Field>
      </dl>
      {p.status === "UNKNOWN" && (
        <p role="status">
          银行结果未知，资金仍在提现在途；恢复时沿用同一银行请求号。
        </p>
      )}
      <Table
        caption="提现状态时间线"
        columns={["原状态", "新状态", "证据", "发生时间", "记录时间"]}
        empty={!d.history.length}
      >
        {d.history.map((h) => (
          <tr key={h.id}>
            <td>{h.fromStatus ?? "—"}</td>
            <td>{h.toStatus}</td>
            <td>
              {h.evidenceType} · {h.evidenceId}
            </td>
            <td>{h.occurredAt}</td>
            <td>{h.recordedAt}</td>
          </tr>
        ))}
      </Table>
      <Table
        caption="提现出站命令"
        columns={["命令", "类型", "状态", "尝试数", "下次尝试", "最近结果"]}
        empty={!d.commands.length}
      >
        {d.commands.map((c) => (
          <tr key={c.commandId}>
            <td>{c.commandId}</td>
            <td>{c.kind}</td>
            <td>{c.status}</td>
            <td>{c.attempts}</td>
            <td>{c.nextAttemptAt}</td>
            <td>{c.lastOutcome ?? "—"}</td>
          </tr>
        ))}
      </Table>
      <Table
        caption="提现尝试列表"
        columns={[
          "命令",
          "次数",
          "发送时间",
          "完成时间",
          "HTTP 状态",
          "结果码",
        ]}
        empty={!d.attempts.length}
      >
        {d.attempts.map((a) => (
          <tr key={`${a.commandId}:${a.attemptNo}`}>
            <td>{a.commandId}</td>
            <td>{a.attemptNo}</td>
            <td>{a.sentAt}</td>
            <td>{a.finishedAt}</td>
            <td>{a.responseStatus ?? "—"}</td>
            <td>{a.resultCode}</td>
          </tr>
        ))}
      </Table>
      <Table
        caption="出账 / 退回流水"
        columns={["流水号", "版本", "方向", "金额", "入账日", "匹配结果"]}
        empty={!d.bankLines.length}
      >
        {d.bankLines.map((b) => (
          <tr key={`${b.bankTxnId}:${b.version}`}>
            <td>
              <a href={href("bank-lines", b.bankTxnId)}>{b.bankTxnId}</a>
            </td>
            <td>{b.version}</td>
            <td>{b.direction === "DEBIT" ? "出账" : "退回 / 入账"}</td>
            <td>
              <Money amount={b.amount} />
            </td>
            <td>{b.bookingDate}</td>
            <td>{b.matchStatus}</td>
          </tr>
        ))}
      </Table>
      <Postings
        postings={d.postings.map((p) => ({
          ...p,
          entryType:
            d.entries.find((e) => e.id === p.entryId)?.entryType ?? "—",
          accountName: "",
        }))}
      />
      {d.entries
        .filter((e) => e.correctionId)
        .map((e) => (
          <p key={e.id}>
            关联纠错：
            <a href={href("corrections", e.correctionId as string)}>
              {e.correctionId}
            </a>
          </p>
        ))}
      <Table
        caption="提现差异"
        columns={["差异", "类别", "状态"]}
        empty={!d.issues.length}
      >
        {d.issues.map((i) => (
          <tr key={i.id}>
            <td>
              <a href={href("recon", i.id)}>{i.id}</a>
            </td>
            <td>{issueCategoryLabel(i.category)}</td>
            <td>{i.status}</td>
          </tr>
        ))}
      </Table>
      <Snapshot asOf={d.asOf} />
    </>
  );
}

export function PayoutPage({ id }: { id: string }) {
  const [revision, setRevision] = useState(0);
  return (
    <>
      <h1>提现详情</h1>
      <p>
        <a href="#/payouts">返回提现列表</a>
      </p>
      <button type="button" onClick={() => setRevision((n) => n + 1)}>
        刷新提现
      </button>
      <Resource<PayoutDetail>
        path={`/api/payouts/${enc(id)}`}
        revision={revision}
      >
        {(data) => <PayoutContent data={data} />}
      </Resource>
    </>
  );
}
