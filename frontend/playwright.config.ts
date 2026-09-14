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
      testIgnore: [/review\.spec\.ts/, /multi-root\.spec\.ts/, /multi-root-unavailable\.spec\.ts/],
    },
    { name: "auth", testMatch: /review\.spec\.ts/ },
    { name: "multi-root", testMatch: /multi-root\.spec\.ts/ },
    { name: "multi-root-unavailable", testMatch: /multi-root-unavailable\.spec\.ts/ },
  ],
});
