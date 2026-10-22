import { expect, test, type Page } from "./smoke-fixtures";
import { expectNoReload, plantNoReloadMarker } from "./helpers";

const GUIDES_ID = "01970000-0000-7000-8000-00000000a110";
const ALPHA_ID = "01970000-0000-7000-8000-00000000a111";

for (const failure of ["500", "transport"]) {
  test(`a reader session ${failure} keeps the served shadowed landing after retries`, async ({ page }) => {
    let requests = 0;
    await page.route("**/api/v1/session", async (route) => {
      requests++;
      if (failure === "transport") await route.abort("connectionfailed");
      else await route.fulfill({ status: 500, json: { error: { code: "unavailable", message: "Session unavailable" } } });
    });
    expect((await page.goto("/docs/reader/shadow/index"))?.status()).toBe(200);
    await expect.poll(() => requests).toBe(3);
    await settle(page);
    expect(new URL(page.url()).pathname).toBe("/docs/reader/shadow/index");
    await expect(page.locator("[data-pb-page-article]")).toHaveAttribute("data-pb-page-id", "01970000-0000-7000-8000-00000000a113");
    await expect(page.locator("[data-pb-new-page], [data-pb-edit-page], .pb-property-add, [aria-label='Discussions']")).toHaveCount(0);
    expect(requests).toBe(3);
  });
}

async function holdSession(page: Page) {
  let release!: () => void;
  let requested!: () => void;
  let count = 0;
  const held = new Promise<void>((resolve) => { release = resolve; });
  const seen = new Promise<void>((resolve) => { requested = resolve; });
  await page.route("**/api/v1/session", async (route) => {
    count++;
    requested();
    await held;
    await route.continue();
  });
  return { release, seen, count: () => count };
}

async function settle(page: Page): Promise<void> {
  await expect(page.locator("[data-pb-page-article]")).toBeVisible();
  await expect(page.locator("[data-pb-initial]")).toHaveCount(0);
  await page.waitForLoadState("networkidle");
}

for (const row of [
  { path: "/docs/reader/guides/", destination: "/docs/reader/guides/", id: GUIDES_ID },
  { path: "/docs/reader/guides/alpha/", destination: "/docs/reader/guides/alpha", id: ALPHA_ID },
]) {
  test(`accepted trailing-slash content at ${row.path} survives takeover`, async ({ page }) => {
    const response = await page.goto(row.path);
    expect(response?.status()).toBe(200);
    const source = await response!.text();
    expect(source).toContain(`name="plainbase-page-id" content="${row.id}"`);
    expect(new URL(response!.url()).pathname).toBe(row.path);
    await settle(page);
    await expect(page.locator("[data-pb-page-article]")).toHaveAttribute("data-pb-page-id", row.id);
    await expect(page.locator("[data-pb-page-article] h1")).toBeVisible();
    await expect(page.locator("[data-pb-not-found]")).toHaveCount(0);
    expect(new URL(page.url()).pathname).toBe(row.destination);
  });
}

test("cold reader folder renders without creation controls while its session is held", async ({ page }) => {
  const session = await holdSession(page);
  try {
    expect((await page.goto("/docs/reader"))?.status()).toBe(200);
    await session.seen;
    await expect(page.locator("[data-pb-folder]")).toBeVisible();
    await expect(page.locator("[data-pb-new-page], .pb-folder-create")).toHaveCount(0);
    session.release();
    await page.waitForLoadState("networkidle");
    await expect(page.locator("[data-pb-new-page], .pb-folder-create")).toHaveCount(0);
    expect(session.count()).toBe(1);
  } finally {
    session.release();
  }
});

for (const row of [
  { name: "new-page address", path: "/new", mode: false },
  { name: "editing mode", path: "/docs/reader/guides/alpha?mode=edit", mode: true },
  { name: "history mode", path: "/docs/reader/guides/alpha?mode=history", mode: true },
]) {
  test(`cold reader ${row.name} waits for its session`, async ({ page }) => {
    const session = await holdSession(page);
    try {
      await page.goto(row.path);
      await session.seen;
      await expect(page.locator("[data-pb-main] [data-pb-loading]")).toBeVisible();
      await expect(page.locator("[data-pb-new-page-form], [data-pb-editor], [data-pb-history], [data-pb-new-page]")).toHaveCount(0);
      session.release();
      if (row.mode) {
        await settle(page);
        await expect(page.locator("[data-pb-page-article]")).toHaveAttribute("data-pb-page-id", ALPHA_ID);
      } else {
        await expect(page.locator("[data-pb-not-found]")).toBeVisible();
        await expect(page.locator("[data-pb-new-page-form]")).toHaveCount(0);
      }
      expect(session.count()).toBe(1);
    } finally {
      session.release();
    }
  });
}

