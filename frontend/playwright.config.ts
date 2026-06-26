import { defineConfig } from "@playwright/test";

/**
 * Smoke flow against the REAL server (installed distribution, embedded SPA, fixture
 * content tree) — not the Vite dev server. Prereq: `./gradlew :server:installDist`.
 * Invoked via `npm run smoke` or `./gradlew :frontend:smokeTest` (which handles the
 * prereq). Deliberately NOT part of `./gradlew build` — it needs a Playwright browser
 * download, which would break the hermetic JAR floor.
 *
 * P4 runs TWO servers (smoke-server.mjs is parameterized by SMOKE_PORT/SMOKE_AUTH):
 *  - the OPEN server (auth.mode=off, port {@link SMOKE_PORT}) — the existing read/write specs,
 *    which read/write ANONYMOUSLY (builtin would deny them — PolicyService denies Anonymous in
 *    enforced mode), run here in the "open" project.
 *  - the AUTH server (builtin ENFORCED, port {@link AUTH_PORT}, seeded with a bootstrap + agent
 *    token) — review.spec.ts runs in the "auth" project (the ci-runs-auth-off-blind lesson).
 */
export const SMOKE_PORT = 4378;
export const AUTH_PORT = 4379;

const webServerDefaults = { reuseExistingServer: false, stdout: "pipe", stderr: "pipe", timeout: 60_000 } as const;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"]],
  use: {
    browserName: "chromium",
  },
  projects: [
    // Every existing spec EXCEPT review.spec.ts → the open (auth-off) server.
    { name: "open", testIgnore: /review\.spec\.ts/, use: { baseURL: `http://127.0.0.1:${SMOKE_PORT}` } },
    // The enforced-builtin approval flow → the seeded auth server.
    { name: "auth", testMatch: /review\.spec\.ts/, use: { baseURL: `http://127.0.0.1:${AUTH_PORT}` } },
  ],
  webServer: [
    {
      // NEVER reuse an already-running server (even locally). edit.spec.ts MUTATES content via PUT/POST;
      // `smoke-server.mjs` always boots against a throwaway `.smoke-content-*` copy of fixtures/demo-docs, so
      // a fresh boot guarantees writes can't land on a foreign server squatting on this port. With reuse
      // enabled a foreign server would silently absorb the writes; refusing reuse makes Playwright start its
      // OWN isolated server (or fail loudly on a port clash) instead.
      command: "node scripts/smoke-server.mjs",
      env: { SMOKE_PORT: String(SMOKE_PORT), SMOKE_AUTH: "off" },
      url: `http://127.0.0.1:${SMOKE_PORT}/healthz`,
      ...webServerDefaults,
    },
    {
      command: "node scripts/smoke-server.mjs",
      env: { SMOKE_PORT: String(AUTH_PORT), SMOKE_AUTH: "builtin" },
      url: `http://127.0.0.1:${AUTH_PORT}/healthz`,
      ...webServerDefaults,
    },
  ],
});
