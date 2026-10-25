import { useState } from "react";
import type {
  BankDetail,
  BatchDetail,
  BatchList,
  EntryDetail,
  EventDetail,
  IssueDetail,
  IssueList,
  Overview,
  PaymentDetail,
  PaymentList,
  StatementDetail,
} from "./api";
import {
  Deliveries,
  enc,
  Field,
  get,
  href,
  JsonEvidence,
  MerchantBalance,
  Money,
  Postings,
  Resource,
  Snapshot,
  Table,
} from "./components";
import { PaymentCorrectionHistory, ReversedNotice } from "./corrections";
import { issueCategoryLabel, issueCategoryLabels } from "./issue-categories";
import { RefundSection } from "./refunds";

export function OverviewPage() {
  return (
    <>
      <h1>资金总览</h1>
      <Resource<Overview> path="/api/overview">
        {(data) => (
          <>
            <dl className="summary balances">
              <Field name="结算在途">
                <Money amount={data.inTransit} />
              </Field>
              <Field name="银行现金">
                <Money amount={data.bankCash} />
              </Field>
              <MerchantBalance available={data.merchantAvailable} />
              <Field name="退款待付">
                <Money amount={data.refund_payable} />
              </Field>
              <Field name="等待资金退款数">{data.awaiting_funds_count}</Field>
              <Field name="等待资金合计">
                <Money amount={data.awaiting_funds_amount} />
              </Field>
              <Field name="待查收款">
                <Money amount={data.unappliedCash} />
              </Field>
            </dl>
            <section aria-label="OPEN 差异">
              <h2>待处理差异 · OPEN</h2>
              {Object.keys(data.openIssuesByCategory).length ? (
                <ul className="issue-counts">
                  {Object.entries(data.openIssuesByCategory).map(
                    ([category, count]) => (
                      <li key={category}>
                        <a
                          href={`#/recon?status=OPEN&category=${enc(category)}`}
                        >
                          {issueCategoryLabel(category)} · {count}
                        </a>
                      </li>
                    ),
                  )}
                </ul>
              ) : (
                <p>暂无 OPEN 差异</p>
              )}
            </section>
            <Snapshot asOf={data.asOf} />
          </>
        )}
      </Resource>
      <RecentBatches />
      <RecentPayments />
    </>
  );
}
function RecentBatches() {
  return (
    <section>
      <h2>最近批次</h2>
      <Resource<BatchList> path="/api/batches">
        {(data) => (
          <>
            <Table
              caption="最近批次列表（最多 20 条）"
              columns={["批次", "状态", "结算日", "净额"]}
              empty={!data.batches.length}
            >
              {data.batches.map((b) => (
                <tr key={b.batchId}>
                  <td>
                    <a href={href("batches", b.batchId)}>{b.batchId}</a>
                  </td>
                  <td>{b.status}</td>
                  <td>{b.settlementDate ?? "—"}</td>
                  <td>
                    <Money amount={b.net} />
                  </td>
                </tr>
              ))}
            </Table>
            <Snapshot asOf={data.asOf} />
          </>
        )}
      </Resource>
    </section>
  );
}
function RecentPayments() {
  return (
    <section>
      <h2>最近支付</h2>
      <Resource<PaymentList> path="/api/payments">
        {(data) => (
          <>
            <Table
              caption="最近支付列表（最多 20 条）"
              columns={["处理方编号", "状态", "金额"]}
              empty={!data.payments.length}
            >
              {data.payments.map((p) => (
                <tr key={p.id}>
                  <td>
                    <a href={href("payments", p.processorPaymentRef)}>
                      {p.processorPaymentRef}
                    </a>
                  </td>
                  <td>{p.status}</td>
                  <td>
                    <Money amount={p.amount} />
                  </td>
                </tr>
              ))}
            </Table>
            <Snapshot asOf={data.asOf} />
          </>
        )}
      </Resource>
    </section>
  );
}
export function PaymentPage({ id }: { id: string }) {
  const [revision, setRevision] = useState(0);
  return (
    <Resource<PaymentDetail>
      revision={revision}
      path={`/api/payments/${enc(id)}`}
    >
      {(p) => (
        <>
          <h1>交易详情</h1>
          <dl className="summary">
            <Field name="毛额">
              <Money amount={p.amount} />
            </Field>
            <Field name={p.actualFee === null ? "预期手续费" : "手续费"}>
              <Money amount={p.actualFee ?? p.expectedFee} />
            </Field>
            <Field name="净额">
              <Money amount={p.net} />
            </Field>
            <Field name="状态">{p.status}</Field>
            <Field name="处理方编号">{p.processorPaymentRef}</Field>
            <Field name="本地编号">{p.id}</Field>
            <Field name="所属批次">
              {p.batch ? (
                <a href={href("batches", p.batch.batchId)}>{p.batch.batchId}</a>
              ) : (
                "尚未进入批次"
              )}
            </Field>
            <Field name="银行入账">
              {p.bankDeposit ? (
                <>
                  <a href={href("bank-lines", p.bankDeposit.bankTxnId)}>
                    {p.bankDeposit.bankTxnId}
                  </a>
                  <small>
                    {p.bankDeposit.bookingDate} ·{" "}
                    <Money amount={p.bankDeposit.amount} />
                  </small>
                </>
              ) : (
                "尚未入账"
              )}
            </Field>
          </dl>
          <Postings postings={p.postings} />
          <PaymentCorrectionHistory payment={p} />
          <Deliveries deliveries={p.deliveries} />
          <Snapshot asOf={p.asOf} />
          <RefundSection
            payment={p}
            onChanged={() => setRevision((v) => v + 1)}
          />
        </>
      )}
    </Resource>
  );
}
export function BatchPage({ id }: { id: string }) {
  return (
    <Resource<BatchDetail> path={`/api/batches/${enc(id)}`}>
      {(d) => (
        <>
          <h1>批次详情</h1>
          <dl className="summary">
            <Field name="批次编号">{d.batch.batchId}</Field>
            <Field name="状态">{d.batch.status}</Field>
            <Field name="暂挂原因">{d.batch.holdReason}</Field>
            <Field name="毛额">
              <Money amount={d.batch.gross} />
            </Field>
            <Field name="手续费">
              <Money amount={d.batch.fees} />
            </Field>
            <Field name="退款">
              <Money amount={d.batch.refunds} />
            </Field>
            <Field name="失败打款退回">
              <Money amount={d.batch.payoutFailuresReturned} />
            </Field>
            <Field name="净额">
              <Money amount={d.batch.net} />
            </Field>
            <Field name="处理方打款金额">
              <Money amount={d.batch.payoutAmount} />
            </Field>
            <Field name="结算日">{d.batch.settlementDate}</Field>
            <Field name="预计入账日">{d.batch.arrivalDate}</Field>
            <Field name="原事件">
              <a href={href("events", d.batch.sourceEventId)}>
                {d.batch.sourceEventId}
              </a>
            </Field>
          </dl>
          <Table
            caption="批次行"
            columns={["行号", "类型", "处理方编号", "毛额", "手续费", "净额"]}
            empty={!d.lines.length}
          >
            {d.lines.map((l) => (
              <tr key={l.lineNo}>
                <td>
                  {l.lineNo}
                  <small>{l.balanceTxnId}</small>
                </td>
                <td>{l.lineType}</td>
                <td>
                  {l.lineType === "PAYMENT" ? (
                    <a href={href("payments", l.processorRef)}>
                      {l.processorRef}
                    </a>
                  ) : (
                    l.processorRef
                  )}
                </td>
                <td>
                  <Money amount={l.gross} />
                </td>
                <td>
                  <Money amount={l.fee} />
                </td>
                <td>
                  <Money amount={l.net} />
                </td>
              </tr>
            ))}
          </Table>
          <Table
            caption="批次状态历史"
            columns={["状态变化", "证据", "业务时间", "记录时间"]}
            empty={!d.history.length}
          >
            {d.history.map((h) => (
              <tr key={h.id}>
                <td>
                  {h.fromStatus ?? "—"} → {h.toStatus}
                  <small>{h.holdReason}</small>
                </td>
                <td>
                  {h.evidenceType} ·{" "}
                  {h.evidenceType === "correction" ? (
                    <a href={href("corrections", h.evidenceId)}>
                      {h.evidenceId}
                    </a>
                  ) : (
                    h.evidenceId
                  )}
                </td>
                <td>{h.occurredAt}</td>
                <td>{h.recordedAt}</td>
              </tr>
            ))}
          </Table>
          <Table
            caption="分录"
            columns={["编号", "类型", "来源", "posting 数"]}
            empty={!d.entries.length}
          >
            {d.entries.map((e) => (
              <tr key={e.id}>
                <td>
                  <a href={href("entries", e.id)}>{e.id}</a>
                </td>
                <td>{e.entryType}</td>
                <td>{e.sourceKey}</td>
                <td>{e.postingCount}</td>
              </tr>
            ))}
          </Table>
          <Postings postings={d.postings} />
          <Table
            caption="银行流水行"
            columns={["流水编号", "金额", "记账日", "匹配状态"]}
            empty={!d.bankLines.length}
          >
            {d.bankLines.map((b) => (
              <tr key={b.bankTxnId}>
                <td>
                  <a href={href("bank-lines", b.bankTxnId)}>{b.bankTxnId}</a>
                </td>
                <td>
                  <Money amount={b.amount} />
                </td>
                <td>{b.bookingDate}</td>
                <td>{b.matchStatus}</td>
              </tr>
            ))}
          </Table>
          <Deliveries deliveries={d.deliveries} />
          <Snapshot asOf={d.asOf} />
        </>
      )}
    </Resource>
  );
}
export function ReconPage({ search }: { search: string }) {
  const params = new URLSearchParams(search);
  const [status, setStatus] = useState(params.get("status") ?? "OPEN");
  const [category, setCategory] = useState(params.get("category") ?? "");
  const query = new URLSearchParams({ limit: "100" });
  if (params.get("status")) query.set("status", params.get("status") ?? "");
  else if (!params.has("status")) query.set("status", "OPEN");
  if (params.get("category"))
    query.set("category", params.get("category") ?? "");
  return (
    <>
      <h1>对账工作台</h1>
      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault();
          window.location.hash = `/recon?${new URLSearchParams({ status, category: category.trim() })}`;
        }}
      >
        <label>
          状态
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">全部</option>
            {["OPEN", "EXPLAINED", "ACCEPTED", "RESOLVED"].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
        <label>
          类别
          <input
            value={category}
            list="issue-categories"
            placeholder="例如 BATCH_TOTAL_MISMATCH"
            onChange={(e) => setCategory(e.target.value)}
          />
          <datalist id="issue-categories">
            {Object.entries(issueCategoryLabels).map(([value, label]) => (
              <option key={value} value={value} label={label} />
            ))}
          </datalist>
        </label>
        <button type="submit">筛选</button>
      </form>
      <Resource<IssueList> path={`/api/recon/issues?${query}`}>
        {(data) => (
          <>
            <Table
              caption="差异列表（最近 100 条）"
              columns={["类别", "对象", "状态", "发现来源", "更新时间"]}
              empty={!data.issues.length}
            >
              {data.issues.map((i) => (
                <tr key={i.id}>
                  <td>
                    <a href={href("recon", i.id)}>
                      {issueCategoryLabel(i.category)}
                    </a>
                  </td>
                  <td>{i.subjectId}</td>
                  <td>{i.status}</td>
                  <td>{i.detectedBy}</td>
                  <td>{i.updatedAt}</td>
                </tr>
              ))}
            </Table>
            <Snapshot asOf={data.asOf} />
          </>
        )}
      </Resource>
    </>
  );
}
export function IssuePage({ id }: { id: string }) {
  const [revision, setRevision] = useState(0);
  return (
    <Resource<IssueDetail> key={revision} path={`/api/recon/issues/${enc(id)}`}>
      {(d) => (
        <>
          <h1>差异详情</h1>
          <dl className="summary">
            <Field name="类别">{issueCategoryLabel(d.issue.category)}</Field>
            <Field name="对象">{d.issue.subjectId}</Field>
            <Field name="状态">{d.issue.status}</Field>
            <Field name="处置说明">{d.issue.explanation}</Field>
            <Field name="resolution_correction_id">
              {d.issue.resolutionCorrectionId ? (
                <a href={href("corrections", d.issue.resolutionCorrectionId)}>
                  {d.issue.resolutionCorrectionId}
                </a>
              ) : (
                "—"
              )}
            </Field>
          </dl>
          <Table
            caption="金额比较"
            columns={["比较项", "预期金额", "实际金额", "差额"]}
            empty={!d.amounts.length}
          >
            {d.amounts.map((a) => (
              <tr key={`${a.metric}:${a.statementLineId}`}>
                <td>
                  {a.metric}
                  <small>{a.statementLineId}</small>
                </td>
                <td>
                  <Money amount={a.expectedAmount} />
                  <small>{a.expectedAmount} 分</small>
                </td>
                <td>
                  <Money amount={a.actualAmount} />
                  <small>{a.actualAmount} 分</small>
                </td>
                <td>
                  <Money amount={a.difference} />
                  <small>{a.difference} 分</small>
                </td>
              </tr>
            ))}
          </Table>
          <section aria-label="关联证据">
            <h2>关联证据</h2>
            <ul className="evidence-links">
              {d.references.batchIds.map((x) => (
                <li key={x}>
                  <a href={href("batches", x)}>批次 {x}</a>
                </li>
              ))}
              {d.references.statementLines.map((x) => (
                <li key={`${x.fileId}:${x.lineSeq}`}>
                  <a href={`#/statement-lines/${enc(x.fileId)}/${x.lineSeq}`}>
                    批次行 {x.statementLineId} · {x.lineSeq}
                  </a>
                </li>
              ))}
              {d.references.eventIds.map((x) => (
                <li key={x}>
                  <a href={href("events", x)}>原事件 {x}</a>
                </li>
              ))}
              {d.references.entryIds.map((x) => (
                <li key={x}>
                  <a href={`#/recon/${enc(id)}/entries/${enc(x)}`}>
                    posting {x}
                  </a>
                </li>
              ))}
              {d.references.bankTxnIds.map((x) => (
                <li key={x}>
                  <a href={href("bank-lines", x)}>银行流水行 {x}</a>
                </li>
              ))}
              {d.references.paymentIds.map((x) => (
                <li key={x}>
                  <a href={href("payments", x)}>支付 {x}</a>
                </li>
              ))}
              {d.references.refundIds.map((x) => (
                <li key={x}>
                  <a href={href("refunds", x)}>退款 {x}</a>
                </li>
              ))}
            </ul>
            {Object.values(d.references).every((refs) => !refs.length) && (
              <p>暂无可跳转证据</p>
            )}
          </section>
          {d.issue.status === "OPEN" && (
            <AcceptIssue id={id} onAccepted={() => setRevision((v) => v + 1)} />
          )}
          <Table
            caption="差异状态历史"
            columns={["状态变化", "原因", "说明", "时间"]}
            empty={!d.history.length}
          >
            {d.history.map((h) => (
              <tr key={h.id}>
                <td>
                  {h.fromStatus ?? "—"} → {h.toStatus}
                </td>
                <td>{h.reason}</td>
                <td>{h.explanation}</td>
                <td>{h.recordedAt}</td>
              </tr>
            ))}
          </Table>
          <section>
            <h2>各次发现（{d.findings.length} 次）</h2>
            {d.findings.map((f) => (
              <details key={f.id}>
                <summary>
                  {f.runId} · {f.recordedAt}
                </summary>
                <dl>
                  <Field name="发现编号">{f.id}</Field>
                  <Field name="到期时间">{f.dueAt}</Field>
                </dl>
                <h3>预期</h3>
                <JsonEvidence value={f.expected} />
                <h3>实际</h3>
                <JsonEvidence value={f.actual} />
                <h3>证据引用</h3>
                <JsonEvidence value={f.evidenceRefs} />
              </details>
            ))}
          </section>
          <details>
            <summary>当前差异证据</summary>
            <JsonEvidence
              value={{
                expected: d.issue.expected,
                actual: d.issue.actual,
                evidence: d.issue.evidenceRefs,
              }}
            />
          </details>
          <Snapshot asOf={d.asOf} />
        </>
      )}
    </Resource>
  );
}
function AcceptIssue({
  id,
  onAccepted,
}: {
  id: string;
  onAccepted: () => void;
}) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return (
    <form
      className="accept"
      onSubmit={async (event) => {
        event.preventDefault();
        if (!reason.trim() || busy) return;
        setBusy(true);
        setError("");
        try {
          const response = await fetch(`/api/recon/issues/${enc(id)}/accept`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ reason: reason.trim() }),
          });
          if (!response.ok)
            throw new Error(
              response.status === 409
                ? "差异状态已变化，请刷新后核对"
                : `接受失败（HTTP ${response.status}），请重试`,
            );
          onAccepted();
        } catch (error: unknown) {
          setError(error instanceof Error ? error.message : "接受失败，请重试");
          setBusy(false);
        }
      }}
    >
      <h2>运营处置</h2>
      <label>
        接受原因
        <textarea
          required
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
      </label>
      <button type="submit" disabled={busy || !reason.trim()}>
        {busy ? "提交中…" : "接受"}
      </button>
      {error && <p role="alert">{error}</p>}
    </form>
  );
}
export function EventPage({ id }: { id: string }) {
  return (
    <Resource<EventDetail> path={`/api/events/${enc(id)}`}>
      {(d) => (
        <>
          <h1>原事件摘要</h1>
          <dl className="summary">
            <Field name="事件号">{d.eventId}</Field>
            <Field name="事件类型">{d.eventType}</Field>
            <Field name="状态">{d.status}</Field>
            <Field name="结果">{d.result}</Field>
            <Field name="业务时间">{d.occurredAt}</Field>
            <Field name="接收时间">{d.receivedAt}</Field>
            <Field name="API 版本">{d.apiVersion}</Field>
            <Field name="模式">{d.livemode ? "LIVE" : "测试模式"}</Field>
            <Field name="关联对象">
              {d.subjectId && d.subject ? (
                <a
                  href={href(
                    d.subject === "batch" ? "batches" : "payments",
                    d.subjectId,
                  )}
                >
                  {d.subjectId}
                </a>
              ) : (
                "—"
              )}
            </Field>
          </dl>
          <p>事件脱敏摘要</p>
          <Deliveries deliveries={d.deliveries} />
          <Snapshot asOf={d.asOf} />
        </>
      )}
    </Resource>
  );
}
export function BankPage({ id }: { id: string }) {
  return (
    <Resource<BankDetail> path={`/api/bank-lines/${enc(id)}`}>
      {(d) => (
        <>
          <h1>银行流水详情</h1>
          <dl className="summary">
            <Field name="流水编号">{d.bankLine.bankTxnId}</Field>
            <Field name="金额">
              <Money
                amount={
                  d.bankLine.currency === "CAD" ? d.bankLine.amount : null
                }
              />
              {d.bankLine.currency !== "CAD" && (
                <span>
                  {d.bankLine.amount} · {d.bankLine.currency} 原始最小单位
                </span>
              )}
            </Field>
            <Field name="方向">{d.bankLine.direction}</Field>
            <Field name="状态">{d.bankLine.status}</Field>
            <Field name="匹配状态">{d.bankLine.matchStatus}</Field>
            <Field name="记账日">{d.bankLine.bookingDate}</Field>
            <Field name="起息日">{d.bankLine.valueDate}</Field>
            <Field name="引用">{d.bankLine.reference}</Field>
            <Field name="已匹配批次">
              {d.bankLine.matchedBatchId ? (
                <a href={href("batches", d.bankLine.matchedBatchId)}>
                  {d.bankLine.matchedBatchId}
                </a>
              ) : (
                "—"
              )}
            </Field>
          </dl>
          <Postings postings={d.postings} />
          <details>
            <summary>流水版本（{d.versions.length}）</summary>
            <JsonEvidence value={d.versions} />
          </details>
          <Snapshot asOf={d.asOf} />
        </>
      )}
    </Resource>
  );
}
export function StatementPage({ file, seq }: { file: string; seq: string }) {
  return (
    <Resource<StatementDetail>
      path={`/api/statement-lines/${enc(file)}/${enc(seq)}`}
    >
      {(d) => (
        <>
          <h1>批次行证据</h1>
          <dl className="summary">
            <Field name="行编号">{d.statementLine.statementLineId}</Field>
            <Field name="文件编号">{d.statementLine.fileId}</Field>
            <Field name="文件内行号">{d.statementLine.lineSeq}</Field>
            <Field name="批次">
              <a href={href("batches", d.statementLine.batchId)}>
                {d.statementLine.batchId}
              </a>
            </Field>
            <Field name="处理方编号">{d.statementLine.processorRef}</Field>
            <Field name="类型">{d.statementLine.lineType}</Field>
            <Field name="毛额">
              <Money amount={d.statementLine.gross} />
            </Field>
            <Field name="手续费">
              <Money amount={d.statementLine.fee} />
            </Field>
            <Field name="净额">
              <Money amount={d.statementLine.net} />
            </Field>
            <Field name="业务时间">{d.statementLine.occurredAt}</Field>
            <Field name="结算日">{d.statementLine.settlementDate}</Field>
          </dl>
          <Snapshot asOf={d.asOf} />
        </>
      )}
    </Resource>
  );
}
// Preserve issue-scoped links and their business source; use one entry snapshot for the audit data.
async function loadEntry(path: string, signal?: AbortSignal) {
  const [issueId, entryId] = path.split("/");
  const issue = await get<IssueDetail>(`/api/recon/issues/${issueId}`, signal);
  if (!issue.references.entryIds.includes(decodeURIComponent(entryId)))
    throw new Error("分录不在这条差异的证据中");
  const refs = issue.references;
  const sources = await Promise.all(
    [
      ...refs.batchIds.map((id) => `/api/batches/${enc(id)}`),
      ...refs.paymentIds.map((id) => `/api/payments/${enc(id)}`),
      ...refs.refundIds.map((id) => `/api/refunds/${enc(id)}`),
      ...refs.bankTxnIds.map((id) => `/api/bank-lines/${enc(id)}`),
    ].map(async (url) => ({
      url,
      data: await get<Pick<PaymentDetail, "postings" | "asOf">>(url, signal),
    })),
  );
  const source = sources.find((s) =>
    s.data.postings.some((p) => p.entryId === decodeURIComponent(entryId)),
  );
  if (!source) throw new Error("关联记录中未找到这条分录的 posting");
  const entry = await get<EntryDetail>(`/api/entries/${entryId}`, signal);
  return {
    reversedBy: entry.reversedBy,
    postings: entry.postings,
    asOf: entry.asOf,
    source: `#${source.url.slice(4)}`,
  };
}
export function EntryPage({
  issueId,
  entryId,
}: {
  issueId: string;
  entryId: string;
}) {
  return (
    <Resource path={`${enc(issueId)}/${enc(entryId)}`} loader={loadEntry}>
      {(d) => (
        <>
          <h1>相关 posting</h1>
          <p>{entryId}</p>
          <ReversedNotice id={d.reversedBy} />
          <p>
            <a href={href("recon", issueId)}>返回差异</a> ·{" "}
            <a href={d.source}>查看关联业务详情</a>
          </p>
          <Postings postings={d.postings} />
          <Snapshot asOf={d.asOf} />
        </>
      )}
    </Resource>
  );
}
