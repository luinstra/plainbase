import { defineConfig } from "@playwright/test";

/** Smoke tests use a fresh server fixture for every test attempt, including retries and repeats. */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 2,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"]],
  use: {
    browserName: "chromium",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "open",
      testIgnore: [/review\.spec\.ts/, /discussions-auth\.spec\.ts/, /multi-root\.spec\.ts/, /multi-root-unavailable\.spec\.ts/, /discussions\.spec\.ts/, /discussion-polish\.spec\.ts/],
    },
    { name: "auth", testMatch: /review\.spec\.ts/ },
    // Headed Chromium applies native caret browsing to static page text; CI supplies Xvfb.
    { name: "auth-caret", testMatch: /discussions-auth\.spec\.ts/,
      use: { headless: false, launchOptions: { args: ["--enable-caret-browsing"] } } },
    { name: "multi-root", testMatch: /(?:multi-root|discussions|discussion-polish)\.spec\.ts/ },
    { name: "multi-root-unavailable", testMatch: /multi-root-unavailable\.spec\.ts/ },
  ],
});
