import { expect, test } from "@playwright/test";
import {
  expandAllSidebarFolders,
  expectNoReload,
  expectReloaded,
  gotoExpectStatus,
  plantNoReloadMarker,
  selectSidebarRoot,
} from "./helpers";

/**
 * The two-root SPA, against a real two-root server (playwright.config.ts `multi-root` project:
 * SMOKE_ROOTS=multi, `docs` + `extra`, both serving).
 *
 * The sidebar remains one layout column and exposes one root tree at a time. The selector is
 * navigation: choosing a root pushes its server-issued root URL without reloading the shell.
 */

// The chromium default viewport (1280x720, unoverridden in playwright.config.ts) puts the sidebar
// clamp at its 16rem = 256 px floor, so ONE aside leaves <main> 1024 px. 900 keeps 124 px of slack
// for padding and a scrollbar while still FAILING the two-aside bug, which leaves 768 px.
const MIN_MAIN_WIDTH = 900;

/**
 * The permalink fixture's ids, hardcoded (frontend/e2e/fixtures/permalink). No row below may discover
 * its own precondition: an absent fixture must fail these tests, never quietly disarm them.
 *
 * `shadow.md` loses path space to `contested.md` (same slug, later raw filename), so LOSER has no
 * root-content address at all. Its only address is the rooted permalink. Both files are mounted into BOTH
 * roots, so both ids are genuinely DUPLICATED and every bare id-addressed read of them answers 409
 * `ambiguous_page_id`. That 409 is what does the assertion work below: on this corpus, "the page
 * rendered" IS proof the request carried `?root=`.
 */
const LOSER = "01970000-0000-7000-8000-00000000f003";

test("one sidebar, one root selector, and one visible tree: <main> keeps its width", async ({ page }) => {
  await gotoExpectStatus(page, "/docs/welcome");
  await expect(page.locator(".pb-prose h1")).toContainText("Welcome to Demo Docs");

  await expect(page.locator("aside[data-pb-sidebar]")).toHaveCount(1);
  const selector = page.locator("[data-pb-root-selector]");
  await expect(selector).toHaveAttribute("data-pb-selected-root", "docs");
  await selector.click();
  await expect(page.locator("[data-pb-root-menu]")).toBeVisible();
  await expect(page.locator("[data-pb-root-option]")).toHaveCount(2);
  await expect(page.locator('[data-pb-root-option="docs"]')).toHaveAttribute("aria-selected", "true");
  await expect(page.locator('[data-pb-root-option="extra"]')).toHaveAttribute("aria-selected", "false");

  const sections = page.locator("[data-pb-root-section]");
  await expect(sections).toHaveCount(1);
  await expect(sections.nth(0)).toHaveAttribute("data-pb-root-section", "docs");

  const main = await page.locator("[data-pb-main]").boundingBox();
  expect(main?.width ?? 0).toBeGreaterThanOrEqual(MIN_MAIN_WIDTH);

  const active = page.locator('.pb-sidebar [aria-current="page"]');
  await expect(active).toHaveCount(1);
  const activeStyle = await active.evaluate((element) => ({
    background: getComputedStyle(element).backgroundColor,
    sidebarBackground: getComputedStyle(element.closest(".pb-sidebar")!).backgroundColor,
    marker: getComputedStyle(element, "::before").content,
  }));
  expect(activeStyle.background).not.toBe(activeStyle.sidebarBackground);
  expect(activeStyle.marker).toBe("none");
});

