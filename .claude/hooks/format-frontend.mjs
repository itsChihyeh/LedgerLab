// PostToolUse (Edit|Write|MultiEdit): format and safe-fix frontend files with Biome right after an edit.
// No-op until frontend/ has Biome installed.
// Biome runs through node with an argument array (no shell), so file names cannot inject commands.
// Remaining lint errors exit 2, which shows them to Claude; the edit itself has already happened.
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
const rel = path.relative(frontendDir, abs);
// On Windows a file on another drive yields an absolute "relative" path; treat that as outside.
const inFrontend = rel !== "" && !path.isAbsolute(rel) && rel !== ".." && !rel.startsWith(`..${path.sep}`);
const formattable = /\.(ts|tsx|js|jsx|mjs|cjs|json|jsonc|css)$/i.test(abs);
const biomeEntry = path.join(frontendDir, "node_modules", "@biomejs", "biome", "bin", "biome");

if (!inFrontend || !formattable || !existsSync(biomeEntry)) process.exit(0);

try {
  execFileSync(process.execPath, [biomeEntry, "check", "--write", "--no-errors-on-unmatched", abs], {
    cwd: frontendDir,
    stdio: ["ignore", "pipe", "pipe"],
    timeout: 60_000,
  });
} catch (e) {
  process.stderr.write(`Biome reported problems in ${rel}:\n${String(e.stdout || "")}${String(e.stderr || e.message)}`);
  process.exit(2);
}
process.exit(0);
