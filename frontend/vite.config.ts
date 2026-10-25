import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  server: {
    host: "localhost",
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": "http://localhost:8080",
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
