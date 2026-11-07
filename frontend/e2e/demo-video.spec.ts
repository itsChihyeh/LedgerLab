import { fileURLToPath } from "node:url";
import { expect, test } from "@playwright/test";

// Scripted walkthrough for the demo video (AC-9.12, demo-and-delivery.md section 10). Not part of `npm run test:e2e`;
// run it with `npm run video:demo`. It only needs the page to behave, so the assertions are loose on purpose.
const ACTIONS = [
  "pay",
  "replay-webhook",
  "batch-first",
  "double-refund",
  "drop-refund-response",
  "bank-drop-receipt",
  "kill-worker",
  "bad-statement",
];
const PAUSE_MS = 2_000;
const output = fileURLToPath(
  new URL("../../docs/media/break-it-demo.webm", import.meta.url),
);

test("ac_9_12_recordDemoWalkthrough", async ({ page }) => {
  test.setTimeout(900_000);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "破坏它试试" })).toBeVisible();
  await expect(page.locator("[data-stream]")).toHaveAttribute(
    "data-stream",
    "open",
    { timeout: 30_000 },
  );
  await expect(page.locator('[data-invariant][data-status="ok"]')).toHaveCount(
    12,
  );
  await page.waitForTimeout(PAUSE_MS);

  for (const action of ACTIONS) {
    const answered = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/demo/actions/${action}`) &&
        response.request().method() === "POST",
      { timeout: 240_000 },
    );
    await page.locator(`[data-action="${action}"]`).click();
    expect((await answered).status(), action).toBe(200);
    await expect(
      page.locator('[data-invariant][data-status="ok"]'),
    ).toHaveCount(12, { timeout: 10_000 });
    await page.waitForTimeout(PAUSE_MS);
  }

  // End on the invariant panel and the counters.
  await page
    .getByRole("region", { name: "不变量面板" })
    .scrollIntoViewIfNeeded();
  await page.waitForTimeout(PAUSE_MS);
  await page.getByRole("region", { name: "计数卡片" }).scrollIntoViewIfNeeded();
  await page.waitForTimeout(PAUSE_MS * 2);

  // The video file only exists once the page is closed.
  const video = page.video();
  await page.close();
  await video?.saveAs(output);
});
