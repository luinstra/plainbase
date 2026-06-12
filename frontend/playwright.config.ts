import { defineConfig } from "@playwright/test";

/**
 * Smoke flow against the REAL server (installed distribution, embedded SPA, fixture
 * content tree) — not the Vite dev server. Prereq: `./gradlew :server:installDist`.
 * Invoked via `npm run smoke` or `./gradlew :frontend:smokeTest` (which handles the
 * prereq). Deliberately NOT part of `./gradlew build` — it needs a Playwright browser
 * download, which would break the hermetic JAR floor.
 */
export const SMOKE_PORT = 4378;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"]],
  use: {
    baseURL: `http://127.0.0.1:${SMOKE_PORT}`,
    browserName: "chromium",
  },
  webServer: {
    command: "node scripts/smoke-server.mjs",
    url: `http://127.0.0.1:${SMOKE_PORT}/healthz`,
    reuseExistingServer: !process.env.CI,
    stdout: "pipe",
    stderr: "pipe",
    timeout: 60_000,
  },
});
