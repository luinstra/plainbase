import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type Page, type Route } from "./smoke-fixtures";
import { gotoAndWaitForSearchReady, gotoExpectStatus, waitForSearchReady } from "./helpers";

/** Search acceptance against the real server and indexed fixture documents. */

async function openPalette(page: Page) {
  await waitForSearchReady(page);
  await page.keyboard.press("ControlOrMeta+k");
  await expect(page.locator("[data-pb-search-input]")).toBeFocused();
}

for (const theme of ["light", "dark"] as const) {
  test(`${theme} content search shows body context without repeating its title heading`, async ({ page, smokeServer }) => {
    await page.emulateMedia({ colorScheme: theme });
    await page.setViewportSize({ width: 1440, height: 1000 });
    writeFileSync(path.join(smokeServer.contentDir, "body-excerpt.md"), "---\ntitle: Body excerpt example\nowner: excerptcase\n---\n# Body excerpt example\n\nFirst actual body context for readers.\n");
    const query = "example excerptcase";
    await expect.poll(async () => {
      const response = await page.request.get(`/api/v1/search?q=${encodeURIComponent(query)}`);
      return response.ok() ? (await response.json()).hits?.[0]?.snippet : "";
    }).toBe("First actual body context for readers.");
    await gotoAndWaitForSearchReady(page, "/docs/welcome");
    await openPalette(page);
    await page.locator("[data-pb-search-input]").fill(query);
    const row = page.locator('[data-pb-search-item="hit"]');
    await expect(row).toHaveCount(1);
    await expect(row.locator("[data-pb-search-trail]")).not.toContainText("Body excerpt example");
    await expect(row.locator("[data-pb-search-snippet]")).toHaveText("First actual body context for readers.");
    await expect(row.locator("[data-pb-search-snippet] mark")).toHaveCount(0);
    const refinementScreenshots = path.resolve("../.crew/ui-designer-round-two-screenshots");
    mkdirSync(refinementScreenshots, { recursive: true });
    await page.screenshot({ path: path.join(refinementScreenshots, `${theme}-body-excerpt.png`), fullPage: false });
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/docs\/body-excerpt$/);
    await openPalette(page);
    await page.locator("[data-pb-search-input]").fill("context excerptcase");
    await expect(row.locator("[data-pb-search-snippet] mark")).toHaveText("context");
    await expect(row.locator("[data-pb-search-trail]")).not.toContainText("Body excerpt example");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/docs\/body-excerpt#body-excerpt-example$/);
  });
}

test("Cmd/Ctrl+K opens the palette; Esc closes", async ({ page }) => {
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await openPalette(page);
  await expect(page.locator("[data-pb-search]")).toBeVisible();
  await page.keyboard.press("Escape");
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

    let treeRequestsAfterReady = 0;
    page.on("request", (request) => {
      const url = request.url();
      if (url.includes("/api/v1/tree")) treeRequestsAfterReady += 1;
    });
    await openPalette(page);
    await page.locator("[data-pb-search-input]").fill("deploy");
    await expect(page.locator('[data-pb-search-item="jump"]').first()).toContainText("Deploy Guide");
    expect(treeRequestsAfterReady).toBe(0);
  } finally {
    releaseTree();
    await page.unroute("**/api/v1/tree", delayInitialTree);
  }
});

test("cached quick-switcher navigates via node.url without waiting on full text", async ({ page }) => {
  await gotoAndWaitForSearchReady(page, "/docs/welcome");

  let releaseSearch!: () => void;
  const released = new Promise<void>((resolve) => { releaseSearch = resolve; });
  let searchStarted = false;
  let finishSearch: Promise<void> | undefined;
  const delaySearch = (route: Route) => {
    searchStarted = true;
    finishSearch = released.then(() => route.continue());
    return finishSearch;
  };
  await page.route("**/api/v1/search?*", delaySearch);

  let treeRequestsAfterOpen = 0;
  page.on("request", (request) => {
    const url = request.url();
    if (url.includes("/api/v1/tree")) treeRequestsAfterOpen += 1;
  });

  try {
    await openPalette(page);
    await page.locator("[data-pb-search-input]").fill("deploy");
    const firstRow = page.locator('[data-pb-search-item="jump"]').first();
    await expect(firstRow).toContainText("Deploy Guide");
    await expect.poll(() => searchStarted).toBe(true);
    await expect(page.locator('[data-pb-search-item="hit"]')).toHaveCount(0);
    expect(treeRequestsAfterOpen).toBe(0);

    await page.keyboard.press("ArrowDown");
    const selected = page.locator("[data-pb-search-active]");
    await expect(selected).toHaveCount(1);
    const selectedStyle = await selected.evaluate((element) => ({
      background: getComputedStyle(element).backgroundColor,
      paletteBackground: getComputedStyle(element.closest("[data-pb-search-panel]")!).backgroundColor,
      marker: getComputedStyle(element, "::before").content,
    }));
    expect(selectedStyle.background).not.toBe(selectedStyle.paletteBackground);
    expect(selectedStyle.marker).toBe("none");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL("/docs/guides/deploy-guide");
  } finally {
    releaseSearch();
    await finishSearch;
    await page.unroute("**/api/v1/search?*", delaySearch);
  }
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

test("full text appears automatically and Escape closes in one step", async ({ page }) => {
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await openPalette(page);
  await page.locator("[data-pb-search-input]").fill("revert");
  await expect(page.locator("[data-pb-search-bridge]")).toHaveCount(0);
  await expect(page.locator('[data-pb-search-item="hit"]').first()).toBeVisible();
  await expect(page.locator("[data-pb-search-snippet] mark").first()).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator("[data-pb-search]")).toBeHidden();
});

test("full-text Enter deep-links to the section anchor, scrolls, and pulses", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 380 });
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  await openPalette(page);
  await page.locator("[data-pb-search-input]").fill("rollback");
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

