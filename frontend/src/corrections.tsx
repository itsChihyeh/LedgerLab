import type {
  BatchDetail,
  CorrectionDetail,
  CorrectionTrace,
  EntryDetail,
  PaymentDetail,
} from "./api";
import {
  enc,
  Field,
  href,
  Money,
  Postings,
  Resource,
  Snapshot,
  Table,
} from "./components";

export function AmountChange({
  params,
}: {
  params: CorrectionDetail["correction"]["params"];
}) {
  if (params.oldAmount === undefined) return <>—</>;
  return (
    <>
      <Money amount={params.oldAmount} />
      {params.newAmount !== undefined && (
        <>
          {" "}
          → <Money amount={params.newAmount} />
        </>
      )}
    </>
  );
}

export function CorrectionTable({ data }: { data: CorrectionDetail[] }) {
  return (
    <Table
      caption="纠错记录"
      columns={["纠错", "类型", "金额前后值", "原因", "提交人", "时间"]}
      empty={!data.length}
    >
      {data.map(({ correction: c }) => (
        <tr key={c.id}>
          <td>
            <a href={href("corrections", c.id)}>{c.id}</a>
          </td>
          <td>
            {c.type}
            {c.type === "VOID_CAPTURE" && <small>CAPTURED → VOIDED</small>}
          </td>
          <td>
            <AmountChange params={c.params} />
          </td>
          <td>{c.reasonCode}</td>
          <td>{c.requestedBy}</td>
          <td>{c.recordedAt}</td>
        </tr>
      ))}
    </Table>
  );
}

export function CorrectionsPage() {
  return (
    <>
      <h1>纠错记录</h1>
      <p>最近 50 条 · 按时间倒序 · 只读</p>
      <Resource<CorrectionDetail[]> path="/api/corrections/recent">
        {(data) => <CorrectionTable data={data} />}
      </Resource>
    </>
  );
}

export function ReversedNotice({ id }: { id: string | null }) {
  return id ? (
    <p>
      已被 <a href={href("corrections", id)}>{id}</a> 冲销
    </p>
  ) : null;
}

export function PaymentCorrectionHistory({
  payment,
}: {
  payment: PaymentDetail;
}) {
  return (
    <section aria-label="支付纠错历史">
      <h2>纠错历史</h2>
      <Resource<CorrectionDetail[]>
        path={`/api/corrections?subjectType=payment&subjectId=${enc(payment.id)}`}
      >
        {(data) => (
          <>
            <CorrectionTable data={data} />
            {data.flatMap((d) =>
              d.targets
                .filter((t) => t.role === "REVERSED")
                .map((t) => (
                  <div key={t.entryId}>
                    <a href={href("entries", t.entryId)}>{t.entryId}</a>
                    <ReversedNotice id={d.correction.id} />
                  </div>
                )),
            )}
          </>
        )}
      </Resource>
    </section>
  );
}

export function CorrectionContent({ data }: { data: CorrectionDetail }) {
  const c = data.correction;
  return (
    <>
      <h1>纠错详情</h1>
      <dl className="summary">
        <Field name="纠错编号">{c.id}</Field>
        <Field name="类型">{c.type}</Field>
        <Field name="原因">{c.reasonCode}</Field>
        <Field name="说明">{c.reasonNote}</Field>
        <Field name="提交人">{c.requestedBy}</Field>
        <Field name="时间">{c.recordedAt}</Field>
        <Field name="支付金额前后值">
          <AmountChange params={c.params} />
        </Field>
      </dl>
      <Table
        caption="目标分录"
        columns={["分录", "角色"]}
        empty={!data.targets.length}
      >
        {data.targets.map((t) => (
          <tr key={t.entryId}>
            <td>
              <a href={href("entries", t.entryId)}>{t.entryId}</a>
            </td>
            <td>{t.role}</td>
          </tr>
        ))}
      </Table>
      <section aria-label="生成的分录">
        <h2>生成的分录与 posting</h2>
        {data.entries.map((e) => (
          <section key={e.entryId} aria-label={e.entryType}>
            <h3>
              {e.entryType} ·{" "}
              <a href={href("entries", e.entryId)}>{e.entryId}</a>
            </h3>
            <Table
              caption={`${e.entryType} posting`}
              columns={["行", "账户", "方向", "金额"]}
              empty={!e.postings.length}
            >
              {e.postings.map((p) => (
                <tr key={p.lineNo}>
                  <td>{p.lineNo}</td>
                  <td>{p.accountCode}</td>
                  <td>{p.side === "D" ? "借（D）" : "贷（C）"}</td>
                  <td>
                    <Money amount={p.amount} />
                  </td>
                </tr>
              ))}
            </Table>
          </section>
        ))}
      </section>
    </>
  );
}

