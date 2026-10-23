import { expect, test } from "@playwright/test";
import type { Overview, PaymentDetail } from "../src/api";
import { backend, fixture, replayB1Confirmation } from "./helpers";

test("ac_1_11 ac_2_16 S3 amounts, balances and duplicate B1 confirmation", async ({
  page,
  request,
}) => {
  // Negative merchant available requires P3 refunds; do not fabricate it with SQL in P2.
  test.info().annotations.push({
    type: "P3",
    description: "AC-1.11 negative available E2E deferred until refunds exist",
  });
  const truth = await fixture<{ aliases: { P1: string; B1: string } }>(
    "sample-a-1-3",
    "truth.json",
  );
  const { P1, B1 } = truth.aliases;
  await page.goto("/#/");
  const balances = page.locator(".balances");
  await expect(
    balances
      .locator("div")
      .filter({ has: page.getByText("结算在途", { exact: true }) }),
  ).toHaveText("结算在途CAD 0.00");
  await expect(
    balances
      .locator("div")
      .filter({ has: page.getByText("银行现金", { exact: true }) }),
  ).toHaveText("银行现金CAD 96.80");
  await expect(
    balances
      .locator("div")
      .filter({ has: page.getByText("商户可用", { exact: true }) }),
  ).toHaveText("商户可用CAD 96.80");
  await expect(balances).toContainText("待查收款CAD 0.00");
  await page.getByRole("link", { name: P1, exact: true }).click();
  await expect(page.getByRole("heading", { name: "交易详情" })).toBeVisible();
  for (const [label, amount] of [
    ["毛额", "CAD 100.00"],
    ["手续费", "CAD 3.20"],
    ["净额", "CAD 96.80"],
  ]) {
    await expect(
      page
        .locator("dl > div")
        .filter({ has: page.getByText(label, { exact: true }) }),
    ).toHaveText(`${label}${amount}`);
  }
  await expect(
    page
      .getByRole("table", { name: "账本 posting（9 条）" })
      .locator("tbody tr"),
  ).toHaveCount(9);
  const before: PaymentDetail = await (
    await request.get(`${backend}/api/payments/${P1}`)
  ).json();
  const overview: Overview = await (
    await request.get(`${backend}/api/overview`)
  ).json();
  expect(before.bankDeposit).not.toBeNull();
  await expect(
    page.getByRole("link", {
      name: before.bankDeposit?.bankTxnId,
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("link", { name: B1, exact: true }).click();
  await expect(page.getByRole("heading", { name: "批次详情" })).toBeVisible();
  await expect(
    page
      .getByRole("table", { name: "批次行", exact: true })
      .locator("tbody tr"),
  ).toHaveCount(1);
  await expect(page.getByRole("table", { name: "批次状态历史" })).toContainText(
    "BANKED",
  );
  await expect(
    page.getByRole("table", { name: "分录", exact: true }),
  ).toContainText("BATCH_SETTLED");
  await page
    .getByRole("link", { name: before.bankDeposit?.bankTxnId, exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "银行流水详情" }),
  ).toBeVisible();
  await page.goto(`/#/payments/${P1}`);
  const eventId = await replayB1Confirmation(request);
  await page.reload();
  const deliveries = page.getByRole("table", {
    name: `投递记录（${before.deliveries.length + 1} 条）`,
  });
  await expect(
    deliveries.locator("tbody tr").filter({ hasText: eventId }),
  ).toContainText("重复（DUPLICATE_FACT）");
  await expect(
    page
      .getByRole("table", { name: "账本 posting（9 条）" })
      .locator("tbody tr"),
  ).toHaveCount(9);
  const after: PaymentDetail = await (
    await request.get(`${backend}/api/payments/${P1}`)
  ).json();
  expect(after.postings).toEqual(before.postings);
  expect(after.deliveries).toHaveLength(before.deliveries.length + 1);
  const next: Overview = await (
    await request.get(`${backend}/api/overview`)
  ).json();
  expect(next.accounts).toEqual(overview.accounts);
  expect(next.asOf.seq).toBe(overview.asOf.seq);
  await page.goto("/#/");
  await expect(balances).toContainText("银行现金CAD 96.80");
  await expect(balances).toContainText("商户可用CAD 96.80");
  await page.screenshot({ path: "e2e-artifacts/overview.png", fullPage: true });
  await page.goto(`/#/payments/${P1}`);
  await expect(
    page.getByRole("table", { name: "账本 posting（9 条）" }),
  ).toBeVisible();
  await page.screenshot({
    path: "e2e-artifacts/settled-payment.png",
    fullPage: true,
  });
});
