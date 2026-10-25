import { renderToStaticMarkup } from "react-dom/server";
import { expect, test } from "vitest";
import {
  AmountChange,
  CorrectionTable,
  ReversedNotice,
  TraceContent,
} from "./corrections";

test("ac_6_14 amount change preserves cents including zero and one cent", () => {
  expect(
    renderToStaticMarkup(
      <AmountChange params={{ oldAmount: 4500, newAmount: 5400 }} />,
    ),
  ).toContain('CAD 45.00</span> → <span class="money">CAD 54.00');
  expect(
    renderToStaticMarkup(
      <AmountChange params={{ oldAmount: 0, newAmount: 1 }} />,
    ),
  ).toContain("CAD 0.01");
  expect(
    renderToStaticMarkup(<AmountChange params={{ oldAmount: 4500 }} />),
  ).not.toContain("→");
  expect(renderToStaticMarkup(<AmountChange params={{}} />)).toBe("—");
});

test("ac_6_14 empty correction list and trace show explicit empty states", () => {
  expect(renderToStaticMarkup(<CorrectionTable data={[]} />)).toContain(
    "暂无记录",
  );
  const html = renderToStaticMarkup(
    <TraceContent
      trace={{
        evidence: [],
        objects: [],
        history: [],
        asOf: { seq: 0, at: "2026-10-25T00:00:00Z" },
      }}
    />,
  );
  expect(html.match(/暂无记录/g)).toHaveLength(3);
});

test("ac_6_14 reversal notice links to the owning correction", () => {
  expect(renderToStaticMarkup(<ReversedNotice id="cor_test" />)).toContain(
    'href="#/corrections/cor_test"',
  );
  expect(renderToStaticMarkup(<ReversedNotice id={null} />)).toBe("");
});

test("ac_6_14 trace links every supported evidence and distinguishes current from historical state", () => {
  const html = renderToStaticMarkup(
    <TraceContent
      trace={{
        evidence: [
          {
            type: "statement_line",
            id: "txn_test",
            links: [
              { kind: "statement-lines", id: "file_test", lineSeq: 2 },
              { kind: "batch-lines", id: "po_test", lineSeq: 1 },
            ],
          },
          {
            type: "bank_txn",
            id: "bank/test",
            links: [{ kind: "bank-lines", id: "bank/test", lineSeq: null }],
          },
          {
            type: "inbox_event",
            id: "evt_test",
            links: [{ kind: "events", id: "evt_test", lineSeq: null }],
          },
        ],
        objects: [
          { kind: "payments", id: "pay_test", status: "SETTLED" },
          { kind: "bank-lines", id: "bank/test", status: "APPLIED" },
        ],
        history: [
          {
            kind: "payments",
            id: "pay_test",
            fromStatus: "CAPTURED",
            toStatus: "CAPTURED",
          },
        ],
        asOf: { seq: 1, at: "2026-10-25T00:00:00Z" },
      }}
    />,
  );
  for (const path of [
    "statement-lines/file_test/2",
    "batch-lines/po_test/1",
    "bank-lines/bank%2Ftest",
    "events/evt_test",
  ])
    expect(html).toContain(`href="#/${path}"`);
  expect(html).toContain("当前状态");
  expect(html).toContain("SETTLED");
  expect(html).toContain("CAPTURED");
  expect(html).not.toContain("<button");
  expect(html).not.toContain("<form");
});
