import { expect, test as base } from "@playwright/test";
import { existsSync } from "node:fs";
import { startSmokeServer, type SmokeScenario, type SmokeServer } from "./smoke-server";

type SmokeFixtures = {
  smokeServer: SmokeServer;
};

export const test = base.extend<SmokeFixtures>({
  smokeServer: [
    async ({}, use, testInfo) => {
      const scenario = scenarioFor(testInfo.project.name);
      const logPath = testInfo.outputPath("server.log");
      let server: SmokeServer | undefined;
      try {
        server = await startSmokeServer(scenario, logPath);
        await use(server);
      } finally {
        let teardownError: unknown;
        try {
          await server?.stop();
        } catch (error) {
          teardownError = error;
        }
        try {
          if ((teardownError !== undefined || testInfo.status !== testInfo.expectedStatus) && existsSync(logPath)) {
            await testInfo.attach("server-log", { path: logPath, contentType: "text/plain" });
          }
        } catch (error) {
          teardownError ??= error;
        }
        if (teardownError) {
          throw teardownError;
        }
      }
    },
    { timeout: 90_000 },
  ],
  baseURL: async ({ smokeServer }, use) => {
    await use(smokeServer.baseURL);
  },
});

export { expect };
export type { Page, Route } from "@playwright/test";

function scenarioFor(projectName: string): SmokeScenario {
  switch (projectName) {
    case "open":
      return { authMode: "off", roots: "single" };
    case "auth":
      return { authMode: "builtin", roots: "single" };
    case "multi-root":
      return { authMode: "off", roots: "multi" };
    case "multi-root-unavailable":
      return { authMode: "off", roots: "multi-missing" };
    default:
      throw new Error(`Unsupported smoke project: ${projectName}`);
  }
}
