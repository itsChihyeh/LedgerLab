// PostToolUse (Edit|Write): format and safe-fix frontend files with Biome right after an edit.
// No-op until frontend/ has Biome installed.
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";

let raw = "";
for await (const chunk of process.stdin) raw += chunk;
const input = JSON.parse(raw || "{}");
const filePath = input.tool_input?.file_path;
if (!filePath) process.exit(0);

const projectDir = process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd();
const frontendDir = path.join(projectDir, "frontend");
const abs = path.resolve(projectDir, filePath);
const inFrontend = !path.relative(frontendDir, abs).startsWith("..");
const formattable = /\.(ts|tsx|js|jsx|mjs|cjs|json|jsonc|css)$/.test(abs);
const biome = path.join(frontendDir, "node_modules", ".bin", process.platform === "win32" ? "biome.cmd" : "biome");

if (!inFrontend || !formattable || !existsSync(biome)) process.exit(0);

try {
  execFileSync(biome, ["check", "--write", "--no-errors-on-unmatched", abs], {
    cwd: frontendDir,
    stdio: ["ignore", "ignore", "pipe"],
    shell: process.platform === "win32",
  });
} catch (e) {
  // Remaining lint errors are reported back to the agent but do not block the edit.
  process.stderr.write(String(e.stderr || e.message));
}
process.exit(0);
