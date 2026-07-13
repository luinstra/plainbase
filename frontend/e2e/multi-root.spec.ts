import { expect, test } from "@playwright/test";

/**
 * The two-root SPA, against a real two-root server (playwright.config.ts `multi-root` project:
 * SMOKE_ROOTS=multi, `main` + `extra`, both serving).
 *
 * The layout assertion is the point. The sidebar `<aside>` IS a layout column — a fixed
 * `w-[clamp(16rem,20vw,22rem)]` slice of the Shell's flex row — so rendering one per root squeezes
 * `<main>` by 256 px per extra root, and with enough roots off the screen entirely. One aside, N
 * sections inside it.
 */

// The chromium default viewport (1280x720, unoverridden in playwright.config.ts) puts the sidebar
// clamp at its 16rem = 256 px floor, so ONE aside leaves <main> 1024 px. 900 keeps 124 px of slack
// for padding and a scrollbar while still FAILING the two-aside bug, which leaves 768 px.
const MIN_MAIN_WIDTH = 900;

test("one sidebar, one section per root: <main> keeps its width", async ({ page }) => {
  await page.goto("/docs/main/welcome");
  await expect(page.locator(".pb-prose h1")).toContainText("Welcome to Demo Docs");

  await expect(page.locator("aside[data-pb-sidebar]")).toHaveCount(1);
  const sections = page.locator("[data-pb-root-section]");
  await expect(sections).toHaveCount(2);
  await expect(sections.nth(0)).toHaveAttribute("data-pb-root-section", "main");
  await expect(sections.nth(1)).toHaveAttribute("data-pb-root-section", "extra");

  const main = await page.locator("[data-pb-main]").boundingBox();
  expect(main?.width ?? 0).toBeGreaterThanOrEqual(MIN_MAIN_WIDTH);
});

test("both roots are labeled and navigable from the one sidebar", async ({ page }) => {
  await page.goto("/docs/main/welcome");

  // Section headers appear only with 2+ roots — with one root a header reading "main" is noise.
  await expect(page.locator('[data-pb-root-label="main"]')).toHaveText("main");
  await expect(page.locator('[data-pb-root-label="extra"]')).toHaveText("extra");

  // Each section's links stay inside its own root: the tree urls are root-qualified verbatim.
  const extra = page.locator('[data-pb-root-section="extra"]');
  const hrefs = await extra.locator("a[href]").evaluateAll((anchors) => anchors.map((a) => a.getAttribute("href")));
  expect(hrefs.length).toBeGreaterThan(0);
  for (const href of hrefs) expect(href).toMatch(/^\/(docs\/extra($|\/)|p\/)/);

  await extra.getByRole("link", { name: "Deploy Guide" }).click();
  await expect(page).toHaveURL("/docs/extra/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");

  // ...and back into main, from the same aside — no reload, no second nav column.
  await page.locator('[data-pb-root-section="main"]').getByRole("link", { name: "Getting Started" }).first().click();
  await expect(page).toHaveURL("/docs/main/guides/getting-started");
  await expect(page.locator(".pb-prose h1")).toContainText("Getting Started");
  await expect(page.locator("aside[data-pb-sidebar]")).toHaveCount(1);
});
