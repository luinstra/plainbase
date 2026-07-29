import { expect, test } from "@playwright/test";
import { expectNoReload, gotoExpectStatus, plantNoReloadMarker } from "./helpers";

/**
 * Chunk-7 acceptance smoke flow, driven against the real server (CIO + embedded SPA)
 * serving fixtures/demo-docs. Each test maps to a plan criterion (plan lines 626-630).
 */

test("sidebar links are root-qualified URLs from the tree; clicking navigates without reload", async ({ page }) => {
  await gotoExpectStatus(page, "/docs/welcome");
  await expect(page.locator(".pb-prose h1")).toContainText("Welcome to Demo Docs");

  const sidebar = page.locator(".pb-sidebar");
  await expect(sidebar).toBeVisible();
  const hrefs = await sidebar.locator("a[href]").evaluateAll((anchors) => anchors.map((a) => a.getAttribute("href")));
  expect(hrefs.length).toBeGreaterThan(30); // the whole fixture tree is in the nav
  for (const href of hrefs) expect(href).toMatch(/^\/(?:docs(?:$|\/)|p\/docs(?:$|\/))/); // tree urls verbatim (incl. bare /docs home); losers via /p/docs/{id}

  await plantNoReloadMarker(page);
  await sidebar.getByRole("link", { name: "Deploy Guide" }).click();
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
  await expectNoReload(page);

  // Breadcrumbs come from the page path, with the _folder.yaml folder title — and the
  // trail always opens with the root "docs" crumb linking to the root landing.
  await expect(page.locator(".pb-breadcrumbs")).toContainText("Guides");
  await expect(page.locator('.pb-breadcrumbs a[href="/docs"]')).toHaveText("docs");
});

test("internal links inside server-rendered HTML navigate via the SPA router", async ({ page }) => {
  await page.goto("/docs/welcome");
  await plantNoReloadMarker(page);
  await page.locator(".pb-prose").getByRole("link", { name: "Getting Started guide" }).click();
  await expect(page).toHaveURL("/docs/guides/getting-started");
  await expect(page.locator(".pb-prose h1")).toContainText("Getting Started");
  await expectNoReload(page);
});

// The exact 301 and its Location are pinned by the no-follow row below; `goto` resolves with the
// first NON-redirect response, so this row can only observe where the browser LANDS.
test("an alias URL follows through to the canonical /docs page and its content", async ({ page }) => {
  // guides/deploy-guide.md declares redirect_from: [/old/deployment.md]
  await gotoExpectStatus(page, "/docs/old/deployment");
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
});

test("the alias URL answers 301 with the canonical Location", async ({ request }) => {
  const response = await request.get("/docs/old/deployment", { maxRedirects: 0 });
  expect(response.status()).toBe(301);
  expect(response.headers()["location"]).toBe("/docs/guides/deploy-guide");
});

