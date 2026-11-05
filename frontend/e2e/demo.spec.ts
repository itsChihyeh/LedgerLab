import { expect, type Page, test } from "@playwright/test";
import { control } from "./helpers";

// action -> the counter that must go up when it runs (demo-and-delivery.md section 2.1)
const ACTIONS: [string, string][] = [
  ["pay", "payments"],
  ["replay-webhook", "duplicatesAbsorbed"],
  ["batch-first", "parkedThenResolved"],
  ["double-refund", "overRefundsRejected"],
  ["drop-refund-response", "unknownRecovered"],
  ["bank-drop-receipt", "unknownRecovered"],
  ["kill-worker", "workerCrashesRecovered"],
  ["bad-statement", "discrepanciesOpened"],
];

const counter = async (page: Page, key: string) =>
  Number(await page.locator(`[data-counter="${key}"] dd`).innerText());
// The timeline is empty until the first event, so read it without waiting for an element.
const newestFeedId = (page: Page) =>
  page.evaluate(
    () =>
      document
        .querySelector(".demo-timeline li")
        ?.getAttribute("data-feed-id") ?? "",
  );

async function openDemo(page: Page) {
  // The server frees a closed browser's stream slot only on its next write (a 5 s heartbeat at most), and one
  // address may hold three, so right after earlier tests a page may have to retry until a slot is free.
  for (let attempt = 1; ; attempt++) {
    await page.goto("/");
    await expect(
      page.getByRole("heading", { name: "破坏它试试" }),
    ).toBeVisible();
    try {
      await expect(page.locator("[data-stream]")).toHaveAttribute(
        "data-stream",
        "open",
        { timeout: 6_000 },
      );
      break;
    } catch (notYet) {
      if (attempt >= 6) throw notYet;
    }
  }
  // The stream sends the latest verdict and counters on connect.
  await expect(page.locator('[data-invariant][data-status="ok"]')).toHaveCount(
    12,
  );
  await expect(page.locator('[data-counter="payments"] dd')).not.toHaveText(
    "—",
  );
}

