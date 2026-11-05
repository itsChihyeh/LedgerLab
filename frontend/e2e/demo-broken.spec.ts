import { expect, test } from "@playwright/test";

// The backend for this scenario is started with ledgerlab.test.break-refund-lock=true (test source set only):
// refund acceptance skips the funds lock and the payment-row lock, so two concurrent refunds both pass.
test("ac_9_2_brokenLockShowsInv7Failure", async ({ page, request }) => {
  test.setTimeout(300_000);
  await page.goto("/");
  await expect(page.locator("[data-stream]")).toHaveAttribute(
    "data-stream",
    "open",
  );
  await expect(page.locator('[data-invariant][data-status="ok"]')).toHaveCount(
    12,
  );

  const answered = page.waitForResponse(
    (r) => r.url().endsWith("/api/demo/actions/double-refund"),
    { timeout: 240_000 },
  );
  await page.locator('[data-action="double-refund"]').click();
  // The scenario expected one refund accepted and one refused; the broken lock accepts both, so it reports a
  // failure, and the panel still shows what is true of the ledger.
  expect((await answered).status()).toBe(502);

  const inv7 = page.locator('[data-invariant="INV-7"]');
  await expect(inv7).toHaveAttribute("data-status", "failed", {
    timeout: 5_000,
  });
  await expect(page.locator(".demo-verdict")).toContainText("失败");
  const violation = page.locator(".demo-violations li", { hasText: "INV-7" });
  await expect(violation).toContainText("exceed captured amount");
  const payment = (await violation.innerText()).match(/payment (pay_\w+)/)?.[1];
  expect(payment, "the violation names the payment").toBeTruthy();
  await expect(
    violation.getByRole("link", { name: payment ?? "" }),
  ).toBeVisible();

  // The named payment really has refunds above its captured amount.
  const detail = await (await request.get(`/api/payments/${payment}`)).json();
  const refunded = (detail.refunds as { amount: number; status: string }[])
    .filter((r) => !["FAILED", "CANCELLED"].includes(r.status))
    .reduce((sum, r) => sum + r.amount, 0);
  expect(refunded).toBeGreaterThan(detail.amount);
});
