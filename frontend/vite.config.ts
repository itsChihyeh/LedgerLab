import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";
import { sandboxReportPlugin } from "./sandbox-report.ts";

export default defineConfig({
  plugins: [
    react(),
    sandboxReportPlugin(
      fileURLToPath(new URL("../docs/runs", import.meta.url)),
    ),
  ],
  server: {
    host: "localhost",
    port: Number(process.env.E2E_VITE_PORT ?? "5173"),
    strictPort: true,
    proxy: {
      "/api": "http://localhost:8080",
      "/agent": `http://localhost:${process.env.VITE_AGENT_PORT ?? "8097"}`,
      ...(process.env.VITE_E2E_REFUNDS === "true"
        ? {
            "/__e2e__": {
              target: "http://localhost:8090",
              rewrite: (path: string) => path.replace(/^\/__e2e__/, ""),
            },
          }
        : {}),
    },
  },
  test: { include: ["src/**/*.test.ts", "src/**/*.test.tsx"] },
});