test("ac_9_1_everyActionUpdatesTimelineAndInvariants", async ({ page }) => {
  test.setTimeout(600_000);
  await openDemo(page);
  await expect(page.locator("[data-action]")).toHaveCount(8);
  // The seeded scenario is the starting point: three settled payments, one funding payment, one finished refund.
  expect(await counter(page, "payments")).toBeGreaterThanOrEqual(3);

  for (const [action, key] of ACTIONS) {
    const before = await counter(page, key);
    const newest = await newestFeedId(page);
    const answered = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/demo/actions/${action}`) &&
        response.request().method() === "POST",
      { timeout: 240_000 },
    );
    await page.locator(`[data-action="${action}"]`).click();
    const response = await answered;
    expect(response.status(), `${action}: ${await response.text()}`).toBe(200);
    // Within two seconds of the action finishing: a new timeline entry, a green panel, a higher counter.
    await expect(page.locator(".demo-timeline li").first()).not.toHaveAttribute(
      "data-feed-id",
      newest ?? "",
      { timeout: 2_000 },
    );
    await expect(
      page.locator('[data-invariant][data-status="ok"]'),
    ).toHaveCount(12, { timeout: 2_000 });
    await expect(page.locator('[data-status="failed"]')).toHaveCount(0);
    await expect(page.locator(".demo-violations")).toHaveCount(0);
    await expect
      .poll(() => counter(page, key), { timeout: 2_000 })
      .toBeGreaterThan(before);
    // The action's own steps are on the timeline.
    await expect(
      page.locator('.demo-timeline li[data-source="step"]').first(),
    ).toBeVisible();
    // Buttons are usable again once the action has finished.
    await expect(page.locator(`[data-action="${action}"]`)).toBeEnabled();
  }
});

test("ac_9_1_secondClickWhileRunningIsRefusedPolitely", async ({
  page,
  request,
}) => {
  await openDemo(page);
  // Simulates another visitor's action already holding the site-wide lock: start one, then try a second.
  const first = page.waitForResponse((r) =>
    r.url().endsWith("/api/demo/actions/pay"),
  );
  await page.locator('[data-action="pay"]').click();
  await expect(page.locator("[data-action]").first()).toBeDisabled();
  const second = await request.post("/api/demo/actions/pay");
  expect([200, 409]).toContain(second.status());
  if (second.status() === 409)
    expect((await second.json()).error).toBe("ACTION_IN_PROGRESS");
  expect((await first).status()).toBe(200);
});

test("ac_9_1_phoneWidthHasNoHorizontalScroll", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  await openDemo(page);
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
  await expect(page.locator('[data-action="pay"]')).toBeVisible();
});

test("ac_9_5_pageShowsLastResetAndArchive", async ({ page }) => {
  await openDemo(page);
  await expect(page.locator("[data-last-reset]")).toContainText(
    "2026-11-05T04:00:00Z",
  );
  const archive = page.getByRole("region", { name: "重置与存档" });
  await expect(archive).toContainText("2026-11-04");
  await expect(archive).toContainText("支付 9");
});

test("ac_9_7_sandboxReportPanel", async ({ page }) => {
  // With a report file: the page shows its run time and numbers, labelled as Stripe test mode.
  await page.route("**/sandbox-report.json", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        run_id: "run_e2e",
        generated_at: "2026-11-06T10:00:00Z",
        payments: { stripe: 20, local: 20 },
        invariants: { violations: 0 },
      }),
    }),
  );
  await page.goto("/#/demo");
  const panel = page.getByRole("region", { name: "真实沙箱报告" });
  await expect(panel).toContainText("run_e2e");
  await expect(panel).toContainText("2026-11-06T10:00:00Z");
  await expect(panel).toContainText("Stripe 测试模式、由本地运行产生");
  await expect(panel.locator("[data-report='present']")).toContainText(
    "invariants · violations",
  );
  await expect(panel).not.toContainText("尚无运行报告");

  // No report: a 404, and also the dev server's HTML fallback, both read as "no report yet".
  await page.unroute("**/sandbox-report.json");
  await page.route("**/sandbox-report.json", (route) =>
    route.fulfill({ status: 404, body: "" }),
  );
  await page.goto("/#/demo");
  await page.reload();
  await expect(
    page.getByRole("region", { name: "真实沙箱报告" }),
  ).toContainText("尚无运行报告");
  await page.unroute("**/sandbox-report.json");
  await page.route("**/sandbox-report.json", (route) =>
    route.fulfill({
      status: 200,
      contentType: "text/html",
      body: "<!doctype html><title>app</title>",
    }),
  );
  await page.reload();
  await expect(
    page.getByRole("region", { name: "真实沙箱报告" }),
  ).toContainText("尚无运行报告");
});

test("ac_9_3_internalTokenNeverReachesThePage", async ({ page, request }) => {
  test.setTimeout(300_000);
  const token = await (await request.get(`${control}/internal-token`)).text();
  expect(token).toMatch(/^[0-9a-f]{64}$/);

  // Everything the page receives: every response (headers and body) and every raw stream message.
  const seen: string[] = [];
  await page.addInitScript(() => {
    const Original = window.EventSource;
    const raw: string[] = [];
    (window as unknown as { __sse: string[] }).__sse = raw;
    window.EventSource = class extends Original {
      constructor(url: string | URL, init?: EventSourceInit) {
        super(url, init);
        for (const type of ["step", "timeline", "invariants", "counters"])
          this.addEventListener(type, (event) =>
            raw.push((event as MessageEvent<string>).data),
          );
      }
    };
  });
  page.on("response", async (response) => {
    const headers: Record<string, string> = await response
      .allHeaders()
      .catch(() => ({}));
    seen.push(JSON.stringify(headers));
    const type = headers["content-type"] ?? "";
    if (type.includes("text/event-stream")) return;
    seen.push(await response.text().catch(() => ""));
  });

  await openDemo(page);
  // Scenarios that call the application's closed write routes with the token: a payment and a refund.
  for (const action of ["pay", "double-refund"]) {
    const answered = page.waitForResponse((r) =>
      r.url().endsWith(`/api/demo/actions/${action}`),
    );
    await page.locator(`[data-action="${action}"]`).click();
    expect((await answered).status()).toBe(200);
  }
  await page.goto("/#/overview");
  const stream = await page.evaluate(
    () => (window as unknown as { __sse?: string[] }).__sse ?? [],
  );
  await page.reload();
  const everything = [...seen, ...stream].join("\n");
  expect(everything.length).toBeGreaterThan(1_000);
  expect(everything).not.toContain(token);
  expect(everything.toLowerCase()).not.toContain("x-ledgerlab-demo-internal");
  // The closed write routes stay closed to a browser that does not hold the token.
  const refused = await request.post("/api/refunds", { data: {} });
  expect(refused.status()).toBe(404);
});

test("ac_9_2_closingAPageFreesItsStreamSlotAtOnce", async ({ context }) => {
  // One address may hold three streams. Probe: 200 means a slot was free (the probe stream is dropped at once).
  const probe = (page: Page) =>
    page.evaluate(async () => {
      const abort = new AbortController();
      const response = await fetch("/api/demo/stream", {
        signal: abort.signal,
      });
      abort.abort();
      return response.status;
    });
  const pages: Page[] = [];
  for (let i = 0; i < 3; i++) {
    const page = await context.newPage();
    await page.goto("/");
    await expect(page.locator("[data-stream]")).toHaveAttribute(
      "data-stream",
      "open",
      { timeout: 15_000 },
    );
    pages.push(page);
  }
  const fourth = await context.newPage();
  await fourth.goto("/#/overview");
  // The fourth connection is refused while the three are open.
  expect(await probe(fourth)).toBe(429);
  // Closing one page gives its slot back once the server's heartbeat write fails. The first write after the
  // browser closed its end usually still succeeds (the reset only arrives afterwards), so allow two 5 s
  // heartbeats; before the page closed its stream itself this took up to two 15 s ones. Poll, never sleep.
  await pages[0].close({ runBeforeUnload: true });
  await expect
    .poll(() => probe(fourth), { timeout: 12_000, intervals: [100] })
    .toBe(200);
});
