// PreToolUse (Edit|Write): block edits that would break reproducibility or leak secrets.
// - committed Flyway migrations are immutable (add a new migration instead)
// - .env files hold secrets and are never edited by the agent
import { execFileSync } from "node:child_process";
import path from "node:path";

let raw = "";
for await (const chunk of process.stdin) raw += chunk;
const input = JSON.parse(raw || "{}");
const filePath = input.tool_input?.file_path;
if (!filePath) process.exit(0);

const projectDir = process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd();
const rel = path.relative(projectDir, path.resolve(projectDir, filePath)).split(path.sep).join("/");
const base = path.posix.basename(rel);

function block(reason) {
  process.stderr.write(`Blocked: ${rel}: ${reason}\n`);
  process.exit(2);
}

if (base.startsWith(".env") && base !== ".env.example") {
  block("secrets file; edit it by hand outside the agent.");
}

if (/(^|\/)db\/migration\/.+\.sql$/.test(rel)) {
  let tracked = false;
  try {
    execFileSync("git", ["ls-files", "--error-unmatch", rel], { cwd: projectDir, stdio: "ignore" });
    tracked = true;
  } catch {
    tracked = false;
  }
  if (tracked) {
    block("this Flyway migration is already committed and must not change. Add a new V<n>__<desc>.sql migration instead.");
  }
}

process.exit(0);
