import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import type { Plugin } from "vite";

const TIME_KEYS = [
  "generated_at",
  "generatedAt",
  "finished_at",
  "finishedAt",
  "started_at",
  "startedAt",
];

/**
 * The newest redacted sandbox report in `dir` (docs/runs/sandbox-*.json), as its text, or null when there is
 * none. Newest means the time recorded inside the report; a report without a readable time falls back to the
 * file's modification time. A file that is not a JSON object is skipped.
 */
export function latestSandboxReport(dir: string): string | null {
  let names: string[];
  try {
    names = readdirSync(dir).filter((name) => /^sandbox-.+\.json$/.test(name));
  } catch {
    return null;
  }
  let best: { text: string; time: number; name: string } | null = null;
  for (const name of names) {
    const file = join(dir, name);
    try {
      const text = readFileSync(file, "utf8");
      const json: unknown = JSON.parse(text);
      if (typeof json !== "object" || json === null || Array.isArray(json))
        continue;
      const recorded = TIME_KEYS.map((key) =>
        Date.parse(String((json as Record<string, unknown>)[key] ?? "")),
      ).find((time) => !Number.isNaN(time));
      const time = recorded ?? statSync(file).mtimeMs;
      if (!best || time > best.time || (time === best.time && name > best.name))
        best = { text, time, name };
    } catch {
      // Unreadable or not JSON: not a report.
    }
  }
  return best ? best.text : null;
}

/**
 * Copies the newest sandbox report into the build as `/sandbox-report.json` and serves it from the dev server
 * too. With no report there is no file at all, and the demo page says so (AC-9.7).
 */
export function sandboxReportPlugin(dir: string): Plugin {
  return {
    name: "ledgerlab-sandbox-report",
    configureServer(server) {
      server.middlewares.use("/sandbox-report.json", (_request, response) => {
        const text = latestSandboxReport(dir);
        if (text === null) {
          response.statusCode = 404;
          response.end();
          return;
        }
        response.setHeader("Content-Type", "application/json");
        response.end(text);
      });
    },
    generateBundle() {
      const text = latestSandboxReport(dir);
      if (text !== null)
        this.emitFile({
          type: "asset",
          fileName: "sandbox-report.json",
          source: text,
        });
    },
  };
}
