import { expect, test, type Page } from "@playwright/test";

/**
 * Chunk-7 acceptance smoke flow, driven against the real server (CIO + embedded SPA)
 * serving fixtures/demo-docs. Each test maps to a plan criterion (plan lines 626-630).
 */

/** Plants a marker that a full page (re)load would wipe. */
async function plantNoReloadMarker(page: Page) {
  await page.evaluate(() => {
    (window as unknown as Record<string, unknown>).__pbNoReload = true;
  });
}

async function expectNoReload(page: Page) {
  expect(await page.evaluate(() => (window as unknown as Record<string, unknown>).__pbNoReload)).toBe(true);
}

test("sidebar links are /docs URLs from the tree; clicking navigates without reload", async ({ page }) => {
  await page.goto("/docs/welcome");
  await expect(page.locator(".pb-prose h1")).toContainText("Welcome to Demo Docs");

  const sidebar = page.locator(".pb-sidebar");
  await expect(sidebar).toBeVisible();
  const hrefs = await sidebar.locator("a[href]").evaluateAll((anchors) => anchors.map((a) => a.getAttribute("href")));
  expect(hrefs.length).toBeGreaterThan(30); // the whole fixture tree is in the nav
  for (const href of hrefs) expect(href).toMatch(/^\/(docs($|\/)|p\/)/); // tree urls verbatim (incl. bare /docs home); losers via /p/{id}

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

test("an alias URL 301s server-side to the canonical /docs URL", async ({ page }) => {
  // guides/deploy-guide.md declares redirect_from: [/old/deployment.md]
  await page.goto("/docs/old/deployment");
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
});

test("a /p/{id} permalink 302s server-side to the canonical path (stale slug tolerated)", async ({ page, request }) => {
  const byPath = await request.get("/api/v1/pages/by-path/guides/deploy-guide");
  expect(byPath.ok()).toBe(true);
  const { id } = (await byPath.json()) as { id: string };

  await page.goto(`/p/${id}`);
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");

  await page.goto(`/p/${id}/some-stale-slug`);
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
});

test("a folder URL renders the generated landing view; breadcrumbs link back to it", async ({ page }) => {
  // fixtures/demo-docs has no README/index children inside folders, so smoke exercises
  // the listing fallback; the README-preference path is covered by the unit suite.
  await page.goto("/docs/guides");
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

test("an unknown path serves the shell and the SPA renders the 404 view", async ({ page }) => {
  const response = await page.goto("/docs/nope/never-existed");
  expect(response?.status()).toBe(200); // shell, per the routing matrix
  await expect(page.locator("[data-pb-not-found]")).toBeVisible();
  await expect(page.locator("[data-pb-not-found]")).toContainText("Page not found");
});

test("a deep link with #fragment scrolls to the anchor", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 380 });
  await page.goto("/docs/guides/deploy-guide#rollback");
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
  await page.goto("/docs/infra/terraform");
  const code = page.locator('.pb-prose pre code[class*="language-"]');
  await expect(code).toHaveClass(/hljs/);
});

test("broken links carry the server marker and the broken-link token color", async ({ page }) => {
  await page.goto("/docs/notes/broken-links");
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
  await page.goto("/");
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
