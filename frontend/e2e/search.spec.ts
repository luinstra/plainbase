import { expect, test, type Page, type Route } from "./smoke-fixtures";
import { gotoAndWaitForSearchReady, gotoExpectStatus, waitForSearchReady } from "./helpers";

/**
 * Chunk-S7 search-UI acceptance flow against the real server (CIO + embedded SPA + FTS5)
 * serving fixtures/demo-docs. Maps to the amended S7 acceptance criteria (addendum 1–23).
 */

async function openPalette(page: Page) {
  await waitForSearchReady(page);
  await page.keyboard.press("ControlOrMeta+k");
  await expect(page.locator("[data-pb-search-input]")).toBeFocused();
}

test("Cmd/Ctrl+K opens the palette in Stage 1 (quick-switcher); Esc closes", async ({ page }) => {
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await openPalette(page);
  await expect(page.locator('[data-pb-search][data-pb-search-stage="jump"]')).toBeVisible();
  await page.keyboard.press("Escape"); // Stage-1 Esc closes
  await expect(page.locator("[data-pb-search]")).toBeHidden();
});

test("search readiness waits for the delayed initial tree before keyboard use", async ({ page }) => {
  let releaseTree!: () => void;
  const treeReleased = new Promise<void>((resolve) => {
    releaseTree = resolve;
  });
  let treeRequestSeen = false;
  const delayInitialTree = async (route: Route) => {
    treeRequestSeen = true;
    await treeReleased;
    await route.continue();
  };

  await page.route("**/api/v1/tree", delayInitialTree);
  try {
    await gotoExpectStatus(page, "/docs/welcome");
    let ready = false;
    const readiness = waitForSearchReady(page).then(() => {
      ready = true;
    });
    await expect.poll(() => treeRequestSeen).toBe(true);
    expect(ready).toBe(false);

    releaseTree();
    await readiness;

    let searchRequests = 0;
    let treeRequestsAfterReady = 0;
    page.on("request", (request) => {
      const url = request.url();
      if (url.includes("/api/v1/search")) searchRequests += 1;
      if (url.includes("/api/v1/tree")) treeRequestsAfterReady += 1;
    });
    await openPalette(page);
    await page.locator("[data-pb-search-input]").fill("deploy");
    await expect(page.locator('[data-pb-search-item="jump"]').first()).toContainText("Deploy Guide");
    expect(searchRequests).toBe(0);
    expect(treeRequestsAfterReady).toBe(0);
  } finally {
    releaseTree();
    await page.unroute("**/api/v1/tree", delayInitialTree);
  }
});

test("Stage 1 quick-switcher is zero-network and Enter navigates via node.url", async ({ page }) => {
  await gotoAndWaitForSearchReady(page, "/docs/welcome");

  let searchRequests = 0;
  let treeRequestsAfterOpen = 0;
  page.on("request", (request) => {
    const url = request.url();
    if (url.includes("/api/v1/search")) searchRequests += 1;
    if (url.includes("/api/v1/tree")) treeRequestsAfterOpen += 1;
  });

  await openPalette(page);
  // Type a partial title; the fuzzy match appears synchronously, no network.
  await page.locator("[data-pb-search-input]").fill("deploy");
  const firstRow = page.locator('[data-pb-search-item="jump"]').first();
  await expect(firstRow).toContainText("Deploy Guide");
  expect(searchRequests).toBe(0); // zero full-text requests in Stage 1
  expect(treeRequestsAfterOpen).toBe(0); // the palette reads the cached tree, never refetches

  // ArrowDown selects the top match, Enter navigates to its node.url.
  await page.keyboard.press("ArrowDown");
  const selected = page.locator("[data-pb-search-active]");
  await expect(selected).toHaveCount(1);
  const selectedStyle = await selected.evaluate((element) => ({
    background: getComputedStyle(element).backgroundColor,
    paletteBackground: getComputedStyle(element.closest("[data-pb-search]")!).backgroundColor,
    marker: getComputedStyle(element, "::before").content,
  }));
  expect(selectedStyle.background).not.toBe(selectedStyle.paletteBackground);
  expect(selectedStyle.marker).toBe("none");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
  expect(searchRequests).toBe(0);
});

/** The path-space collision loser the smoke corpus is mounted with - see frontend/e2e/fixtures/permalink. */
const LOSER = "01970000-0000-7000-8000-00000000f003";

