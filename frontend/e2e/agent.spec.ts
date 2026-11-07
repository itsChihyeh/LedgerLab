import { expect, type Page, test } from "@playwright/test";
import type { IssueDetail, IssueList } from "../src/api";
import { backend, control } from "./helpers";

// P9-T04: the investigation panel against the real test backend and the real service (stub model).
// Tests run in order and share one database: the split deposit is adopted first, then the fee difference.
test.describe.configure({ mode: "serial" });

const STAND_IN = "本地替身（规则基线），不代表大模型能力";

/** Every non-GET request the browser sends, split by who receives it. */
function recordWrites(page: Page) {
  const main: string[] = [];
  const agent: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "GET") return;
    const path = new URL(request.url()).pathname;
    const line = `${request.method()} ${path}`;
    if (path.startsWith("/agent/")) agent.push(line);
    else main.push(line);
  });
  return { main, agent };
}

// Writes that reached the main application across the adoption tests (AC-11.6).
const mainWrites: string[] = [];

async function issues(
  request: import("@playwright/test").APIRequestContext,
  category: string,
  status = "OPEN",
) {
  const response = await request.get(
    `${backend}/api/recon/issues?category=${category}&status=${status}&limit=100`,
  );
  expect(response.ok(), await response.text()).toBeTruthy();
  return ((await response.json()) as IssueList).issues;
}

async function detail(
  request: import("@playwright/test").APIRequestContext,
  id: string,
) {
  const response = await request.get(`${backend}/api/recon/issues/${id}`);
  expect(response.ok()).toBeTruthy();
  return (await response.json()) as IssueDetail;
}

test.beforeAll(async ({ playwright }) => {
  const api = await playwright.request.newContext();
  try {
    // The manifest ends at 2026-11-13T12:00Z with the split deposit imported; run reconciliation once at the
    // ac-5-3 "second" checkpoint (evidence 13:00Z) so the two DEPOSIT_AMOUNT_MISMATCH issues exist.
    const clock = await api.post(`${control}/advance-clock`, { data: "3720" });
    expect(clock.ok(), await clock.text()).toBeTruthy();
    const run = await api.post(`${backend}/api/recon/runs`, {
      data: {
        kind: "ALL",
        dryRun: false,
        cutoffAt: "2026-11-13T05:00:00Z",
        evidenceAsOf: "2026-11-13T13:00:00Z",
      },
    });
    expect(run.ok(), await run.text()).toBeTruthy();
    expect(await issues(api, "DEPOSIT_AMOUNT_MISMATCH")).toHaveLength(2);
    expect(await issues(api, "FEE_MISMATCH")).toHaveLength(1);
  } finally {
    await api.dispose();
  }
});

test("ac_11_6 investigate a split deposit, adopt it as a correction, nothing is written before submit", async ({
  page,
  request,
}) => {
  const writes = recordWrites(page);
  const [issue, sibling] = await issues(request, "DEPOSIT_AMOUNT_MISMATCH");
  await page.goto(`/#/recon/${issue.id}`);
  const panel = page.getByRole("region", { name: "调查助手" });
  await expect(panel).toBeVisible();
  await expect(panel).toContainText("还没有调查记录");
  await panel.getByRole("button", { name: "让 Agent 调查" }).click();
  await expect(panel).toContainText("BANK_SPLIT_DEPOSIT");
  await expect(panel).toContainText(STAND_IN);
  await expect(panel).toContainText("stub");
  const citations = panel.locator(".agent-citations a");
  await expect(citations.first()).toBeVisible();
  const bankLink = panel.getByRole("link", { name: /^bank_line · / });
  expect(await bankLink.count()).toBeGreaterThanOrEqual(2);
  await page.screenshot({
    path: test.info().outputPath("split-investigated.png"),
    fullPage: true,
  });

  // A citation leads to the existing bank line page, and back restores the panel with the stored run.
  const bankId = (await bankLink.first().innerText()).replace(
    "bank_line · ",
    "",
  );
  await bankLink.first().click();
  await expect(
    page.getByRole("heading", { name: "银行流水详情" }),
  ).toBeVisible();
  await expect(page.locator("dl.summary")).toContainText(bankId);
  await page.goBack();
  await expect(panel).toContainText("BANK_SPLIT_DEPOSIT");

  // Opening the adoption form sends nothing: still only the one investigation request.
  expect(writes.agent).toEqual(["POST /agent/investigations"]);
  expect(writes.main).toEqual([]);
  await panel.getByRole("button", { name: "采纳", exact: true }).click();
  const form = page.getByRole("form", { name: "采纳调查结论" });
  await expect(form).toBeVisible();
  await expect(form).toContainText("APPLY_UNAPPLIED_CASH");
  await expect(form).toContainText("SPLIT_DEPOSIT");
  await expect(form.getByLabel("备注（可编辑）")).toHaveValue(/^采纳调查 inv_/);
  expect(writes.agent).toEqual(["POST /agent/investigations"]);
  expect(writes.main).toEqual([]);

  await form.getByLabel("操作人").fill("e2e-operator");
  await page.screenshot({
    path: test.info().outputPath("split-adoption-form.png"),
    fullPage: true,
  });
  await form.getByRole("button", { name: "提交" }).click();
  await expect(page.locator("dl.summary").first()).toContainText("RESOLVED");
  expect(writes.main).toEqual(["POST /api/corrections"]);
  expect(writes.agent).toEqual(["POST /agent/investigations"]);
  mainWrites.push(...writes.main);

  // After a reload the issue is resolved and links to the correction recorded in the main application.
  await page.reload();
  await expect(page.locator("dl.summary").first()).toContainText("RESOLVED");
  const resolved = await detail(request, issue.id);
  const correctionId = resolved.issue.resolutionCorrectionId;
  expect(correctionId).toBeTruthy();
  await page.locator(`a[href="#/corrections/${correctionId}"]`).click();
  await expect(page.getByRole("heading", { name: "纠错详情" })).toBeVisible();
  await expect(page.locator("dl.summary")).toContainText("采纳调查 inv_");
  await expect(page.locator("dl.summary")).toContainText("e2e-operator");
  await page.screenshot({
    path: test.info().outputPath("split-correction.png"),
    fullPage: true,
  });
  const note = await page
    .locator("dl.summary div")
    .filter({ hasText: "说明" })
    .locator("dd")
    .innerText();
  expect(note.startsWith("采纳调查 inv_")).toBeTruthy();

  // ADR-0011 3: the batch's other split issue closed with the same correction.
  const other = await detail(request, sibling.id);
  expect(other.issue.status).toBe("RESOLVED");
  expect(other.issue.resolutionCorrectionId).toBe(correctionId);
});

