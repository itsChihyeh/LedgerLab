import { expect, test } from "@playwright/test";
import type { CorrectionDetail, IssueList } from "../src/api";
import { backend } from "./helpers";

test("ac_6_14 ac_6_5 sample E links B6 issue to reversal, restatement and statement evidence", async ({
  page,
  request,
}) => {
  const response = await request.get(
    `${backend}/api/recon/issues?status=RESOLVED&limit=100`,
  );
  expect(response.ok()).toBeTruthy();
  const { issues } = (await response.json()) as IssueList;
  const issue = issues.find((i) => i.subjectId === "po_t07_b6");
  expect(issue?.resolutionCorrectionId).toBeTruthy();
  if (!issue?.resolutionCorrectionId)
    throw new Error("Missing sample E correction");
  const id = issue.resolutionCorrectionId;
  await page.goto(`/#/recon/${issue.id}`);
  await expect(page.getByRole("heading", { name: "差异详情" })).toBeVisible();
  await page.getByRole("link", { name: id, exact: true }).click();
  await expect(page.getByRole("heading", { name: "纠错详情" })).toBeVisible();
  await expect(
    page.getByRole("region", { name: "REVERSAL", exact: true }),
  ).toContainText("CAD 45.00");
  await expect(
    page.getByRole("region", { name: "CORRECTION", exact: true }),
  ).toContainText("CAD 54.00");
  await expect(
    page
      .locator("dl > div")
      .filter({ has: page.getByText("支付金额前后值", { exact: true }) }),
  ).toHaveText("支付金额前后值CAD 45.00 → CAD 54.00");
  await expect(
    page.getByRole("table", { name: "受影响对象（当前状态）" }),
  ).toContainText("SETTLED");
  await expect(
    page.getByRole("table", { name: "受影响对象（当前状态）" }),
  ).toContainText("CONFIRMED");
  await expect(
    page.getByRole("table", { name: "受影响对象（当前状态）" }),
  ).toContainText("RESOLVED");
  await expect(page.locator("form, button")).toHaveCount(0);
  await page.screenshot({
    path: test.info().outputPath("correction-detail.png"),
    fullPage: true,
  });
  await page.getByRole("link", { name: /^结算单行 ·/ }).click();
  await expect(page.getByRole("heading", { name: "批次行证据" })).toBeVisible();
  await expect(page.locator("dl")).toContainText("txn_t07_b6");
  await expect(page.locator("dl")).toContainText("CAD 54.00");
  await page.goto(`/#/corrections/${id}`);
  await page.getByRole("link", { name: "原事件 · evt_t07_p5" }).click();
  await expect(page.getByRole("heading", { name: "原事件摘要" })).toBeVisible();
  await page.goto(`/#/payments/ch_t07_p5`);
  const history = page.getByRole("region", { name: "支付纠错历史" });
  await expect(history).toContainText("CAD 45.00 → CAD 54.00");
  await expect(history).toContainText(`已被 ${id} 冲销`);
  const detailResponse = await request.get(`${backend}/api/corrections/${id}`);
  const detail = (await detailResponse.json()) as CorrectionDetail;
  await history
    .getByRole("link", { name: detail.targets[0].entryId, exact: true })
    .click();
  await expect(page.getByRole("heading", { name: "分录详情" })).toBeVisible();
  await expect(page.getByText(`已被 ${id} 冲销`)).toBeVisible();
  await page.goto("/#/batches/po_t07_b6");
  await expect(
    page
      .getByRole("table", { name: "批次状态历史" })
      .getByRole("link", { name: id, exact: true })
      .first(),
  ).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("link", { name: "纠错记录" })
    .click();
  await expect(
    page
      .getByRole("table", { name: "纠错记录" })
      .getByRole("link", { name: id, exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: test.info().outputPath("corrections-list.png"),
    fullPage: true,
  });
});
