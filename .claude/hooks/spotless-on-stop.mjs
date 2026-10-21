// Stop: run Spotless once per turn when Java files changed.
// Maven start-up takes seconds, so this runs at the end of a turn rather than after every edit.
// No-op until pom.xml configures Spotless.
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

let raw = "";
for await (const chunk of process.stdin) raw += chunk;
const input = JSON.parse(raw || "{}");
if (input.stop_hook_active) process.exit(0);

const projectDir = process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd();
const pom = path.join(projectDir, "pom.xml");
if (!existsSync(pom) || !readFileSync(pom, "utf8").includes("spotless")) process.exit(0);

const status = execFileSync("git", ["status", "--porcelain"], { cwd: projectDir, encoding: "utf8" });
if (!/\.java$/m.test(status)) process.exit(0);

const mvnw = process.platform === "win32" ? "mvnw.cmd" : "./mvnw";
try {
  execFileSync(mvnw, ["-q", "spotless:apply"], {
    cwd: projectDir,
    stdio: ["ignore", "ignore", "pipe"],
    shell: process.platform === "win32",
  });
} catch (e) {
  process.stderr.write(`spotless:apply failed: ${String(e.stderr || e.message)}\n`);
}
process.exit(0);
