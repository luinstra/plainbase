import { expect, type Page } from "@playwright/test";

/**
 * Shared spec helpers. `playwright.config.ts` sets `testDir: "./e2e"` with the default `testMatch`
 * (`*.spec.ts`), so this module is never collected as a test file.
 *
 * Nothing typechecks this file: `frontend/tsconfig.json` includes `src` only, so an error here shows
 * up at `:frontend:smokeTest` and nowhere earlier.
 */

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
