import { renderToStaticMarkup } from "react-dom/server";
import { expect, test } from "vitest";
import type { PayoutDetail } from "./api";
import { MerchantBalance } from "./components";
import { PayoutBalances, PayoutContent, payoutError } from "./payouts";

test("ac_7_19 merchant debt and zero free cash have explicit labels", () => {
  const html = renderToStaticMarkup(
    <dl>
      <MerchantBalance available={-320} />
      <PayoutBalances inTransit={9680} freeCash={0} />
    </dl>,
  );
  expect(html).toContain("商户欠款");
  expect(html).toContain("CAD 3.20");
  expect(html).not.toContain("CAD -");
  expect(html).toContain("提现在途");
  expect(html).toContain("CAD 96.80");
  expect(html).toContain("CAD 0.00");
  expect(html).toContain("暂无可用于新提现的现金");
  expect(html).toContain("已预留的提现资金继续保留");
});

test("ac_7_19 positive free cash preserves cents without zero warning", () => {
  const html = renderToStaticMarkup(
    <PayoutBalances inTransit={0} freeCash={1} />,
  );
  expect(html).toContain("CAD 0.01");
  expect(html).not.toContain("暂无可用于新提现的现金");
});

test.each([
  ["INSUFFICIENT_FUNDS", "MERCHANT_BALANCE", "商户可用余额不足"],
  ["INSUFFICIENT_FUNDS", "FREE_CASH", "可支配现金不足"],
  ["PAYOUT_REF_CONFLICT", undefined, "业务编号已用于不同金额"],
])(
  "ac_7_19 payout conflict %s %s explains the reason",
  (code, reason, label) => {
    expect(payoutError(409, code as string, reason)).toContain(label);
    expect(payoutError(409, code as string, reason)).toContain(`409 ${code}`);
  },
);

test("ac_7_19 returned payout links debit return entries corrections and issues", () => {
  const data: PayoutDetail = {
    payout: {
      payoutId: "mpo_demo",
      merchantPayoutRef: "PO1",
      amount: 9680,
      currency: "CAD",
      status: "RETURNED",
      bankRequestId: "mpo_demo",
      bankAckRef: "bpm_demo",
      bankExecutionDate: "2026-11-16",
      bankTxnId: "bt_out",
      returnBankTxnId: "bt_return",
      failureReason: null,
      returnReason: "ACCOUNT_CLOSED",
      reservedAt: "2026-11-13T13:02:00Z",
    },
    history: [],
    commands: [],
    attempts: [],
    bankLines: ["DEBIT", "CREDIT"].map((direction, i) => ({
      bankTxnId: i ? "bt_return" : "bt_out",
      version: 1,
      direction,
      status: "BOOKED",
      amount: 9680,
      bookingDate: "2026-11-16",
      matchStatus: i ? "PAYOUT_RETURNED" : "PAYOUT_PAID",
      reference: "mpo_demo",
    })),
    entries: [
      { id: "je_demo", entryType: "PAYOUT_PAID", correctionId: "cor_demo" },
    ],
    postings: [
      {
        entryId: "je_demo",
        lineNo: 1,
        accountCode: 1103,
        side: "C",
        amount: 9680,
      },
    ],
    issues: [
      { id: "iss_demo", category: "PAYOUT_STATE_CONFLICT", status: "RESOLVED" },
    ],
    asOf: { seq: 1, at: "2026-11-16T14:00:00Z" },
  };
  const html = renderToStaticMarkup(<PayoutContent data={data} />);
  for (const path of [
    "bank-lines/bt_out",
    "bank-lines/bt_return",
    "entries/je_demo",
    "corrections/cor_demo",
    "recon/iss_demo",
  ])
    expect(html).toContain(`href="#/${path}"`);
  expect(html).toContain("ACCOUNT_CLOSED");
  expect(html).toContain("提现状态冲突");
  expect(html).toContain("PAYOUT_RETURNED");
  expect(html).toContain("CAD 96.80");
});
