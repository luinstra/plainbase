import { defineConfig } from "@playwright/test";

/**
 * Smoke flow against the REAL server (installed distribution, embedded SPA, fixture
 * content tree) — not the Vite dev server. Prereq: `./gradlew :server:installDist`.
 * Invoked via `npm run smoke` or `./gradlew :frontend:smokeTest` (which handles the
 * prereq). Deliberately NOT part of `./gradlew build` — it needs a Playwright browser
 * download, which would break the hermetic JAR floor.
 *
 * FOUR servers (smoke-server.mjs is parameterized by SMOKE_PORT/SMOKE_AUTH/SMOKE_ROOTS):
 *  - the OPEN server (auth.mode=off, port {@link SMOKE_PORT}) — the existing read/write specs,
 *    which read/write ANONYMOUSLY (builtin would deny them — PolicyService denies Anonymous in
 *    enforced mode), run here in the "open" project.
 *  - the AUTH server (builtin ENFORCED, port {@link AUTH_PORT}, seeded with a bootstrap + agent
 *    token) — review.spec.ts runs in the "auth" project (the ci-runs-auth-off-blind lesson).
 *  - the MULTI-ROOT server (two roots, both serving, port {@link MULTI_PORT}).
 *  - the MULTI-ROOT server whose second root is MISSING at boot ({@link MULTI_MISSING_PORT}).
 *
 * The last two are two SERVERS and not one parameterized project because root availability is
 * decided at boot and is sticky until restart: "both roots serving" and "one root down" cannot be
 * the same process.
 */
export const SMOKE_PORT = 4378;
export const AUTH_PORT = 4379;
export const MULTI_PORT = 4380;
export const MULTI_MISSING_PORT = 4381;

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
    // Every existing spec EXCEPT the ones with a server of their own → the open (auth-off) server.
    // A spec left out of this ignore list ALSO runs here, against a single-root server, and fails there.
    {
      name: "open",
      testIgnore: [/review\.spec\.ts/, /multi-root\.spec\.ts/, /multi-root-unavailable\.spec\.ts/],
      use: { baseURL: `http://127.0.0.1:${SMOKE_PORT}` },
    },
    // The enforced-builtin approval flow → the seeded auth server.
    { name: "auth", testMatch: /review\.spec\.ts/, use: { baseURL: `http://127.0.0.1:${AUTH_PORT}` } },
    { name: "multi-root", testMatch: /multi-root\.spec\.ts/, use: { baseURL: `http://127.0.0.1:${MULTI_PORT}` } },
    {
      name: "multi-root-unavailable",
      testMatch: /multi-root-unavailable\.spec\.ts/,
      use: { baseURL: `http://127.0.0.1:${MULTI_MISSING_PORT}` },
    },
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
    {
      command: "node scripts/smoke-server.mjs",
      env: { SMOKE_PORT: String(MULTI_PORT), SMOKE_ROOTS: "multi" },
      url: `http://127.0.0.1:${MULTI_PORT}/healthz`,
      ...webServerDefaults,
    },
    {
      command: "node scripts/smoke-server.mjs",
      env: { SMOKE_PORT: String(MULTI_MISSING_PORT), SMOKE_ROOTS: "multi-missing" },
      url: `http://127.0.0.1:${MULTI_MISSING_PORT}/healthz`,
      ...webServerDefaults,
    },
  ],
});
