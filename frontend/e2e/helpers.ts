import { expect, type Page, type Response } from "@playwright/test";

/**
 * Shared spec helpers. `playwright.config.ts` sets `testDir: "./e2e"` with the default `testMatch`
 * (`*.spec.ts`), so this module is never collected as a test file.
 *
 * `frontend/tsconfig.json` includes `e2e`, so `:frontend:build` typechecks these helpers.
 */

/** Opens a server-backed route and verifies the transport status before making DOM assertions. */
export async function gotoExpectStatus(page: Page, url: string, status = 200): Promise<Response | null> {
  const response = await page.goto(url);
  expect(response?.status(), url).toBe(status);
  return response;
}

/** Plants a marker that a full page (re)load would wipe. */
export async function plantNoReloadMarker(page: Page) {
  await page.evaluate(() => {
    (window as unknown as Record<string, unknown>).__pbNoReload = true;
  });
}

export async function expectNoReload(page: Page) {
  expect(await page.evaluate(() => (window as unknown as Record<string, unknown>).__pbNoReload)).toBe(true);
}

/** The inverse: the marker is gone, so the browser built a NEW document rather than the router swapping views. */
export async function expectReloaded(page: Page) {
  expect(await page.evaluate(() => (window as unknown as Record<string, unknown>).__pbNoReload)).toBeUndefined();
}