for (const theme of ["light", "dark"] as const) {
  test(`${theme} search groups, space chips and desktop result geometry`, async ({ page, smokeServer }, testInfo) => {
    await page.emulateMedia({ colorScheme: theme });
    for (let index = 0; index < 8; index++) writeFileSync(path.join(smokeServer.contentDir, `runbook-${index}.md`),
      `# Runbook ${index}\n\n## Release procedure\n\nHow to deploy service ${index} safely.\n`);
    await expect.poll(async () => {
      const response = await page.request.get("/api/v1/search?q=deploy");
      const body = await response.json();
      return new Set((body.hits ?? []).filter((hit: { title: string }) => hit.title.startsWith("Runbook")).map((hit: { page_id: string }) => hit.page_id)).size;
    }).toBe(8);
    const shots = path.resolve("../.crew/ui-search-palette-screenshots");
    mkdirSync(shots, { recursive: true });
    for (const width of [1280, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      await gotoAndWaitForSearchReady(page, "/docs/welcome");
      await openPalette(page);
      const input = page.locator("[data-pb-search-input]");
      await expect(input).toBeFocused();
      const inputBounds = await input.boundingBox();
      const scopeBounds = await page.getByRole("button", { name: "All spaces", exact: true }).boundingBox();
      expect(scopeBounds!.y - (inputBounds!.y + inputBounds!.height)).toBeGreaterThanOrEqual(12);
      await input.fill("deploy");
      await expect(page.locator('[data-pb-search-item="jump"]').first()).toBeVisible();
      await expect(page.locator('[data-pb-search-item="jump"] mark').first()).toHaveText(/deploy/i);
      await page.keyboard.press("ArrowDown");
      const active = await input.getAttribute("aria-activedescendant");
      await expect(page.locator('[data-pb-search-item="hit"]').first()).toBeVisible();
      await expect(input).toHaveAttribute("aria-activedescendant", active!);
      await expect(page.locator("[data-pb-search-group]").first()).toBeVisible();
      await page.keyboard.press("Tab");
      await expect(page.getByRole("button", { name: "All spaces", exact: true })).toBeFocused();
      await page.keyboard.press("Tab");
      const rootChip = page.getByRole("group", { name: "Search scope" }).getByRole("button").nth(1);
      await expect(rootChip).toBeFocused();
      const request = page.waitForRequest((request) => request.url().includes("/api/v1/search") && new URL(request.url()).searchParams.has("root"));
      await page.keyboard.press("Enter");
      await request;
      await expect(rootChip).toHaveAttribute("aria-pressed", "true");
      await expect(page.locator('[data-pb-search-item="hit"]').first()).toBeVisible();
      await expect(page.locator("[data-pb-search-group]")).toHaveCount(0);
      const visibleRows = await page.locator("[data-pb-search-list]").evaluate((list) => {
        const bounds = list.getBoundingClientRect();
        return [...list.querySelectorAll('[role="option"]')].filter((row) => {
          const box = row.getBoundingClientRect();
          return box.top >= bounds.top && box.bottom <= bounds.bottom;
        }).length;
      });
      expect(visibleRows).toBeGreaterThanOrEqual(6);
      const scrim = await page.locator("[data-pb-search]").evaluate((node) => getComputedStyle(node).backdropFilter);
      expect(scrim).toContain("blur");
      const panel = await page.locator("[data-pb-search-panel]").boundingBox();
      expect(panel!.width).toBeGreaterThan(600);
      expect(panel!.y + panel!.height).toBeLessThan(1000);
      const screenshot = path.join(shots, `${theme}-search-${width}.png`);
      await page.screenshot({ path: screenshot });
      await testInfo.attach(`${theme}-search-${width}`, { path: screenshot, contentType: "image/png" });
      await page.keyboard.press("Escape");
      await expect(page.locator("[data-pb-search]")).toBeHidden();
    }
  });
}

test("keyboard navigation scrolls content results beyond the visible window", async ({ page, request }) => {
  await gotoAndWaitForSearchReady(page, "/docs/welcome");
  const response = await (await request.get("/api/v1/search?q=deploy")).json();
  expect(response.hits.length).toBeGreaterThan(0);
  const hit = response.hits[0];
  await page.route("**/api/v1/search?*", (route) => route.fulfill({ json: { ...response,
    hits: Array.from({ length: 16 }, (_, i) => ({ ...hit, page_id: `page-${i}`, heading_id: `section-${i}`, title: `Content result ${i + 1}` })) } }));
  await openPalette(page);
  await page.locator("[data-pb-search-input]").fill("zzunique");
  await expect(page.locator('[data-pb-search-item="hit"]')).toHaveCount(16);
  await page.keyboard.press("ArrowUp");
  const last = page.locator('[data-pb-search-item="hit"]').last();
  await expect(last).toHaveAttribute("aria-selected", "true");
  await expect(last).toBeInViewport();
  expect(await page.locator("[data-pb-search-list]").evaluate((node) => node.scrollTop)).toBeGreaterThan(0);
  const bounds = await page.locator("[data-pb-search-list]").boundingBox();
  const lastBounds = await last.boundingBox();
  expect(lastBounds!.y + lastBounds!.height).toBeLessThanOrEqual(bounds!.y + bounds!.height + 1);
});
