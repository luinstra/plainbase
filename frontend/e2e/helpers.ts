import { expect, type Locator, type Page, type Response } from "@playwright/test";

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

/** Expands every currently reachable sidebar branch, including nested branches revealed along the way. */
export async function expandAllSidebarFolders(scope: Locator): Promise<void> {
  await expect(scope).toBeVisible();
  // The Shell renders a visible empty aside while its independent tree query is still loading.
  // Wait for the navigation itself or a fast page response can make this helper return too early.
  await expect(scope.getByRole("navigation", { name: "Documentation tree" })).toBeVisible();
  for (let expanded = 0; expanded < 100; expanded += 1) {
    const collapsed = scope.locator('[data-pb-folder-toggle][aria-expanded="false"]');
    if ((await collapsed.count()) === 0) return;
    // The tree re-sorts and inserts nested rows after each expansion. Pin the button by its unique
    // aria-controls id and wait for that exact row's state transition before resolving the next one;
    // repeatedly using the live `collapsed.first()` locator can otherwise click a moving target.
    const childrenId = await collapsed.first().getAttribute("aria-controls");
    if (!childrenId) throw new Error("expandable sidebar folder has no aria-controls target");
    const toggle = scope.locator(`[data-pb-folder-toggle][aria-controls="${childrenId}"]`);
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
  }
  throw new Error("sidebar expansion exceeded 100 folders");
}

/** Opens the themed root listbox and activates one configured root. */
export async function selectSidebarRoot(page: Page, root: string): Promise<void> {
  const trigger = page.locator("[data-pb-root-selector]");
  await trigger.click();
  const option = page.locator(`[data-pb-root-option="${root}"]`);
  await expect(option).toHaveCount(1);
  await option.click();
}