test("a primary /docs URL serves content after the URL flip", async ({ page }) => {
  await gotoExpectStatus(page, "/docs/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
});

test("a missing page beneath a registered root answers 200 and renders the SPA NotFound view", async ({ page }) => {
  await gotoExpectStatus(page, "/docs/nope/never-existed");
  await expect(page.locator("[data-pb-not-found]")).toBeVisible();
  await expect(page.locator("[data-pb-not-found]")).toContainText("Page not found");
  await expect(page.locator("[data-pb-folder]")).toHaveCount(0);
});

test("an UNKNOWN ROOT returns HTTP 404 with the SPA shell and NotFound view", async ({ page, request }) => {
  await gotoExpectStatus(page, "/nope/guides/deploy-guide", 404);
  await expect(page.locator("[data-pb-not-found]")).toBeVisible();
  await expect(page.locator("[data-pb-not-found]")).toContainText("Page not found");
  await expect(page.locator("[data-pb-folder]")).toHaveCount(0);

  const withQuery = await request.get("/nope/guides/deploy-guide?mode=edit", { maxRedirects: 0 });
  expect(withQuery.status()).toBe(404);

  // An alias is registered under a root, so an unknown-root alias reaches no alias registry either.
  const alias = await request.get("/nope/old/deployment", { maxRedirects: 0 });
  expect(alias.status()).toBe(404);
});

test("trailing-slash SPA routes keep the server's 404 verdict after hydration", async ({ page }) => {
  for (const path of ["/new/", "/admin/", "/review/"]) {
    await gotoExpectStatus(page, path, 404);
    await expect(page.locator("[data-pb-not-found]"), path).toBeVisible();
    await expect(page.locator("[data-pb-new-page-form]"), path).toHaveCount(0);
    await expect(page.locator("[data-pb-admin]"), path).toHaveCount(0);
    await expect(page.locator("[data-pb-review-queue]"), path).toHaveCount(0);
  }
});

test("a bare permalink answers 302 with the canonical Location", async ({ request }) => {
  const byPath = await request.get("/api/v1/pages/by-path/docs/guides/deploy-guide");
  expect(byPath.ok()).toBe(true);
  const { id } = (await byPath.json()) as { id: string };

  for (const address of [`/p/${id}`, `/p/${id}/some-stale-slug`]) {
    const response = await request.get(address, { maxRedirects: 0 });
    expect(response.status()).toBe(302);
    expect(response.headers()["location"]).toBe("/docs/guides/deploy-guide");
  }
});

// Same split as the alias rows: the exact 302 and its Location are pinned above by the no-follow
// row, and this one observes only the canonical page the browser ends up on.
test("a bare /p/{id} permalink follows through to the canonical page (stale slug tolerated)", async ({ page, request }) => {
  const byPath = await request.get("/api/v1/pages/by-path/docs/guides/deploy-guide");
  expect(byPath.ok()).toBe(true);
  const { id } = (await byPath.json()) as { id: string };

  await gotoExpectStatus(page, `/p/${id}`);
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");

  await gotoExpectStatus(page, `/p/${id}/some-stale-slug`);
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
});

test("the bare /docs/ URL answers 200 and renders the primary root folder landing", async ({ page }) => {
  await gotoExpectStatus(page, "/docs/");
  // demo-docs has an authored root index, so its folder landing is PageContent rather than the generated
  // listing. The reading rail and the landing page heading are the positive markers for that branch.
  await expect(page.locator("[data-pb-rail]")).toBeVisible();
  await expect(page.locator(".pb-prose h1")).toContainText("Welcome to Demo Docs");
  await expect(page.locator("[data-pb-not-found]")).toHaveCount(0);
  await expect(page.locator("[data-pb-editor]")).toHaveCount(0);
});

test("a folder URL renders the generated landing view; breadcrumbs link back to it", async ({ page }) => {
  // fixtures/demo-docs has no README/index children inside folders, so smoke exercises
  // the listing fallback; the README-preference path is covered by the unit suite.
  await gotoExpectStatus(page, "/docs/guides");
  const listing = page.locator("[data-pb-folder]");
  await expect(listing).toBeVisible();
  await expect(listing.locator("h1")).toHaveText("Guides"); // _folder.yaml title
  await expect(listing.locator('a[href="/docs/guides/advanced"]')).toBeVisible(); // subfolder link

  await plantNoReloadMarker(page);
  await listing.getByRole("link", { name: "Deploy Guide" }).click();
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
  await expectNoReload(page);

  // The breadcrumb ancestor is now a link back to the folder landing (ADR-0003).
  await page.locator(".pb-breadcrumbs").getByRole("link", { name: "Guides" }).click();
  await expect(page).toHaveURL("/docs/guides");
  await expect(page.locator("[data-pb-folder]")).toBeVisible();
  await expectNoReload(page);
});

test("sidebar folder labels navigate to the landing view; the chevron still collapses", async ({ page }) => {
  await page.goto("/docs/welcome");
  const sidebar = page.locator(".pb-sidebar");

  await sidebar.getByRole("button", { name: "Collapse Guides" }).click();
  await expect(sidebar.getByRole("link", { name: "Deploy Guide" })).toBeHidden();
  await sidebar.getByRole("button", { name: "Expand Guides" }).click();
  await expect(sidebar.getByRole("link", { name: "Deploy Guide" })).toBeVisible();

  await plantNoReloadMarker(page);
  await sidebar.getByRole("link", { name: "Guides", exact: true }).click();
  await expect(page).toHaveURL("/docs/guides");
  await expect(page.locator("[data-pb-folder]")).toBeVisible();
  await expectNoReload(page);
});

test("the new-section affordance creates <dir>/index.md and the folder landing renders it (no child listing)", async ({ page }) => {
  // C2 end-to-end: ticking "new section" on /new POSTs slug:index with the Folder field as the new
  // section path, materializing a brand-new nested dir server-side. The unique suffix keeps a retry
  // (CI runs retries=1) from colliding on an already-created section.
  const stamp = Date.now();
  const dir = `runbooks-${stamp}`;
  const sectionTitle = `Runbooks ${stamp}`;

  await gotoExpectStatus(page, "/new");
  await expect(page.locator("[data-pb-new-page-form]")).toBeVisible();
  await page.locator("[data-pb-new-section]").check();
  await page.locator("[data-pb-new-folder]").fill(dir);
  await page.locator("[data-pb-new-title]").fill(sectionTitle);
  await page.locator("[data-pb-new-create]").click();

  // The index page's own url (/docs/<dir>/index) canonicalizes to the folder landing (/docs/<dir>).
  await expect(page).toHaveURL(`/docs/${dir}`);
  // REPLACE semantics: the folder landing IS the index page view (rail present), NOT the generated
  // listing — neither the listing container nor the generated-folder heading appear.
  await expect(page.locator("[data-pb-rail]")).toBeVisible();
  await expect(page.locator("[data-pb-folder-children]")).toHaveCount(0);
  await expect(page.locator("[data-pb-folder]")).toHaveCount(0);
  await expect(page.locator(".pb-breadcrumbs")).toContainText(sectionTitle);
  // The sidebar gains the new section as a folder row labelled by the index page's title (folderTitle).
  await expect(page.locator(`.pb-sidebar a[href="/docs/${dir}"]`)).toHaveText(sectionTitle);
});

test("a deep link with #fragment scrolls to the anchor", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 380 });
  await gotoExpectStatus(page, "/docs/guides/deploy-guide#rollback");
  const heading = page.locator("#rollback");
  await expect(heading).toBeInViewport();
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
});

