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
    // NEVER reuse an already-running server (even locally). edit.spec.ts MUTATES content via PUT/POST;
    // `smoke-server.mjs` always boots against a throwaway `.smoke-content` copy of fixtures/demo-docs, so
    // a fresh boot guarantees writes can't land on a foreign server (e.g. a dev `plainbase serve` over real
    // docs) squatting on this port. With reuse enabled a foreign server would silently absorb the writes;
    // refusing reuse makes Playwright start its OWN isolated server (or fail loudly on a port clash) instead.
    reuseExistingServer: false,
    stdout: "pipe",
    stderr: "pipe",
    timeout: 60_000,
  },
});
