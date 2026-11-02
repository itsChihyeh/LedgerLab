import { expect, test } from "@playwright/test";
import type { Overview, PayoutDetail } from "../src/api";
import { backend, control } from "./helpers";

test("ac_7_19 lost bank response recovers UNKNOWN to PAID with only one payment", async ({
  page,
  request,
}) => {
  const bankResponse = await request.get(`${control}/bank-url`);
  expect(bankResponse.ok()).toBeTruthy();
  const bank = await bankResponse.text();
  const plan = await request.put(`${bank}/sim/bank/plan`, {
    data: { target_ref: "*", fault: "EXECUTE_DROP_RESPONSE", parameter: 0 },
  });
  expect(plan.ok(), await plan.text()).toBeTruthy();
  await page.goto("/#/payouts");
  await expect(
    page.getByRole("table", { name: "提现列表", exact: true }),
  ).toContainText("暂无记录");
  const form = page.getByRole("form", { name: "发起提现" });
  await form.getByLabel("业务编号", { exact: true }).fill("PO1");
  await form.getByLabel("提现金额（CAD 元）").fill("100.00");
  await form.getByRole("button", { name: "提交提现", exact: true }).click();
  await expect(form.getByRole("alert")).toContainText(
    "409 INSUFFICIENT_FUNDS (MERCHANT_BALANCE)",
  );
  await form.getByLabel("提现金额（CAD 元）").fill("96.80");
  const submitted: { key: string | undefined; body: unknown }[] = [];
  page.on("request", (r) => {
    if (r.method() === "POST" && r.url().endsWith("/api/payouts"))
      submitted.push({
        key: r.headers()["idempotency-key"],
        body: r.postDataJSON(),
      });
  });
  // The real API commits; lose only its browser response, then retry the same immutable request.
  await page.route(
    "**/api/payouts",
    async (route) => {
      const committed = await route.fetch();
      expect(committed.status()).toBe(201);
      await route.abort("connectionreset");
    },
    { times: 1 },
  );
  await form.getByRole("button", { name: "提交提现", exact: true }).click();
  await expect(
    form.getByRole("button", { name: "用原键重试提现" }),
  ).toBeEnabled();
  await form.getByRole("button", { name: "用原键重试提现" }).click();
  await expect(form.getByRole("status")).toContainText("已受理");
  expect(submitted).toHaveLength(2);
  expect(submitted[0]).toEqual(submitted[1]);
  expect(submitted[0].key).toBeTruthy();
  const id = (await form.getByRole("link").textContent()) ?? "";
  expect(id).toMatch(/^mpo_/);
  const list = page.getByRole("table", { name: "提现列表", exact: true });
  await expect(list.locator("tbody tr")).toHaveCount(1);
  await expect(list).toContainText("PO1");
  await expect(page.locator(".balances")).toContainText("提现在途CAD 96.80");
  await expect(page.locator(".balances")).toContainText(
    "暂无可用于新提现的现金",
  );
  const run = async () => {
    const r = await request.post(`${control}/payout-worker`);
    expect(r.status(), await r.text()).toBe(200);
    expect(await r.text()).toBe("1");
  };
  await run();
  await form.getByRole("link").click();
  await expect(page.getByRole("heading", { name: "提现详情" })).toBeVisible();
  await expect(page.locator(".summary")).toContainText("状态UNKNOWN");
  await expect(page.locator(".summary")).toContainText(`银行请求号${id}`);
  await expect(page.getByRole("table", { name: "提现尝试列表" })).toContainText(
    "NO_RESPONSE",
  );
  await page.screenshot({
    path: test.info().outputPath("payout-unknown.png"),
    fullPage: true,
  });
  const held: Overview = await (
    await request.get(`${backend}/api/overview`)
  ).json();
  expect([
    held.merchantAvailable,
    held.payoutInTransit,
    held.bankCash,
    held.freeCash,
  ]).toEqual([0, 9680, 9680, 0]);
  const advance = await request.post(`${control}/advance-clock`, { data: "2" });
  expect(advance.ok()).toBeTruthy();
  await run();
  await page.getByRole("button", { name: "刷新提现" }).click();
  await expect(page.locator(".summary")).toContainText("状态ACCEPTED");
  await expect(
    page.getByRole("table", { name: "提现尝试列表" }).locator("tbody tr"),
  ).toHaveCount(2);
  const execute = await request.post(`${control}/payout-execute`);
  expect(execute.ok(), await execute.text()).toBeTruthy();
  await page.getByRole("button", { name: "刷新提现" }).click();
  await expect(page.locator(".summary")).toContainText("状态PAID");
  const history = page.getByRole("table", { name: "提现状态时间线" });
  await expect(history).toContainText("UNKNOWN");
  await expect(history).toContainText("ACCEPTED");
  await expect(history).toContainText("PAID");
  const lines = page.getByRole("table", { name: "出账 / 退回流水" });
  await expect(lines).toContainText(`bt_${id}_1`);
  await expect(lines).toContainText("CAD 96.80");
  await expect(lines).toContainText("PAYOUT_PAID");
  const detail: PayoutDetail = await (
    await request.get(`${backend}/api/payouts/${id}`)
  ).json();
  expect(detail.entries.map((e) => e.entryType)).toEqual([
    "PAYOUT_RESERVED",
    "PAYOUT_PAID",
  ]);
  await expect(
    page.getByRole("table", { name: "账本 posting（4 条）" }),
  ).toContainText("PAYOUT_PAID");
  await page.screenshot({
    path: test.info().outputPath("payout-paid.png"),
    fullPage: true,
  });
  await lines.getByRole("link").click();
  await expect(
    page.getByRole("heading", { name: "银行流水详情" }),
  ).toBeVisible();
  const payments = await request.get(`${bank}/sim/bank/payments`);
  expect(payments.ok()).toBeTruthy();
  const bankPayments = await payments.json();
  expect(bankPayments).toHaveLength(1);
  expect(bankPayments[0]).toMatchObject({
    requestId: id,
    paymentId: detail.payout.bankAckRef,
    status: "EXECUTED",
  });
  await page.goto("/#/");
  await expect(page.locator(".balances")).toContainText("商户可用CAD 0.00");
  await expect(page.locator(".balances")).toContainText("提现在途CAD 0.00");
  await expect(page.locator(".balances")).toContainText("银行现金CAD 0.00");
  await expect(page.locator(".balances")).toContainText(
    "可支配现金（free_cash）CAD 0.00",
  );
  const final: Overview = await (
    await request.get(`${backend}/api/overview`)
  ).json();
  expect([
    final.merchantAvailable,
    final.payoutInTransit,
    final.bankCash,
    final.freeCash,
  ]).toEqual([0, 0, 0, 0]);
});
