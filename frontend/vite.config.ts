/// <reference types="vitest/config" />
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Dev mode proxies the API surfaces to a locally running server; the SPA handles
    // /docs/* and /p/* itself (vite's history fallback serves index.html for them).
    proxy: {
      "/api": "http://localhost:8080",
      "/assets/": "http://localhost:8080",
      "/browse": "http://localhost:8080",
      "/healthz": "http://localhost:8080",
    },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["src/test-setup.ts"],
  },
});