export function TraceContent({ trace }: { trace: CorrectionTrace }) {
  return (
    <>
      <Table
        caption="纠错证据"
        columns={["类型", "编号", "查看证据"]}
        empty={!trace.evidence.length}
      >
        {Array.from(
          new Map(trace.evidence.map((e) => [`${e.type}:${e.id}`, e])).values(),
        ).map((e) => (
          <tr key={`${e.type}:${e.id}`}>
            <td>{e.type}</td>
            <td>{e.id}</td>
            <td>
              {e.links.length
                ? e.links.map((l) => (
                    <div key={`${l.kind}:${l.id}:${l.lineSeq}`}>
                      <a
                        href={`${href(l.kind, l.id)}${l.lineSeq === null ? "" : `/${l.lineSeq}`}`}
                      >
                        {l.kind === "statement-lines"
                          ? "结算单行"
                          : l.kind === "batch-lines"
                            ? "批次行"
                            : l.kind === "events"
                              ? "原事件"
                              : l.kind === "bank-lines"
                                ? "银行流水"
                                : l.kind === "recon"
                                  ? "差异"
                                  : "批次"}{" "}
                        · {l.id}
                        {l.lineSeq === null ? "" : ` / ${l.lineSeq}`}
                      </a>
                    </div>
                  ))
                : "暂无可用的只读页面关联"}
            </td>
          </tr>
        ))}
      </Table>
      <Table
        caption="受影响对象（当前状态）"
        columns={["对象", "编号", "状态"]}
        empty={!trace.objects.length}
      >
        {trace.objects.map((o) => (
          <tr key={`${o.kind}:${o.id}`}>
            <td>
              {o.kind === "payments"
                ? "支付"
                : o.kind === "batches"
                  ? "批次"
                  : o.kind === "bank-lines"
                    ? "流水"
                    : "关闭的差异"}
            </td>
            <td>
              <a href={href(o.kind, o.id)}>{o.id}</a>
            </td>
            <td>{o.status}</td>
          </tr>
        ))}
      </Table>
      <Table
        caption="本次纠错触发的状态历史"
        columns={["对象", "原状态", "新状态"]}
        empty={!trace.history.length}
      >
        {trace.history.map((h) => (
          <tr key={`${h.kind}:${h.id}:${h.fromStatus}:${h.toStatus}`}>
            <td>
              <a href={href(h.kind, h.id)}>{h.id}</a>
            </td>
            <td>{h.fromStatus ?? "—"}</td>
            <td>{h.toStatus}</td>
          </tr>
        ))}
      </Table>
      <Snapshot asOf={trace.asOf} />
    </>
  );
}

export function CorrectionPage({ id }: { id: string }) {
  return (
    <Resource<CorrectionDetail> path={`/api/corrections/${enc(id)}`}>
      {(data) => (
        <>
          <CorrectionContent data={data} />
          <Resource<CorrectionTrace> path={`/api/correction-traces/${enc(id)}`}>
            {(trace) => <TraceContent trace={trace} />}
          </Resource>
        </>
      )}
    </Resource>
  );
}

export function LedgerEntryPage({ id }: { id: string }) {
  return (
    <Resource<EntryDetail> path={`/api/entries/${enc(id)}`}>
      {(d) => (
        <>
          <h1>分录详情</h1>
          <p>
            {d.entry.id} · {d.entry.entryType}
          </p>
          <ReversedNotice id={d.reversedBy} />
          {d.entry.correctionId && (
            <p>
              所属纠错：
              <a href={href("corrections", d.entry.correctionId)}>
                {d.entry.correctionId}
              </a>
            </p>
          )}
          {d.entry.reversesEntryId && (
            <p>
              冲销目标：
              <a href={href("entries", d.entry.reversesEntryId)}>
                {d.entry.reversesEntryId}
              </a>
            </p>
          )}
          <Postings postings={d.postings} />
          <Snapshot asOf={d.asOf} />
        </>
      )}
    </Resource>
  );
}

export function BatchLinePage({ id, seq }: { id: string; seq: string }) {
  return (
    <Resource<BatchDetail> path={`/api/batches/${enc(id)}`}>
      {(d) => {
        const line = d.lines.find((l) => String(l.lineNo) === seq);
        if (!line) return <p role="alert">找不到这条批次行</p>;
        return (
          <>
            <h1>批次行证据</h1>
            <p>
              <a href={href("batches", id)}>{id}</a> · 行 {seq} ·{" "}
              {line.balanceTxnId}
            </p>
            <dl className="summary">
              <Field name="处理方编号">{line.processorRef}</Field>
              <Field name="类型">{line.lineType}</Field>
              <Field name="毛额">
                <Money amount={line.gross} />
              </Field>
              <Field name="手续费">
                <Money amount={line.fee} />
              </Field>
              <Field name="净额">
                <Money amount={line.net} />
              </Field>
            </dl>
            <Snapshot asOf={d.asOf} />
          </>
        );
      }}
    </Resource>
  );
}
