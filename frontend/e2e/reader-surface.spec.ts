import { writeFileSync } from "node:fs";
import { expect, test, type Page } from "./smoke-fixtures";

async function settle(page: Page): Promise<void> {
  await expect(page.locator("[data-pb-page-article]")).toBeVisible();
  await expect(page.locator("[data-pb-initial]")).toHaveCount(0);
  await page.waitForLoadState("networkidle");
}

// Only route-specific ReaderSurfaceContract entries admit requests; fallback routes do not.
const mountedRequests = [
  /^\/api\/v1\/(session|tree|search)$/,
  /^\/api\/v1\/pages\/by-path\/.+$/,
  /^\/api\/v1\/pages\/(?!by-path(?:\/|$))[^/]+(\/html|\/metadata)?$/,
  /^\/assets\/.+$/,
  /^\/_plainbase\/(theme-light|theme-dark|custom)\.css$/,
  // FrontendBundle.files and its flat fonts directory supply these static routes.
  /^\/fonts\/[^/]+$/,
  /^\/(apple-touch-icon\.png|favicon\.svg|index\.html|plainbase-logo-dark\.svg|plainbase-logo\.svg|plainbase-theme-generic\.css)$/,
];

test("page and root landing content remain readable without JavaScript", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, baseURL });
  try {
    const page = await context.newPage();
    const alpha = await page.goto("/docs/reader/guides/alpha");
    expect(alpha?.status()).toBe(200);
    await expect(page.locator("[data-pb-initial]")).toBeVisible();
    await expect(page.getByText("Alpha unique reader sentence.", { exact: true })).toBeVisible();

    const root = await page.goto("/docs");
    expect(root?.status()).toBe(200);
    await expect(page.locator("[data-pb-initial]")).toBeVisible();
    await expect(page.getByText(
      "This fixture tree exercises Plainbase end to end: nested folders, assets, " +
      "relative links, frontmatter variety, and unicode edge cases.", { exact: true },
    )).toBeVisible();
  } finally {
    await context.close();
  }
});

test("takeover replaces the served article without duplicating its content", async ({ page }) => {
  const response = await page.goto("/docs/reader/guides/alpha");
  expect(response?.status()).toBe(200);
  const source = await response!.text();
  expect(source).toContain("data-pb-initial");
  expect(source).toContain("Alpha unique reader sentence.");
  await settle(page);
  await expect(page.getByText("Alpha unique reader sentence.", { exact: true })).toHaveCount(1);
  await expect(page.locator("[data-pb-initial]")).toHaveCount(0);
});

test("normal loads request only the mounted reader surface", async ({ page, baseURL }, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const paths = new Set<string>();
  const documents = new Set<string>();
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.origin !== origin) return;
    if (request.resourceType() === "document") {
      documents.add(url.pathname);
      return;
    }
    paths.add(url.pathname);
  });

  for (const path of [
    "/docs", "/docs/reader", "/docs/reader/guides", "/docs/reader/guides/alpha", "/docs/guides/deploy-guide",
  ]) {
    const response = await page.goto(path);
    expect(response?.status(), path).toBe(200);
    if (path === "/docs/reader") {
      await expect(page.locator("[data-pb-folder]")).toBeVisible();
      await page.waitForLoadState("networkidle");
    } else {
      await settle(page);
    }
  }

  const observed = [...paths].sort();
  const requestLog = testInfo.outputPath("reader-requests.json");
  writeFileSync(requestLog, JSON.stringify({ nonDocument: observed, documents: [...documents].sort() }, null, 2));
  await testInfo.attach("reader-requests", {
    path: requestLog, contentType: "application/json",
  });
  expect(observed).toContain("/api/v1/tree");
  expect(observed.some((path) => path.startsWith("/api/v1/pages/by-path/"))).toBe(true);
  for (const path of ["/api/v1/pages/sample", "/api/v1/pages/sample/html", "/api/v1/pages/sample/metadata", "/api/v1/pages/by-path/space/page"]) {
    expect(mountedRequests.some((pattern) => pattern.test(path)), path).toBe(true);
  }
  expect(mountedRequests.some((pattern) => pattern.test("/api/v1/pages/by-path"))).toBe(false);
  expect(mountedRequests.some((pattern) => pattern.test("/api/v1/pages/sample/discussions"))).toBe(false);
  expect(observed.filter((path) => !mountedRequests.some((pattern) => pattern.test(path)))).toEqual([]);
});
