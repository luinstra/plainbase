import { appendFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, type Page } from "./smoke-fixtures";
import { gotoExpectStatus, plantNoReloadMarker, waitForSearchReady } from "./helpers";
import { test } from "./vite-dev-harness";

async function readDevStylesheetState(page: Page) {
  return page.evaluate(() => {
    const head = Array.from(document.head.children);
    const bootstrap = head.flatMap((element, index) =>
      element instanceof HTMLScriptElement && !element.hasAttribute("src") && element.textContent?.includes("pb-theme")
        ? [index] : []);
    const links = head.flatMap((element, index) =>
      element instanceof HTMLLinkElement && element.rel === "stylesheet" ? [{ index, path: new URL(element.href).pathname }] : []);
    return {
      bootstrap,
      stylesheets: links.map((link) => link.path),
      appLinks: links.filter((link) => link.path === "/src/styles/app.css").map((link) => link.index),
      overrideLinks: links.filter((link) => link.path === "/_plainbase/custom.css").map((link) => link.index),
      appDevStyles: Array.from(document.querySelectorAll("style[data-vite-dev-id]")).flatMap((style) => {
        const id = style.getAttribute("data-vite-dev-id");
        return id?.endsWith("/src/styles/app.css") ? [id] : [];
      }),
      textRendering: getComputedStyle(document.body).textRendering,
    };
  });
}

function expectDevStylesheetState(state: Awaited<ReturnType<typeof readDevStylesheetState>>, phase: string): void {
  expect.soft(state.bootstrap, `${phase}: single inline theme bootstrap`).toHaveLength(1);
  expect.soft(state.appLinks, `${phase}: single app stylesheet link`).toHaveLength(1);
  expect.soft(state.overrideLinks, `${phase}: single override stylesheet link`).toHaveLength(1);
  expect.soft(state.bootstrap[0], `${phase}: bootstrap before app stylesheet`).toBeLessThan(state.appLinks[0]);
  expect.soft(state.stylesheets, `${phase}: stylesheet order`).toEqual([
    "/src/styles/app.css", "/plainbase-theme-generic.css", "/_plainbase/theme-light.css", "/_plainbase/theme-dark.css", "/_plainbase/custom.css",
  ]);
  expect.soft(state.appLinks[0], `${phase}: app stylesheet before override`).toBeLessThan(state.overrideLinks[0]);
  expect.soft(state.appDevStyles, `${phase}: no injected app stylesheet`).toEqual([]);
  expect.soft(state.textRendering.toLowerCase(), `${phase}: operator text-rendering`).toBe("geometricprecision");
}

test("dev keeps the override after the app stylesheet once modules run", async ({ page, smokeServer, viteDev: dev }, testInfo) => {
  test.setTimeout(180_000);
  writeFileSync(path.join(smokeServer.dataDir, "custom.css"), "body { text-rendering: geometricPrecision; }");
  await page.emulateMedia({ colorScheme: "light" });
  await gotoExpectStatus(page, new URL("/docs/scratch/todo", dev.baseURL).href);
  await page.waitForLoadState("networkidle");
  // A cold dev server can reload the first visit after optimizing dependencies; observe a settled page instead.
  const reload = await page.reload();
  expect(reload?.status()).toBe(200);
  await page.waitForLoadState("networkidle");
  await waitForSearchReady(page);
  const state = await readDevStylesheetState(page);
  const snapshotPath = testInfo.outputPath("dev-stylesheet.json");
  const snapshot = { baseURL: dev.baseURL, runRoot: dev.runRoot, state };
  writeFileSync(snapshotPath, JSON.stringify(snapshot, null, 2) + "\n");
  await testInfo.attach("dev-stylesheet", { path: snapshotPath, contentType: "application/json" });
  expectDevStylesheetState(state, "before update");
  await plantNoReloadMarker(page);
  const stylesheetPath = path.join(dev.runRoot, "src/styles/app.css");
  appendFileSync(stylesheetPath, "\nhtml { scroll-padding-top: 37px; }\n");
  await expect.poll(() => page.evaluate(() => getComputedStyle(document.documentElement).scrollPaddingTop), {
    timeout: 30_000,
  }).toBe("37px");
  const appLink = page.locator('link[rel="stylesheet"][href*="/src/styles/app.css"]');
  await expect.soft(appLink, "single app stylesheet after update").toHaveCount(1);
  await expect.soft(appLink, "app stylesheet timestamp after update").toHaveAttribute("href", /[?&]t=/);
  const updated = await page.evaluate(() => ({
    scrollPaddingTop: getComputedStyle(document.documentElement).scrollPaddingTop,
    noReload: (window as unknown as Record<string, unknown>).__pbNoReload === true,
    appLinks: Array.from(document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]'))
      .filter((link) => new URL(link.href).pathname === "/src/styles/app.css")
      .map((link) => link.href),
  }));
  const updatedState = await readDevStylesheetState(page);
  const updatedPath = testInfo.outputPath("hot-stylesheet.json");
  writeFileSync(updatedPath, JSON.stringify({ stylesheetPath, updated, state: updatedState }, null, 2) + "\n");
  await testInfo.attach("hot-stylesheet", { path: updatedPath, contentType: "application/json" });
  expect.soft(updated.noReload, "no reload during stylesheet update").toBe(true);
  expectDevStylesheetState(updatedState, "after update");
});
