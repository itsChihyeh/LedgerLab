import { fileURLToPath } from "node:url";
import { defineConfig, devices } from "@playwright/test";

const scenario = process.env.LEDGERLAB_E2E_SCENARIO ?? "sample-a-1-3";
// The demo scenarios start the real demo-profile application with the simulator jar (P7), not the replay backend.
const demo = scenario.startsWith("demo");

export default defineConfig({
  testMatch:
    scenario === "demo"
      ? "demo.spec.ts"
      : scenario === "demo-video"
        ? "demo-video.spec.ts"
        : scenario === "demo-broken-lock"
          ? "demo-broken.spec.ts"
          : scenario === "payout-recovery"
            ? "payouts.spec.ts"
            : scenario === "correction-e"
              ? "corrections.spec.ts"
              : scenario.startsWith("refund-")
                ? "refunds.spec.ts"
                : scenario === "ac-5-3"
                  ? "recon.spec.ts"
                  : ["payments.spec.ts", "settlement.spec.ts"],
  outputDir: `test-results/${scenario}`,
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [
    ["list"],
    ["html", { open: "never", outputFolder: `playwright-report/${scenario}` }],
  ],
  use: { baseURL: "http://localhost:5173", trace: "retain-on-failure" },
  projects: [
    scenario === "demo-video"
      ? {
          // Recording project: only `npm run video:demo` selects this scenario.
          name: "demo-video",
          use: {
            ...devices["Desktop Chrome"],
            viewport: { width: 1280, height: 900 },
            video: { mode: "on", size: { width: 1280, height: 900 } },
          },
        }
      : {
          name: "chromium",
          use: {
            ...devices["Desktop Chrome"],
            viewport: { width: 1440, height: 1000 },
          },
        },
  ],
  webServer: [
    {
      command: `${process.platform === "win32" ? ".\\mvnw.cmd" : "./mvnw"} -B spring-boot:test-run -Dspring-boot.run.main-class=${demo ? "dev.ledgerlab.demo.internal.DemoE2eApplication" : "dev.ledgerlab.TestLedgerLabApplication"}`,
      cwd: fileURLToPath(new URL("..", import.meta.url)),
      url: "http://localhost:8090/ready",
      timeout: demo ? 300_000 : 180_000,
      reuseExistingServer: false,
    },
    {
      command: "npm run dev",
      url: "http://localhost:5173",
      reuseExistingServer: false,
    },
  ],
});