test("a collision-loser quick-switch hit navigates via /p/{root}/{id}", async ({ page, request }) => {
  // Discover the loser from the tree (a page whose url is null) and ASSERT it is there. This row used
  // to bail out conditionally when the corpus carried no loser, and the corpus never did: it bailed on
  // every run while six live defects sat behind it. A corpus change that removes the loser must fail
  // this test BY NAME, never disarm it.
  const tree = await (await request.get("/api/v1/tree")).json();
  const losers: string[] = [];
  const walk = (node: { type: string; url: string | null; title?: string; children?: unknown[] }) => {
    if (node.type === "page" && node.url === null && node.title) losers.push(node.title);
    if (node.children) for (const child of node.children) walk(child as never);
  };
  for (const entry of tree.roots) walk(entry.tree); // one entry per root since C3
  // EVERY loser, not the first one the walk meets: the id asserted at the end is hardcoded, so a corpus that
  // grows a SECOND loser must fail HERE, by name. A first-wins scan only caught an interloper that sorted
  // BEFORE shadow.md - one that sorted after left this row green on a pinned title while the palette had two
  // candidates to choose between.
  expect(
    losers,
    "the smoke corpus must carry EXACTLY the path-space collision loser this row is pinned to: frontend/e2e/fixtures/permalink/shadow.md shares a slug with contested.md",
  ).toEqual(["Shadowed Loser"]);
  const loserTitle = losers[0];

  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await openPalette(page);
  await page.locator("[data-pb-search-input]").fill(loserTitle);
  await expect(page.locator('[data-pb-search-item="jump"]').first()).toContainText(loserTitle);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(`/p/docs/${LOSER}`);
});

test("activating the bridge enters Stage 2 (full-text only) with a stage label", async ({ page }) => {
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await openPalette(page);
  await page.locator("[data-pb-search-input]").fill("rollback");
  await page.locator("[data-pb-search-bridge]").click();
  await expect(page.locator('[data-pb-search][data-pb-search-stage="search"]')).toBeVisible();
  await expect(page.locator("[data-pb-search-stage-label]")).toContainText("Search results");
  // Full-text hits render with visible <mark> highlights.
  await expect(page.locator('[data-pb-search-item="hit"]').first()).toBeVisible();
  await expect(page.locator("[data-pb-search-snippet] mark").first()).toBeVisible();
});

test("Stage 2 Esc returns to Stage 1; a second Esc closes", async ({ page }) => {
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await openPalette(page);
  await page.locator("[data-pb-search-input]").fill("rollback");
  await page.locator("[data-pb-search-bridge]").click();
  await expect(page.locator('[data-pb-search-stage="search"]')).toBeVisible();

  await page.keyboard.press("Escape"); // Stage 2 → Stage 1
  await expect(page.locator('[data-pb-search-stage="jump"]')).toBeVisible();
  await page.keyboard.press("Escape"); // Stage 1 → closed
  await expect(page.locator("[data-pb-search]")).toBeHidden();
});

test("full-text Enter deep-links to the section anchor, scrolls, and pulses", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 380 });
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await openPalette(page);
  await page.locator("[data-pb-search-input]").fill("rollback");
  await page.locator("[data-pb-search-bridge]").click();
  await expect(page.locator('[data-pb-search-item="hit"]').first()).toBeVisible();

  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/docs\/guides\/deploy-guide#.+/);
  const headingId = new URL(page.url()).hash.slice(1);
  const heading = page.locator(`#${headingId}`);
  await expect(heading).toBeInViewport();
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  await expect(heading).toHaveClass(/pb-deeplink-pulse/);
});

test("reduced-motion: deep-link still scrolls but does not pulse", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.setViewportSize({ width: 1280, height: 380 });
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await openPalette(page);
  await page.locator("[data-pb-search-input]").fill("rollback");
  await page.locator("[data-pb-search-bridge]").click();
  await expect(page.locator('[data-pb-search-item="hit"]').first()).toBeVisible();

  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/docs\/guides\/deploy-guide#.+/);
  const heading = page.locator(`#${new URL(page.url()).hash.slice(1)}`);
  await expect(heading).toBeInViewport();
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  await expect(heading).not.toHaveClass(/pb-deeplink-pulse/);
});

test("a deep link to a missing fragment lands at top with no error", async ({ page }) => {
  const errors: string[] = [];
  page.on("console", (msg) => {
    if (msg.type() !== "error") return;
    // The chrome's "Review" nav gate probes GET /api/v1/session on every load; in auth.mode=off that route
    // is intentionally absent (KtorServer registers it only in builtin/proxy), so it 404s. That probe is
    // by-design infra noise, orthogonal to this deep-link test — ignore it.
    if (msg.location().url.includes("/api/v1/session")) return;
    errors.push(msg.text());
  });
  await page.setViewportSize({ width: 1280, height: 380 });
  await gotoExpectStatus(page, "/docs/guides/deploy-guide#does-not-exist");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
  expect(await page.evaluate(() => window.scrollY)).toBe(0);
  expect(errors).toEqual([]);
});

test("the page behind does not scroll while the palette is open", async ({ page }) => {
  await gotoAndWaitForSearchReady(page, "/docs/guides/deploy-guide");
  await openPalette(page);
  // body is scroll-locked while open.
  expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).toBe("hidden");
  await page.keyboard.press("Escape");
  await expect(page.locator("[data-pb-search]")).toBeHidden();
  expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).not.toBe("hidden");
});

test("dark mode renders the palette via token swap only", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await page.locator("[data-pb-theme-toggle]").click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await openPalette(page);
  await expect(page.locator("[data-pb-search]")).toBeVisible();
});
