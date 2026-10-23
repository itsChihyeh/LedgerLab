import { fileURLToPath } from "node:url";
import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
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
      url: "http://localhost:8080/api/payments",
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
