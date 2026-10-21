// Stop: run Spotless once when Java files are uncommitted.
// Maven start-up takes seconds, so this runs at the end of a turn rather than after every edit.
// No-op until pom.xml configures Spotless. This is a convenience; CI's `spotless:check` is the gate.
// On failure it exits 2 so Claude sees the error and keeps working; stop_hook_active prevents a loop.
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

// -uall lists files inside new directories; -z avoids quoting of paths with spaces.
const status = execFileSync("git", ["status", "--porcelain", "-uall", "-z"], { cwd: projectDir, encoding: "utf8" });
const changedJava = status.split("\0").some((entry) => /\.java$/i.test(entry));
if (!changedJava) process.exit(0);

const win = process.platform === "win32";
try {
  execFileSync(win ? "mvnw.cmd" : "./mvnw", ["-q", "spotless:apply"], {
    cwd: projectDir,
    stdio: ["ignore", "pipe", "pipe"],
    // mvnw.cmd is a batch file; Node only runs those through a shell. The arguments are fixed strings.
    shell: win,
    timeout: 150_000,
  });
} catch (e) {
  const why = e.code === "ETIMEDOUT" ? "timed out after 150 s" : String(e.stderr || e.stdout || e.message);
  process.stderr.write(`spotless:apply failed (${why}). Run it yourself and check the result before finishing.\n`);
  process.exit(2);
}
process.exit(0);
