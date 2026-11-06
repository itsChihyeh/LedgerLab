import { fileURLToPath } from "node:url";
import { defineConfig, devices } from "@playwright/test";

const scenario = process.env.LEDGERLAB_E2E_SCENARIO ?? "sample-a-1-3";
// The demo scenarios start the real demo-profile application with the simulator jar (P7), not the replay backend.
const demo = scenario.startsWith("demo");
// P9: the investigation service (stub model) runs next to the replay backend in this scenario only.
const agentPort = "8098";
const vitePort = process.env.E2E_VITE_PORT ?? "5173";

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
                  : scenario === "agent-workbench"
                    ? "agent.spec.ts"
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
  use: { baseURL: `http://localhost:${vitePort}`, trace: "retain-on-failure" },
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
    ...(scenario === "agent-workbench"
      ? [
          {
            // The service reads the live test backend over HTTP (never the database) and keeps its JSONL under test-results.
            command: `python ../agent/ledgerlab-agent-service.py --base-url http://127.0.0.1:8080 --model stub --port ${agentPort} --store test-results/${scenario}/investigations.jsonl`,
            url: `http://127.0.0.1:${agentPort}/agent/health`,
            reuseExistingServer: false,
          },
        ]
      : []),
    {
      command: "npm run dev",
      url: `http://localhost:${vitePort}`,
      reuseExistingServer: false,
      ...(scenario === "agent-workbench"
        ? { env: { VITE_AGENT_PORT: agentPort } }
        : {}),
    },
  ],
});
