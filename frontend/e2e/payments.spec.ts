import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { expect, test } from "@playwright/test";
import type { PaymentDetail } from "../src/api";

import { backend, deliver } from "./helpers";

function event(id: string, charge: string) {
  return JSON.stringify({
    id,
    type: "charge.succeeded",
    api_version: "2026-09-30.endive",
    livemode: false,
    created: 1792591200,
    data: {
      object: {
        id: charge,
        payment_intent: `pi_${charge.slice(3)}`,
        amount: 10000,
        amount_captured: 10000,
        currency: "cad",
        captured: true,
        status: "succeeded",
        livemode: false,
        created: 1792591100,
      },
    },
  });
}

test("ac_1_14 shows one capture and all three delivery results", async ({
  page,
  request,
}) => {
  const suffix = randomUUID().replaceAll("-", "");
  const charge = `ch_${suffix}`;
  const firstId = `evt_${suffix}01`;
  const secondId = `evt_${suffix}02`;
  const first = event(firstId, charge);
  await deliver(request, first);
  // Finish the original fact before sending a different event for that fact.
  await expect
    .poll(
      async () =>
        (await request.get(`${backend}/api/payments/${charge}`)).status(),
      { timeout: 15_000 },
    )
    .toBe(200);
  await deliver(request, first);
  await deliver(request, event(secondId, charge));
  await expect
    .poll(
      async () => {
        const response = await request.get(`${backend}/api/payments/${charge}`);
        const detail: PaymentDetail = await response.json();
        return detail.deliveries.map((delivery) => [
          delivery.outcome,
          delivery.status,
          delivery.result,
        ]);
      },
      { timeout: 15_000 },
    )
    .toEqual([
      ["NEW", "PROCESSED", "APPLIED"],
      ["DUPLICATE", "PROCESSED", "APPLIED"],
      ["NEW", "PROCESSED", "DUPLICATE_FACT"],
    ]);

  await page.goto("/");
  await page.getByRole("link", { name: charge, exact: true }).click();
  await expect(page).toHaveURL(`/#/payments/${charge}`);
  await expect(page.getByRole("heading", { name: "交易详情" })).toBeVisible();
  await expect(
    page.locator("dd").filter({ hasText: /^CAD 100\.00$/ }),
  ).toBeVisible();
  await expect(
    page.locator("dd").filter({ hasText: /^CAPTURED$/ }),
  ).toBeVisible();
  await expect(page.locator("dd").filter({ hasText: charge })).toBeVisible();
  await expect(page.locator("dd").filter({ hasText: /^pay_/ })).toBeVisible();
  await expect(
    page.locator("dd").filter({ hasText: /^CAD 3\.20$/ }),
  ).toBeVisible();
  const postings = page
    .getByRole("table", { name: "账本 posting（2 条）" })
    .locator("tbody tr");
  await expect(postings).toHaveCount(2);
  await expect(postings.nth(0)).toContainText(
    /PAYMENT_CAPTURED.*1101.*processor_receivable.*借（D）.*CAD 100\.00/,
  );
  await expect(postings.nth(1)).toContainText(
    /PAYMENT_CAPTURED.*2101.*merchant_pending.*贷（C）.*CAD 100\.00/,
  );
  const deliveries = page
    .getByRole("table", { name: "投递记录（3 条）" })
    .locator("tbody tr");
  await expect(deliveries).toHaveCount(3);
  await expect(deliveries.nth(0)).toHaveText(
    `${firstId}charge.succeededNEWPROCESSEDAPPLIED`,
  );
  await expect(deliveries.nth(1)).toHaveText(
    `${firstId}charge.succeeded重复投递PROCESSEDAPPLIED`,
  );
  await expect(deliveries.nth(2)).toHaveText(
    `${secondId}charge.succeededNEWPROCESSED重复（DUPLICATE_FACT）`,
  );
  await expect(page.locator("footer")).toContainText(
    /asOf · 截至账本序号 [1-9]\d*/,
  );
  await expect(page.locator("footer time")).toHaveAttribute(
    "datetime",
    /^\d{4}-.*Z$/,
  );
  await mkdir("e2e-artifacts", { recursive: true });
  await page.screenshot({ path: "e2e-artifacts/detail.png", fullPage: true });
  await page.reload();
  await expect(page.getByRole("heading", { name: "交易详情" })).toBeVisible();
});

test("ac_1_14 missing payment shows 404 message", async ({ page }) => {
  await page.goto(`/payments/ch_missing_${randomUUID()}`);
  await expect(page.getByRole("alert")).toHaveText("找不到这笔支付");
});

test("ac_1_14 shows loading and API failure", async ({ page }) => {
  let release: () => void = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/payments/ch_unavailable", async (route) => {
    await gate;
    await route.fulfill({ status: 503, body: "" });
  });
  await page.goto("/payments/ch_unavailable");
  try {
    await expect(page.getByRole("status")).toHaveText("加载中…");
  } finally {
    release();
  }
  await expect(page.getByRole("alert")).toHaveText(
    "接口错误（HTTP 503），请刷新重试",
  );
});
