import { expect, test } from "@playwright/test";
import type { IssueDetail, IssueList, Overview } from "../src/api";
import { backend } from "./helpers";

test("ac_5_15 trace B1 discrepancy and accept with a reason", async ({
  page,
  request,
}) => {
  const list: IssueList = await (
    await request.get(`${backend}/api/recon/issues?limit=100`)
  ).json();
  expect(list.issues).toHaveLength(7);
  const issue = list.issues.find((i) => i.category === "BATCH_TOTAL_MISMATCH");
  expect(issue).toBeDefined();
  const id = issue?.id ?? "";
  const d: IssueDetail = await (
    await request.get(`${backend}/api/recon/issues/${id}`)
  ).json();
  for (const refs of [
    d.references.statementLines,
    d.references.eventIds,
    d.references.entryIds,
    d.references.bankTxnIds,
  ])
    expect(refs.length).toBeGreaterThan(0);
  const before: Overview = await (
    await request.get(`${backend}/api/overview`)
  ).json();
  await page.goto("/#/");
  await page
    .getByRole("link", { name: "BATCH_TOTAL_MISMATCH · 1", exact: true })
    .click();
  await expect(
    page.getByRole("combobox", { name: "状态", exact: true }),
  ).toHaveValue("OPEN");
  await expect(page.getByLabel("类别", { exact: true })).toHaveValue(
    "BATCH_TOTAL_MISMATCH",
  );
  await expect(
    page
      .getByRole("table", { name: "差异列表（最近 100 条）" })
      .locator("tbody tr"),
  ).toHaveCount(1);
  await page
    .getByRole("link", { name: "BATCH_TOTAL_MISMATCH", exact: true })
    .click();
  const amounts = page.getByRole("table", { name: "金额比较" });
  for (const text of ["25096 分", "23639 分", "CAD 14.57"])
    await expect(amounts).toContainText(text);
  await expect(page.getByRole("table", { name: "差异状态历史" })).toContainText(
    "OPEN",
  );
  await expect(
    page.getByRole("heading", { name: `各次发现（${d.findings.length} 次）` }),
  ).toBeVisible();
  const finding = page
    .locator("details")
    .filter({ hasText: d.findings[0].runId })
    .first();
  await finding.locator("summary").click();
  await expect(finding).toContainText("25096");
  await page.screenshot({ path: "e2e-artifacts/issue.png", fullPage: true });
  // Follow every returned evidence reference, asserting the target's actual identity/content.
  for (const line of d.references.statementLines) {
    await page
      .getByRole("link", {
        name: `批次行 ${line.statementLineId} · ${line.lineSeq}`,
        exact: true,
      })
      .click();
    await expect(
      page.getByRole("heading", { name: "批次行证据" }),
    ).toBeVisible();
    await expect(page.locator("dl.summary")).toContainText(
      line.statementLineId,
    );
    await page.goBack();
  }
  for (const event of d.references.eventIds) {
    await page
      .getByRole("link", { name: `原事件 ${event}`, exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "原事件摘要" }),
    ).toBeVisible();
    await expect(page.locator("dl.summary")).toContainText(event);
    await expect(page.getByText("事件脱敏摘要", { exact: true })).toBeVisible();
    await page.goBack();
  }
  for (const entry of d.references.entryIds) {
    await page
      .getByRole("link", { name: `posting ${entry}`, exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "相关 posting" }),
    ).toBeVisible();
    const rows = page.getByRole("table").locator("tbody tr");
    expect(await rows.count()).toBeGreaterThan(1);
    for (const row of await rows.all()) await expect(row).toContainText(entry);
    await page.goBack();
  }
  for (const bank of d.references.bankTxnIds) {
    await page
      .getByRole("link", { name: `银行流水行 ${bank}`, exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "银行流水详情" }),
    ).toBeVisible();
    await expect(page.locator("dl.summary")).toContainText(bank);
    await page.goBack();
  }
  const accept = page.getByRole("button", { name: "接受", exact: true });
  await expect(accept).toBeDisabled();
  await page.getByLabel("接受原因").fill("   ");
  await expect(accept).toBeDisabled();
  const reason = "已核对合成批次证据，接受演示差异";
  await page.getByLabel("接受原因").fill(reason);
  await accept.click();
  await expect(page.locator("dl.summary")).toContainText("ACCEPTED");
  await expect(page.getByRole("table", { name: "差异状态历史" })).toContainText(
    reason,
  );
  await page.reload();
  await expect(page.locator("dl.summary")).toContainText("ACCEPTED");
  const after: IssueDetail = await (
    await request.get(`${backend}/api/recon/issues/${id}`)
  ).json();
  expect(after.findings).toEqual(d.findings);
  expect(after.history).toHaveLength(d.history.length + 1);
  const next: Overview = await (
    await request.get(`${backend}/api/overview`)
  ).json();
  expect(next.accounts).toEqual(before.accounts);
  expect(next.openIssuesByCategory.BATCH_TOTAL_MISMATCH).toBeUndefined();
  expect(next.asOf.seq).toBe(before.asOf.seq);
  await page.goto("/#/recon");
  await page
    .getByRole("combobox", { name: "状态", exact: true })
    .selectOption("ACCEPTED");
  await page.getByRole("button", { name: "筛选" }).click();
  await page
    .getByRole("link", { name: "BATCH_TOTAL_MISMATCH", exact: true })
    .click();
  await expect(page.locator("dl.summary")).toContainText("ACCEPTED");
  await page.goto("/#/");
  await expect(
    page.getByRole("link", { name: "BATCH_TOTAL_MISMATCH · 1", exact: true }),
  ).toHaveCount(0);
});

test("ac_5_15 missing evidence and empty filter are explicit", async ({
  page,
}) => {
  for (const path of [
    "batches/missing",
    "events/missing",
    "bank-lines/missing",
    "statement-lines/missing/1",
    "recon/missing",
  ]) {
    await page.goto(`/#/${path}`);
    await expect(page.getByRole("alert")).toHaveText("找不到这条记录");
  }
  await page.goto("/#/recon?status=RESOLVED");
  await expect(page.getByRole("table")).toContainText("暂无记录");
});