for (const row of [
  { name: "Unicode folder landing", path: "/docs/reader/caf%C3%A9", served: "/docs/reader/caf%C3%A9",
    id: "01970000-0000-7000-8000-00000000a117", redirect: false },
  { name: "decomposed Unicode folder landing", path: "/docs/reader/cafe%CC%81", served: "/docs/reader/cafe%CC%81",
    id: "01970000-0000-7000-8000-00000000a117", redirect: false },
  { name: "decomposed slashed Unicode folder landing", path: "/docs/reader/cafe%CC%81/", served: "/docs/reader/cafe%CC%81/",
    id: "01970000-0000-7000-8000-00000000a117", redirect: false },
  { name: "landing child", path: "/docs/reader/guides/index", served: "/docs/reader/guides", id: GUIDES_ID, redirect: true },
  { name: "page alias", path: "/docs/reader/old/alpha", served: "/docs/reader/guides/alpha", id: ALPHA_ID, redirect: true },
  { name: "landing alias at its folder", path: "/docs/reader/named", served: "/docs/reader/named",
    id: "01970000-0000-7000-8000-00000000a114", redirect: false },
  { name: "landing alias at its slashed folder", path: "/docs/reader/named/", served: "/docs/reader/named/",
    id: "01970000-0000-7000-8000-00000000a114", redirect: false },
  { name: "shadowed landing child", path: "/docs/reader/shadow/index", served: "/docs/reader/shadow/index",
    id: "01970000-0000-7000-8000-00000000a113", redirect: false },
  { name: "landing with an alias-owned folder URL", path: "/docs/reader/ref/index", served: "/docs/reader/ref/index",
    id: "01970000-0000-7000-8000-00000000a115", redirect: false },
]) {
  test(`${row.name} keeps the served address and page after takeover`, async ({ page }) => {
    const response = await page.goto(row.path);
    expect(response).not.toBeNull();
    expect(response!.status()).toBe(200);
    const servedPath = new URL(response!.url()).pathname;
    const source = await response!.text();
    const servedId = source.match(/<meta name="plainbase-page-id" content="([^"]+)"/)?.[1];
    expect(servedPath).toBe(row.served);
    expect(servedId).toBe(row.id);
    if (row.redirect) expect(response!.request().redirectedFrom()).not.toBeNull();
    else expect(response!.request().redirectedFrom()).toBeNull();

    await settle(page);
    expect(new URL(page.url()).pathname).toBe(servedPath);
    await expect(page.locator("[data-pb-page-article]")).toHaveAttribute("data-pb-page-id", servedId!);
    await expect(page.locator("[data-pb-not-found]")).toHaveCount(0);
    await expect(page.locator('meta[name="plainbase-page-id"]')).toHaveAttribute("content", servedId!);
  });
}

for (const row of [
  { name: "Back to the guides", href: "/docs/reader/guides/index", hash: "" },
  { name: "Guides top", href: "/docs/reader/guides/index#reader-guides", hash: "#reader-guides" },
]) {
  test(`the in-app ${row.name} link reaches the landing folder without reloading`, async ({ page }) => {
    const response = await page.goto("/docs/reader/guides/alpha");
    expect(response?.status()).toBe(200);
    await settle(page);
    await expect(page.locator("[data-pb-page-article]")).toHaveAttribute("data-pb-page-id", ALPHA_ID);
    const link = page.getByRole("link", { name: row.name, exact: true });
    await expect(link).toHaveAttribute("href", row.href);
    await plantNoReloadMarker(page);
    await link.click();
    await expect(page).toHaveURL((url) => url.pathname === "/docs/reader/guides");
    if (row.hash) expect(new URL(page.url()).hash).toBe(row.hash);
    await settle(page);
    expect(new URL(page.url()).pathname).toBe("/docs/reader/guides");
    await expect(page.locator("[data-pb-page-article]")).toHaveAttribute("data-pb-page-id", GUIDES_ID);
    await expectNoReload(page);
  });
}
