import { mkdirSync } from "node:fs";
import path from "node:path";
import type { TreeFolder, TreeResponse } from "../src/api/types";
import { expect, test } from "./smoke-fixtures";
import { expectNoReload, gotoExpectStatus, plantNoReloadMarker } from "./helpers";

test("folder creation keeps the current location and page links keep native navigation", async ({ page, context }) => {
  await gotoExpectStatus(page, "/docs/guides");
  const listing = page.locator("[data-pb-folder]");
  await expect(listing.getByRole("heading", { name: "Guides", exact: true })).toBeVisible();
  await plantNoReloadMarker(page);
  const pageLink = listing.getByRole("link", { name: "Deploy Guide", exact: true });
  const [otherTab] = await Promise.all([context.waitForEvent("page"), pageLink.click({ modifiers: ["ControlOrMeta"] })]);
  await expect(otherTab).toHaveURL(/\/docs\/guides\/deploy-guide$/);
  await otherTab.close();
  await expect(page).toHaveURL(/\/docs\/guides$/);
  await listing.getByRole("link", { name: "New page here" }).focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/docs\/guides$/);
  await expect(page.getByRole("dialog", { name: "New page" })).toBeVisible();
  await expect(page.locator("[data-pb-new-folder]")).toHaveValue(JSON.stringify(["folder", "guides"]));
  await expectNoReload(page);
});

for (const theme of ["light", "dark"] as const) {
  test(`${theme} generated folders fill their column and page rows share one panel`, async ({ page }, testInfo) => {
    await page.emulateMedia({ colorScheme: theme });
    const shots = path.resolve("../.crew/ui-folder-landing-screenshots");
    mkdirSync(shots, { recursive: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await gotoExpectStatus(page, "/docs/guides");
    const grid = page.locator("[data-pb-folder] .pb-folder-grid");
    const single = page.locator('[data-pb-folder-child="folder"]');
    await expect(single).toHaveCount(1);
    expect(Math.abs((await grid.boundingBox())!.width - (await single.boundingBox())!.width)).toBeLessThan(2);

    let empty = false;
    const longTitle = "Platform integrations and deployment procedures with deliberately long names";
    const longPath = "guides/platform/integrations/very-long-unbroken-directory-name-for-layout";
    await page.route("**/api/v1/tree", async (route) => {
      const response = await route.fetch();
      const data = await response.json() as TreeResponse;
      const guides = data.roots[0].tree.children.find((child): child is TreeFolder => child.type === "folder" && child.path === "guides")!;
      const original = guides.children.find((child): child is TreeFolder => child.type === "folder")!;
      guides.children = empty ? [] : [
        { ...original, title: "Getting started", path: "guides/starting", page_count: 3 },
        { ...original, title: longTitle, path: longPath, page_count: 12 },
        { ...original, title: "API reference", path: "guides/api", page_count: 4 },
        ...guides.children.filter((child) => child.type === "page"),
      ];
      guides.page_count = guides.children.filter((child) => child.type === "page").length;
      await route.fulfill({ response, json: data });
    });

    for (const width of [1280, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.reload();
      const listing = page.locator("[data-pb-folder]");
      await expect(listing.locator('[data-pb-folder-child="folder"]')).toHaveCount(3);
      await expect(listing.locator(".fn").nth(1)).toHaveText(longTitle);
      await expect(listing.locator(".fp").nth(1)).toHaveAttribute("title", `${longPath}/`);
      const geometry = await listing.evaluate((node) => {
        const cards = [...node.querySelectorAll(".pb-folder-card")];
        const panel = node.querySelector(".pb-page-grid")!;
        const rows = [...panel.querySelectorAll(".pb-page-row")];
        return {
          overflow: node.scrollWidth > node.clientWidth,
          width: node.getBoundingClientRect().width,
          cardTops: cards.map((card) => card.getBoundingClientRect().top),
          cardLines: cards.map((card) => [".fn", ".fc", ".fp"].map((selector) => {
            const rect = card.querySelector(selector)!.getBoundingClientRect();
            return rect.top + rect.height / 2;
          })),
          panelBorder: getComputedStyle(panel).borderTopWidth,
          panelWidth: panel.getBoundingClientRect().width,
          rows: rows.map((row) => ({ width: row.getBoundingClientRect().width, top: row.getBoundingClientRect().top })),
        };
      });
      expect(geometry.overflow).toBe(false);
      expect(geometry.width).toBeGreaterThan(800);
      expect(geometry.width).toBeLessThanOrEqual(1040);
      expect(geometry.panelWidth).toBeGreaterThan(800);
      if (width === 1440) expect(new Set(geometry.cardTops).size).toBe(1);
      for (const [title, count, path] of geometry.cardLines) {
        expect(count - title).toBeGreaterThan(12);
        expect(Math.abs(path - count)).toBeLessThan(2);
      }
      expect(parseFloat(geometry.panelBorder)).toBeGreaterThan(0);
      expect(geometry.rows.length).toBeGreaterThan(1);
      for (const row of geometry.rows) expect(Math.abs(row.width - geometry.panelWidth)).toBeLessThan(3);
      expect(new Set(geometry.rows.map((row) => row.top)).size).toBe(geometry.rows.length);
      await expect(listing.locator(".pb-page-row .pb-pdot, .pb-page-row .pdate")).toHaveCount(0);
      await expect(listing.locator(".pb-page-row [data-pb-row-chevron]")).toHaveCount(geometry.rows.length);
      const action = listing.getByRole("link", { name: "New page here" });
      await action.focus();
      await expect(action).toBeFocused();
      expect(await action.evaluate((node) => getComputedStyle(node).outlineStyle)).not.toBe("none");
      const screenshot = path.join(shots, `${theme}-folders-${width}.png`);
      await page.screenshot({ path: screenshot });
      await testInfo.attach(`${theme}-folders-${width}`, { path: screenshot, contentType: "image/png" });
    }
    empty = true;
    await page.reload();
    await expect(page.locator("[data-pb-folder-counts]")).toHaveText("0 folders · 0 pages");
    await expect(page.locator("[data-pb-folder-child]")).toHaveCount(0);
    await expect(page.getByRole("link", { name: "New page here" })).toBeVisible();
    const screenshot = path.join(shots, `${theme}-empty.png`);
    await page.screenshot({ path: screenshot });
    await testInfo.attach(`${theme}-empty`, { path: screenshot, contentType: "image/png" });
  });
}
