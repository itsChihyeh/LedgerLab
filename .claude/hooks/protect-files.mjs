// PreToolUse (Edit|Write|MultiEdit|NotebookEdit): block edits that would break reproducibility or leak secrets.
// - Flyway migrations already in HEAD are immutable (add a new migration instead)
// - .env secret files are never edited by the agent (.env.example is the template and stays editable)
// Fails closed: if the check itself cannot run, the edit is blocked.
// This hook only covers Claude's file tools; scripts/check-migrations.sh enforces the same rule
// in lefthook and CI for every agent and for shell edits.
import { execFileSync } from "node:child_process";
import path from "node:path";

function block(reason) {
  process.stderr.write(`Blocked: ${reason}\n`);
  process.exit(2);
}

try {
  let raw = "";
  for await (const chunk of process.stdin) raw += chunk;
  const input = JSON.parse(raw || "{}");
  const filePath = input.tool_input?.file_path ?? input.tool_input?.notebook_path;
  if (!filePath) process.exit(0);

  const projectDir = process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd();
  // Windows paths are case-insensitive, so compare lowercased POSIX-style paths.
  const rel = path.relative(projectDir, path.resolve(projectDir, filePath)).split(path.sep).join("/");
  const relLower = rel.toLowerCase();
  const base = path.posix.basename(relLower);

  if (base === ".env" || (base.startsWith(".env.") && base !== ".env.example")) {
    block(`${rel}: secrets file; edit it by hand outside the agent.`);
  }

  if (/(^|\/)db\/migration\/[^/]+\.sql$/.test(relLower)) {
    let committed;
    try {
      committed = execFileSync("git", ["ls-tree", "-r", "--name-only", "HEAD"], {
        cwd: projectDir,
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
      });
    } catch (e) {
      // A repository without any commit has nothing committed; anything else is a failed check.
      const msg = String(e.stderr || e.message);
      if (/not a valid object name|unknown revision|bad revision/i.test(msg)) process.exit(0);
      block(`${rel}: could not check whether this migration is committed (${msg.trim()}).`);
    }
    if (committed.split("\n").some((f) => f.toLowerCase() === relLower)) {
      block(`${rel}: this Flyway migration is already committed and must not change. Add a new V<n>__<desc>.sql migration instead.`);
    }
  }
  process.exit(0);
} catch (e) {
  block(`protect-files hook failed (${e.message}).`);
}
