import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";

// The demo scenarios run the real simulator jar as a child process of the test backend (P7).
const simulatorJar =
  "../../simulator/target/ledgerlab-simulator-0.0.1-SNAPSHOT.jar";
if (!existsSync(new URL(simulatorJar, import.meta.url))) {
  const wrapper = process.platform === "win32" ? ".\\mvnw.cmd" : "./mvnw";
  const built = spawnSync(
    wrapper,
    ["-B", "-f", "simulator/pom.xml", "-DskipTests", "package"],
    { stdio: "inherit", cwd: new URL("../..", import.meta.url), shell: true },
  );
  if (built.status !== 0) process.exit(built.status ?? 1);
}

// `npm run test:e2e -- --scenario=demo` runs one scenario; the rest of the arguments go to Playwright.
const only = process.argv
  .slice(2)
  .find((arg) => arg.startsWith("--scenario="))
  ?.slice("--scenario=".length);
const passed = process.argv
  .slice(2)
  .filter((arg) => !arg.startsWith("--scenario="));

// Scenarios that only run when asked for by name (never in the default `npm run test:e2e`).
const optional = ["demo-video"];

// Separate launches give S3 and AC-5.7 their own real PostgreSQL database.
for (const scenario of [
  "sample-a-1-3",
  "ac-5-3",
  "refund-concurrent-6000",
  "refund-concurrent-5000",
  "refund-retry",
  "correction-e",
  "payout-recovery",
  "agent-workbench",
  "demo",
  "demo-broken-lock",
  ...optional,
].filter((name) => (only ? name === only : !optional.includes(name)))) {
  const result = spawnSync(
    process.execPath,
    ["node_modules/@playwright/test/cli.js", "test", ...passed],
    {
      stdio: "inherit",
      env: {
        ...process.env,
        LEDGERLAB_E2E_SCENARIO: scenario,
        VITE_E2E_REFUNDS: "true",
        VITE_SIMULATOR_MODE: "true",
      },
    },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
