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
    // Keep the per-test ceiling strictly ABOVE Testing-Library's `asyncUtilTimeout` (5000ms, set in
    // test-setup.ts). At the vitest default (also 5000ms) the two ceilings collide: a slow-but-correct
    // `waitFor` on a contended CI runner races the test timeout, which wins and surfaces the opaque
    // "Test timed out in 5000ms" instead of `waitFor`'s own assertion message. With the test ceiling
    // higher, a legitimately slow settle passes, while a genuinely broken assertion still fails fast at
    // the 5000ms `waitFor` ceiling with a useful message.
    testTimeout: 15000,
    hookTimeout: 15000,
  },
});