test("both roots are selectable and navigable from the one sidebar", async ({ page }) => {
  await page.goto("/docs/welcome");

  const docs = page.locator('[data-pb-root-section="docs"]');
  await expandAllSidebarFolders(docs);
  const docsHrefs = await docs.locator("a[href]").evaluateAll((anchors) => anchors.map((a) => a.getAttribute("href")));
  expect(docsHrefs.length).toBeGreaterThan(0);
  for (const href of docsHrefs) expect(href).toMatch(/^\/(?:docs(?:$|\/)|p\/docs(?:$|\/))/);

  await selectSidebarRoot(page, "extra");
  await expect(page).toHaveURL("/extra");
  const extra = page.locator('[data-pb-root-section="extra"]');
  await expandAllSidebarFolders(extra);
  const hrefs = await extra.locator("a[href]").evaluateAll((anchors) => anchors.map((a) => a.getAttribute("href")));
  expect(hrefs.length).toBeGreaterThan(0);
  for (const href of hrefs) expect(href).toMatch(/^\/(?:extra(?:$|\/)|p\/extra(?:$|\/))/);

  await extra.getByRole("link", { name: "Deploy Guide" }).click();
  await expect(page).toHaveURL("/extra/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");

  // Back into docs from the same aside, with no reload or second nav column.
  await plantNoReloadMarker(page);
  await selectSidebarRoot(page, "docs");
  await expect(page).toHaveURL("/docs");
  await expandAllSidebarFolders(page.locator('[data-pb-root-section="docs"]'));
  await page.locator('[data-pb-root-section="docs"]').getByRole("link", { name: "Getting Started" }).first().click();
  await expect(page).toHaveURL("/docs/guides/getting-started");
  await expect(page.locator(".pb-prose h1")).toContainText("Getting Started");
  await expectNoReload(page);
  await expect(page.locator("aside[data-pb-sidebar]")).toHaveCount(1);
});

test("the sidebar's loser row links to the ROOTED permalink", async ({ page }) => {
  // The navigation is part of the row's input, not scenery: a Playwright test starts on about:blank,
  // where the sidebar locator would time out whether or not the href is rooted.
  await page.goto("/extra");

  await expandAllSidebarFolders(page.locator('[data-pb-root-section="extra"]'));
  await expect(page.locator(`[data-pb-root-section="extra"] a[href="/p/extra/${LOSER}"]`)).toHaveCount(1);
  // A bare permalink now answers 300 for this id, so it must appear NOWHERE on the page.
  await expect(page.locator(`a[href="/p/${LOSER}"]`)).toHaveCount(0);
});

test("clicking the loser row renders it, on a corpus where its id is DUPLICATED", async ({ page }) => {
  await page.goto("/extra");
  await expandAllSidebarFolders(page.locator('[data-pb-root-section="extra"]'));
  await page.locator(`[data-pb-root-section="extra"] a[href="/p/extra/${LOSER}"]`).click();

  await expect(page).toHaveURL(`/p/extra/${LOSER}`);
  await expect(page.locator(".pb-prose h1")).toContainText("Shadowed Loser");
  // Both id-addressed reads behind this view are pinned to `extra`; a bare one 409s. The crumb reads
  // the root NAME because two roots are configured.
  await expect(page.locator("[data-pb-breadcrumbs] li").first()).toContainText("extra");
});

test("a SERVER-emitted in-content loser link and the CLIENT parse agree", async ({ page }) => {
  // The one row that tests the emission-to-parse SYNC: the href is produced by the server's own
  // permalink builder, and everything after the click is the client's parse of that same string.
  await page.goto("/extra/permalink/hub");
  const link = page.locator(".pb-prose a", { hasText: "shadowed loser" });
  await expect(link).toHaveAttribute("href", `/p/extra/${LOSER}`);

  await plantNoReloadMarker(page);
  await link.click();
  await expect(page).toHaveURL(`/p/extra/${LOSER}`);
  await expect(page.locator(".pb-prose h1")).toContainText("Shadowed Loser");
  await expectNoReload(page);
});

