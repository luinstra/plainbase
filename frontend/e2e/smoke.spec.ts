import { expect, test } from "@playwright/test";
import { expectNoReload, plantNoReloadMarker } from "./helpers";

/**
 * Chunk-7 acceptance smoke flow, driven against the real server (CIO + embedded SPA)
 * serving fixtures/demo-docs. Each test maps to a plan criterion (plan lines 626-630).
 */

test("sidebar links are /docs URLs from the tree; clicking navigates without reload", async ({ page }) => {
  await page.goto("/docs/main/welcome");
  await expect(page.locator(".pb-prose h1")).toContainText("Welcome to Demo Docs");

  const sidebar = page.locator(".pb-sidebar");
  await expect(sidebar).toBeVisible();
  const hrefs = await sidebar.locator("a[href]").evaluateAll((anchors) => anchors.map((a) => a.getAttribute("href")));
  expect(hrefs.length).toBeGreaterThan(30); // the whole fixture tree is in the nav
  for (const href of hrefs) expect(href).toMatch(/^\/(docs($|\/)|p\/)/); // tree urls verbatim (incl. bare /docs home); losers via /p/{root}/{id}

  await plantNoReloadMarker(page);
  await sidebar.getByRole("link", { name: "Deploy Guide" }).click();
  await expect(page).toHaveURL("/docs/main/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
  await expectNoReload(page);

  // Breadcrumbs come from the page path, with the _folder.yaml folder title — and the
  // trail always opens with the root "docs" crumb linking to the root landing.
  await expect(page.locator(".pb-breadcrumbs")).toContainText("Guides");
  await expect(page.locator('.pb-breadcrumbs a[href="/docs"]')).toHaveText("docs");
});

test("internal links inside server-rendered HTML navigate via the SPA router", async ({ page }) => {
  await page.goto("/docs/main/welcome");
  await plantNoReloadMarker(page);
  await page.locator(".pb-prose").getByRole("link", { name: "Getting Started guide" }).click();
  await expect(page).toHaveURL("/docs/main/guides/getting-started");
  await expect(page.locator(".pb-prose h1")).toContainText("Getting Started");
  await expectNoReload(page);
});

test("an alias URL 301s server-side to the canonical /docs URL", async ({ page }) => {
  // guides/deploy-guide.md declares redirect_from: [/old/deployment.md]
  await page.goto("/docs/main/old/deployment");
  await expect(page).toHaveURL("/docs/main/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
});

test("a rootless /docs URL is 404 carrying the shell body: the root segment is required", async ({ request }) => {
  // The pre-C3 URL shape names no root in its first segment, so it addresses no page. The BODY is
  // still the shell (this is a browser navigation and the SPA owns not-found); the status is honest.
  const rootless = await request.get("/docs/guides/deploy-guide", { maxRedirects: 0 });
  expect(rootless.status()).toBe(404);
  expect(rootless.headers()["content-type"]).toContain("text/html");

  const withQuery = await request.get("/docs/guides/deploy-guide?mode=edit", { maxRedirects: 0 });
  expect(withQuery.status()).toBe(404);

  // An alias is registered UNDER a root, so a rootless alias URL reaches no alias registry either.
  const alias = await request.get("/docs/old/deployment", { maxRedirects: 0 });
  expect(alias.status()).toBe(404);
});

test("a bare /p/{id} permalink 302s server-side to the canonical path (stale slug tolerated)", async ({ page, request }) => {
  const byPath = await request.get("/api/v1/pages/by-path/main/guides/deploy-guide");
  expect(byPath.ok()).toBe(true);
  const { id } = (await byPath.json()) as { id: string };

  await page.goto(`/p/${id}`);
  await expect(page).toHaveURL("/docs/main/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");

  await page.goto(`/p/${id}/some-stale-slug`);
  await expect(page).toHaveURL("/docs/main/guides/deploy-guide");
});

test("a folder URL renders the generated landing view; breadcrumbs link back to it", async ({ page }) => {
  // fixtures/demo-docs has no README/index children inside folders, so smoke exercises
  // the listing fallback; the README-preference path is covered by the unit suite.
  await page.goto("/docs/main/guides");
  const listing = page.locator("[data-pb-folder]");
  await expect(listing).toBeVisible();
  await expect(listing.locator("h1")).toHaveText("Guides"); // _folder.yaml title
  await expect(listing.locator('a[href="/docs/main/guides/advanced"]')).toBeVisible(); // subfolder link

  await plantNoReloadMarker(page);
  await listing.getByRole("link", { name: "Deploy Guide" }).click();
  await expect(page).toHaveURL("/docs/main/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
  await expectNoReload(page);

  // The breadcrumb ancestor is now a link back to the folder landing (ADR-0003).
  await page.locator(".pb-breadcrumbs").getByRole("link", { name: "Guides" }).click();
  await expect(page).toHaveURL("/docs/main/guides");
  await expect(page.locator("[data-pb-folder]")).toBeVisible();
  await expectNoReload(page);
});

test("sidebar folder labels navigate to the landing view; the chevron still collapses", async ({ page }) => {
  await page.goto("/docs/main/welcome");
  const sidebar = page.locator(".pb-sidebar");

  await sidebar.getByRole("button", { name: "Collapse Guides" }).click();
  await expect(sidebar.getByRole("link", { name: "Deploy Guide" })).toBeHidden();
  await sidebar.getByRole("button", { name: "Expand Guides" }).click();
  await expect(sidebar.getByRole("link", { name: "Deploy Guide" })).toBeVisible();

  await plantNoReloadMarker(page);
  await sidebar.getByRole("link", { name: "Guides", exact: true }).click();
  await expect(page).toHaveURL("/docs/main/guides");
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

  await page.goto("/new");
  await expect(page.locator("[data-pb-new-page-form]")).toBeVisible();
  await page.locator("[data-pb-new-section]").check();
  await page.locator("[data-pb-new-folder]").fill(dir);
  await page.locator("[data-pb-new-title]").fill(sectionTitle);
  await page.locator("[data-pb-new-create]").click();

  // The index page's own url (/docs/<dir>/index) canonicalizes to the folder landing (/docs/<dir>).
  await expect(page).toHaveURL(`/docs/main/${dir}`);
  // REPLACE semantics: the folder landing IS the index page view (rail present), NOT the generated
  // listing — neither the listing container nor the generated-folder heading appear.
  await expect(page.locator("[data-pb-rail]")).toBeVisible();
  await expect(page.locator("[data-pb-folder-children]")).toHaveCount(0);
  await expect(page.locator("[data-pb-folder]")).toHaveCount(0);
  await expect(page.locator(".pb-breadcrumbs")).toContainText(sectionTitle);
  // The sidebar gains the new section as a folder row labelled by the index page's title (folderTitle).
  await expect(page.locator(`.pb-sidebar a[href="/docs/main/${dir}"]`)).toHaveText(sectionTitle);
});

test("an unknown path serves the shell and the SPA renders the 404 view", async ({ page }) => {
  const response = await page.goto("/docs/main/nope/never-existed");
  expect(response?.status()).toBe(200); // shell, per the routing matrix
  await expect(page.locator("[data-pb-not-found]")).toBeVisible();
  await expect(page.locator("[data-pb-not-found]")).toContainText("Page not found");
});

test("a deep link with #fragment scrolls to the anchor", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 380 });
  await page.goto("/docs/main/guides/deploy-guide#rollback");
  const heading = page.locator("#rollback");
  await expect(heading).toBeInViewport();
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
});

test("dark-mode toggle swaps data-theme, restyles via tokens, and persists", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await page.goto("/docs/main/welcome");
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
  await page.goto("/docs/main/infra/terraform");
  const code = page.locator('.pb-prose pre code[class*="language-"]');
  await expect(code).toHaveClass(/hljs/);
});

test("broken links carry the server marker and the broken-link token color", async ({ page }) => {
  await page.goto("/docs/main/notes/broken-links");
  const broken = page.locator('[data-pb-link-error="broken_missing"]').first();
  await expect(broken).toBeVisible();
  const [brokenColor, liveColor] = await page.evaluate(() => {
    const brokenEl = document.querySelector('[data-pb-link-error="broken_missing"]')!;
    const liveEl = document.querySelector('.pb-prose a[href^="/docs/main/"]')!;
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
  await expect(page).toHaveURL("/docs/main/guides/getting-started");
  await expect(page.locator(".pb-prose h1")).toContainText("Getting Started");
  await expectNoReload(page);
});