test("dark-mode toggle swaps data-theme, restyles via tokens, and persists", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await page.goto("/docs/welcome");
  const html = page.locator("html");
  await expect(html).not.toHaveAttribute("data-theme", "dark");
  const lightBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);

  await page.locator("[data-pb-theme-toggle]").click();
  await expect(html).toHaveAttribute("data-theme", "dark");
  const darkBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(darkBg).not.toBe(lightBg); // the token swap repainted — no component color logic involved

  await page.reload();
  await expect(html).toHaveAttribute("data-theme", "dark"); // persisted override survives reload
});

test("code blocks are highlighted client-side", async ({ page }) => {
  await gotoExpectStatus(page, "/docs/infra/terraform");
  const code = page.locator('.pb-prose pre code[class*="language-"]');
  await expect(code).toHaveClass(/hljs/);
});

test("broken links carry the server marker and the broken-link token color", async ({ page }) => {
  await gotoExpectStatus(page, "/docs/notes/broken-links");
  const broken = page.locator('[data-pb-link-error="broken_missing"]').first();
  await expect(broken).toBeVisible();
  const [brokenColor, liveColor] = await page.evaluate(() => {
    const brokenEl = document.querySelector('[data-pb-link-error="broken_missing"]')!;
    const liveEl = document.querySelector('.pb-prose a[href^="/docs/"]')!;
    return [getComputedStyle(brokenEl).color, getComputedStyle(liveEl).color];
  });
  expect(brokenColor).not.toBe(liveColor); // styled via --pb-link-broken, distinct from live links
});

test("the root path lands on the root folder landing at /docs", async ({ page }) => {
  await gotoExpectStatus(page, "/");
  await expect(page).toHaveURL("/docs");
  // demo-docs has BOTH index.md and README.md at the root — index wins, so the root
  // landing renders the welcome page's content at /docs (the listing branch is unit-covered).
  await expect(page.locator(".pb-prose h1")).toContainText("Welcome to Demo Docs");

  // A child link inside the landing content navigates via the SPA router.
  await plantNoReloadMarker(page);
  await page.locator(".pb-prose").getByRole("link", { name: "Getting Started guide" }).click();
  await expect(page).toHaveURL("/docs/guides/getting-started");
  await expect(page.locator(".pb-prose h1")).toContainText("Getting Started");
  await expectNoReload(page);
});