test("a percent-escaped /p/ href goes to the SERVER: real reload, real 400 invalid_root", async ({ page }) => {
  // The one row that runs the percent guard through a REAL browser. Everything else that covers it is
  // jsdom, which is a model of `anchor.pathname`, and the guard's whole premise is that a browser keeps
  // the escape as authored rather than decoding it into a legal-looking root.
  //
  // The href cannot be authored in content: the renderer resolves `/p/%65xtra/{id}` as a content path,
  // finds nothing there, and emits the link INERT (no href at all). So the anchor is planted into the
  // live shell, which is exactly where the interceptor listens.
  await page.goto("/extra/permalink/hub");
  await page.evaluate((id) => {
    const anchor = document.createElement("a");
    anchor.setAttribute("href", `/p/%65xtra/${id}`);
    anchor.setAttribute("data-pb-escaped-permalink", "");
    anchor.textContent = "escaped root";
    document.querySelector("[data-pb-shell]")!.append(anchor);
  }, LOSER);

  const documentStatuses: number[] = [];
  page.on("response", (response) => {
    if (response.request().resourceType() === "document") documentStatuses.push(response.status());
  });

  await plantNoReloadMarker(page);
  await page.locator("a[data-pb-escaped-permalink]").click();
  await expect(page).toHaveURL(`/p/%65xtra/${LOSER}`);

  // The URL above is the same either way (the router would push that very string), so the marker is what
  // separates "the browser asked" from "the SPA answered".
  await expectReloaded(page);
  expect(documentStatuses).toEqual([400]);
  // Not a page: the shell never boots on the error document, and the verdict names the raw root segment.
  await expect(page.locator("[data-pb-shell]")).toHaveCount(0);
  await expect(page.locator("body")).toContainText("invalid_root");
});

test("the same id in two roots resolves to ITS OWN root across a client-side navigation", async ({ page }) => {
  // The CACHE-KEY row. Hop 1 seeds the cache; hop 2 MUST be a click and a second `page.goto` is BANNED
  // here, because a full document load discards the react-query cache and the row would pass with the
  // root dropped from the key: vacuous. The no-reload marker is the positive evidence the hop stayed
  // client-side rather than the hope that it did.
  await gotoExpectStatus(page, `/p/docs/${LOSER}`);
  await expect(page.locator(".pb-prose h1")).toContainText("Shadowed Loser");
  await expect(page.locator("[data-pb-breadcrumbs] li").first()).toContainText("docs");

  await plantNoReloadMarker(page);
  await selectSidebarRoot(page, "extra");
  await expect(page).toHaveURL("/extra");
  await expandAllSidebarFolders(page.locator('[data-pb-root-section="extra"]'));
  await page.locator(`[data-pb-root-section="extra"] a[href="/p/extra/${LOSER}"]`).click();
  await expect(page).toHaveURL(`/p/extra/${LOSER}`);
  await expectNoReload(page);
  // The two files are byte-identical copies, so CONTENT cannot tell the roots apart; the breadcrumb,
  // which reads the html response's own `root`, can.
  await expect(page.locator("[data-pb-breadcrumbs] li").first()).toContainText("extra");
});

test("an ordinary root-qualified view of a duplicated id renders in BOTH roots", async ({ page }) => {
  // Two `goto`s are correct HERE, unlike the cache-key row above: this row's defect is a missing
  // `?root=` on the REQUEST, which the server answers 409 on a duplicated id, so a cold cache
  // strengthens it. The hub page (id ...f001) is duplicated across both roots exactly like the loser,
  // and it is addressed here by its hardcoded PATH, which 404s if the fixture is not mounted.
  await gotoExpectStatus(page, "/docs/permalink/hub");
  await expect(page.locator(".pb-prose h1")).toContainText("Permalink Hub");

  await gotoExpectStatus(page, "/extra/permalink/hub");
  await expect(page.locator(".pb-prose h1")).toContainText("Permalink Hub");
  await expect(page).toHaveURL("/extra/permalink/hub");
  await expect(page.locator("[data-pb-edit-page]")).toHaveAttribute("href", "/extra/permalink/hub?mode=edit");
  await expect(page.locator('[data-pb-breadcrumbs] a[href="/extra"]')).toHaveText("extra");
  await expect(page.locator("[data-pb-breadcrumbs] li").first()).toContainText("extra");
});
