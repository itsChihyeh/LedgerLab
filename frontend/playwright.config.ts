import { fileURLToPath } from "node:url";
import { defineConfig, devices } from "@playwright/test";

const scenario = process.env.LEDGERLAB_E2E_SCENARIO ?? "sample-a-1-3";

export default defineConfig({
  testMatch: scenario.startsWith("refund-")
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
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1440, height: 1000 },
      },
    },
  ],
  webServer: [
    {
      command: `${process.platform === "win32" ? ".\\mvnw.cmd" : "./mvnw"} -B spring-boot:test-run -Dspring-boot.test-run.main-class=dev.ledgerlab.TestLedgerLabApplication`,
      cwd: fileURLToPath(new URL("..", import.meta.url)),
      url: "http://localhost:8090/ready",
      timeout: 180_000,
      reuseExistingServer: false,
    },
    {
      command: "npm run dev",
      url: "http://localhost:5173",
      reuseExistingServer: false,
    },
  ],
});