test("ac_11_6 investigate a fee difference and adopt it as an accepted issue", async ({
  page,
  request,
}) => {
  const writes = recordWrites(page);
  const [issue] = await issues(request, "FEE_MISMATCH");
  await page.goto(`/#/recon/${issue.id}`);
  const panel = page.getByRole("region", { name: "调查助手" });
  await panel.getByRole("button", { name: "让 Agent 调查" }).click();
  await expect(panel).toContainText("PROCESSOR_FEE_DIFFERS");
  await expect(panel).toContainText(STAND_IN);
  await panel.getByRole("button", { name: "采纳", exact: true }).click();
  const form = page.getByRole("form", { name: "采纳调查结论" });
  await expect(form.getByLabel("接受原因（可编辑）")).toHaveValue(
    /^采纳调查 inv_/,
  );
  expect(writes.agent).toEqual(["POST /agent/investigations"]);
  expect(writes.main).toEqual([]);
  await page.screenshot({
    path: test.info().outputPath("fee-adoption-form.png"),
    fullPage: true,
  });
  await form.getByRole("button", { name: "提交" }).click();
  await expect(page.locator("dl.summary").first()).toContainText("ACCEPTED");
  expect(writes.main).toEqual([`POST /api/recon/issues/${issue.id}/accept`]);
  mainWrites.push(...writes.main);

  await page.reload();
  await expect(page.locator("dl.summary").first()).toContainText("ACCEPTED");
  await expect(page.getByRole("table", { name: "差异状态历史" })).toContainText(
    "采纳调查 inv_",
  );
  const after = await detail(request, issue.id);
  expect(after.issue.status).toBe("ACCEPTED");
  expect(after.history.at(-1)?.explanation).toMatch(/^采纳调查 inv_/);

  // AC-11.6: across both adoptions the main application received exactly these two writes.
  expect(mainWrites).toEqual([
    "POST /api/corrections",
    `POST /api/recon/issues/${issue.id}/accept`,
  ]);
});

test("ac_11_5 the rest of the issue page works while the investigation service is down", async ({
  page,
  request,
}) => {
  await page.route("**/agent/**", (route) => route.abort());
  const [issue] = await issues(request, "BATCH_TOTAL_MISMATCH");
  await page.goto(`/#/recon/${issue.id}`);
  const panel = page.getByRole("region", { name: "调查助手" });
  await expect(panel).toContainText("调查助手未启动");
  await expect(
    panel.getByRole("button", { name: "让 Agent 调查" }),
  ).toHaveCount(0);
  await expect(page.getByRole("table", { name: "金额比较" })).toContainText(
    "25096 分",
  );
  await expect(page.getByRole("heading", { name: "关联证据" })).toBeVisible();
  await expect(page.locator(".evidence-links a").first()).toBeVisible();
  await page.getByLabel("接受原因").fill("服务不在时仍可人工处理");
  await expect(
    page.getByRole("button", { name: "接受", exact: true }),
  ).toBeEnabled();
  await page.screenshot({
    path: test.info().outputPath("service-down.png"),
    fullPage: true,
  });
});
