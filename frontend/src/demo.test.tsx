import { mkdirSync, mkdtempSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { expect, test } from "vitest";
import { latestSandboxReport } from "../sandbox-report.ts";
import {
  ActionsPanel,
  ArchivePanel,
  CounterCards,
  InvariantPanel,
  SandboxPanel,
  TimelineList,
} from "./demo";
import {
  ACTIONS,
  applyEvent,
  emptyState,
  INVARIANTS,
  parseSandboxReport,
  violationObject,
} from "./demo-model";

const allOk = (failed: string[] = []) => ({
  actionId: "act_1",
  checkedAt: "2026-11-05T16:00:00Z",
  ok: failed.length === 0,
  checks: INVARIANTS.map(({ id }) => ({ id, ok: !failed.includes(id) })),
  failed,
  violations: failed.map((id) => ({
    invariant: id,
    detail: "payment pay_abc123: refunds 12000 exceed captured amount 10000",
  })),
});

test("ac_9_1 there are eight actions, each says what it proves", () => {
  expect(ACTIONS.map((a) => a.name)).toEqual([
    "pay",
    "replay-webhook",
    "batch-first",
    "double-refund",
    "drop-refund-response",
    "bank-drop-receipt",
    "kill-worker",
    "bad-statement",
  ]);
  for (const action of ACTIONS) expect(action.proves.length).toBeGreaterThan(8);
  const html = renderToStaticMarkup(
    <ActionsPanel running={null} onRun={() => {}} notice={null} />,
  );
  expect(html.match(/data-action=/g)).toHaveLength(8);
  // The review asked for the lease wording, not "another worker".
  expect(html).toContain("租约过期、重新领取（代数 +1）");
  expect(html).not.toContain("另一个 worker");
});

test("ac_9_1 buttons are disabled while an action runs", () => {
  const html = renderToStaticMarkup(
    <ActionsPanel running="pay" onRun={() => {}} notice={null} />,
  );
  expect(html.match(/disabled=""/g)).toHaveLength(8);
  expect(html).toContain("进行中…");
});

test("ac_9_1 live events fold into feed, verdict and counters", () => {
  let state = applyEvent(
    emptyState,
    "step",
    JSON.stringify({
      actionId: "act_1",
      action: "pay",
      n: 1,
      label: "Charge created",
      at: "2026-11-05T16:00:01Z",
    }),
  );
  state = applyEvent(
    state,
    "timeline",
    JSON.stringify({
      items: [
        { kind: "inbox", key: "1", label: "charge.succeeded", at: "t1" },
        { kind: "entry", key: "7", label: "PAYMENT_CAPTURED 500", at: "t2" },
      ],
    }),
  );
  // The same item delivered twice (stream resume) shows once; newest first.
  state = applyEvent(
    state,
    "timeline",
    JSON.stringify({
      items: [
        { kind: "entry", key: "7", label: "PAYMENT_CAPTURED 500", at: "t2" },
      ],
    }),
  );
  expect(state.feed.map((item) => item.id)).toEqual([
    "entry:7",
    "inbox:1",
    "step:act_1:1",
  ]);
  state = applyEvent(state, "invariants", JSON.stringify(allOk()));
  state = applyEvent(state, "counters", JSON.stringify({ payments: 3 }));
  expect(state.verdict?.ok).toBe(true);
  expect(state.counters?.payments).toBe(3);
  expect(applyEvent(state, "step", "not json")).toBe(state);
  expect(applyEvent(state, "other", "{}")).toBe(state);
});

test("ac_9_2 a failed invariant is red and names the violating payment", () => {
  const html = renderToStaticMarkup(
    <InvariantPanel verdict={allOk(["INV-7"])} />,
  );
  expect(html).toContain('data-invariant="INV-7" data-status="failed"');
  expect(html.match(/data-status="ok"/g)).toHaveLength(11);
  expect(html).toContain("1 条失败");
  expect(html).toContain('role="alert"');
  expect(html).toContain("pay_abc123");
  expect(html).toContain('href="#/payments/pay_abc123"');
  expect(violationObject("refund rfd_9 (READY): 2 reservations")).toEqual({
    kind: "refund",
    id: "rfd_9",
  });
});

test("ac_9_1 all twelve invariants pass", () => {
  const html = renderToStaticMarkup(<InvariantPanel verdict={allOk()} />);
  expect(html.match(/data-status="ok"/g)).toHaveLength(12);
  expect(html).toContain("全部通过");
  expect(html).not.toContain('role="alert"');
});

test("ac_9_1 counters separate ledger queries from what the actions observed", () => {
  const html = renderToStaticMarkup(
    <CounterCards counters={{ payments: 4, overRefundsRejected: 2 }} />,
  );
  expect(html).toContain("来自账本查询");
  expect(html).toContain("演示动作观察到的");
  expect(html).toContain("演示动作观察到的被拒次数");
  const observed = html.slice(html.indexOf("演示动作观察到的</h3>"));
  expect(observed).toContain('data-counter="overRefundsRejected"');
  expect(html.slice(0, html.indexOf("演示动作观察到的</h3>"))).not.toContain(
    "overRefundsRejected",
  );
});

test("ac_9_1 the timeline lists newest entries with a readable source", () => {
  const html = renderToStaticMarkup(
    <TimelineList
      feed={[
        {
          id: "entry:7",
          source: "entry",
          text: "PAYMENT_CAPTURED 500",
          at: "x",
        },
      ]}
    />,
  );
  expect(html).toContain("分录");
  expect(html).toContain("PAYMENT_CAPTURED 500");
});

test("ac_9_5 the page shows the last reset and the archived days", () => {
  const html = renderToStaticMarkup(
    <ArchivePanel
      data={{
        lastReset: "2026-11-05T04:00:00Z",
        archive: [
          {
            file: "2026-11-04.json",
            summary: {
              day: "2026-11-04",
              counters: { payments: 9, entries: 31 },
              invariants: { ok: true },
            },
          },
        ],
      }}
    />,
  );
  expect(html).toContain("2026-11-05T04:00:00Z");
  expect(html).toContain("2026-11-04");
  expect(html).toContain("支付 9");
  expect(renderToStaticMarkup(<ArchivePanel data={null} />)).toContain(
    "暂无记录",
  );
});

test("ac_9_7 no report says so, a report shows run time and its numbers", () => {
  expect(renderToStaticMarkup(<SandboxPanel report="none" />)).toContain(
    "尚无运行报告",
  );
  const report = parseSandboxReport({
    run_id: "run42",
    generated_at: "2026-11-06T10:00:00Z",
    payments: { stripe: 20, local: 20 },
    invariants: { violations: 0 },
    notes: "ignored text",
  });
  expect(report?.facts).toEqual([
    { name: "payments · stripe", value: 20 },
    { name: "payments · local", value: 20 },
    { name: "invariants · violations", value: 0 },
  ]);
  const html = renderToStaticMarkup(<SandboxPanel report={report ?? "none"} />);
  expect(html).toContain("run42");
  expect(html).toContain("2026-11-06T10:00:00Z");
  expect(html).toContain("Stripe 测试模式、由本地运行产生");
  expect(html).not.toContain("尚无运行报告");
  expect(parseSandboxReport([1, 2])).toBeNull();
  expect(parseSandboxReport("<!doctype html>")).toBeNull();
});

test("ac_9_7 the build picks the newest report by its recorded time", () => {
  const dir = mkdtempSync(join(tmpdir(), "sandbox-runs-"));
  expect(latestSandboxReport(join(dir, "missing"))).toBeNull();
  expect(latestSandboxReport(dir)).toBeNull();
  writeFileSync(
    join(dir, "sandbox-b.json"),
    '{"generated_at":"2026-11-01T00:00:00Z","n":1}',
  );
  writeFileSync(
    join(dir, "sandbox-a.json"),
    '{"generated_at":"2026-11-03T00:00:00Z","n":2}',
  );
  writeFileSync(join(dir, "sandbox-c.md"), "not a report");
  writeFileSync(join(dir, "sandbox-d.json"), "[1]");
  expect(JSON.parse(latestSandboxReport(dir) ?? "{}").n).toBe(2);
  // Without a recorded time the file's own modification time decides.
  const plain = mkdtempSync(join(tmpdir(), "sandbox-runs-"));
  mkdirSync(plain, { recursive: true });
  writeFileSync(join(plain, "sandbox-x.json"), '{"n":1}');
  writeFileSync(join(plain, "sandbox-y.json"), '{"n":2}');
  utimesSync(join(plain, "sandbox-x.json"), 2_000_000_000, 2_000_000_000);
  utimesSync(join(plain, "sandbox-y.json"), 1_000_000_000, 1_000_000_000);
  expect(JSON.parse(latestSandboxReport(plain) ?? "{}").n).toBe(1);
});
