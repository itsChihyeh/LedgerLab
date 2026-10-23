import { spawnSync } from "node:child_process";

// Separate launches give S3 and AC-5.7 their own real PostgreSQL database.
for (const scenario of ["sample-a-1-3", "ac-5-3"]) {
  const result = spawnSync(
    process.execPath,
    ["node_modules/@playwright/test/cli.js", "test", ...process.argv.slice(2)],
    {
      stdio: "inherit",
      env: { ...process.env, LEDGERLAB_E2E_SCENARIO: scenario },
    },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
