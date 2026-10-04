import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "./smoke-fixtures";
import { gotoAndWaitForSearchReady, gotoExpectStatus } from "./helpers";

for (const theme of ["light", "dark"] as const) {
  test(`${theme} shell stays centered and long sidebar titles fit at desktop widths`, async ({ page, smokeServer }, testInfo) => {
    await page.emulateMedia({ colorScheme: theme });
    const folder = "release-notes";
    const deepFolder = `${folder}/platform-integrations/enterprise-deployment-guides/operational-rollout-procedures-and-troubleshooting`;
    const title = "Release notes for the documentation platform and all of its integrations";
    for (const fixtureFolder of [folder, deepFolder]) {
      mkdirSync(path.join(smokeServer.contentDir, fixtureFolder), { recursive: true });
      writeFileSync(path.join(smokeServer.contentDir, fixtureFolder, "overview.md"), `---\ntitle: ${title}\n---\n# Release notes\n\nThe latest improvements to our documentation.\n`);
      await expect.poll(async () => (await page.request.get(`/api/v1/pages/by-path/docs/${fixtureFolder}/overview`)).status()).toBe(200);
    }

    const expectSidebarTitleFits = async () => {
      const current = page.locator('.pb-sidebar a[aria-current="page"]');
      await expect(current).toHaveText(title);
      await expect(current.locator("svg")).toHaveAttribute("aria-hidden", "true");
      const label = current.locator("span");
      await expect(label).toHaveAttribute("title", title);
      expect(await label.evaluate((el) => el.scrollWidth > el.clientWidth && getComputedStyle(el).textOverflow === "ellipsis")).toBe(true);
      expect(await current.evaluate((el) => getComputedStyle(el).fontWeight)).toBe("600");
      expect(await page.locator("[data-pb-sidebar]").evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    };

    for (const width of [1280, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      await gotoAndWaitForSearchReady(page, `/docs/${folder}/overview`);
      const geometry = await page.locator("[data-pb-header]").evaluate((header) => {
        const rect = (selector: string) => header.querySelector(selector)!.getBoundingClientRect().toJSON();
        return { header: header.getBoundingClientRect().toJSON(), search: rect("[data-pb-search-trigger]"),
          logo: rect("[data-pb-home]"), actions: rect(".pb-header-actions") };
      });
      expect(geometry.header.height).toBe(56);
      expect(geometry.search.width).toBe(480);
      // Center in the usable header width, excluding the browser's reserved scrollbar gutter.
      expect(Math.abs(geometry.search.x + geometry.search.width / 2 - (geometry.header.x + geometry.header.width / 2))).toBeLessThanOrEqual(1);
      expect(geometry.logo.right).toBeLessThan(geometry.search.x);
      expect(geometry.search.right).toBeLessThan(geometry.actions.x);
      await expectSidebarTitleFits();
      const sidebar = page.locator("[data-pb-sidebar]");
      await expect(sidebar.getByRole("link", { name: "Release notes", exact: true })).toHaveAttribute("href", "/docs/release-notes");
      await expect(page.locator("[data-pb-root-selector]")).toHaveCount(0);
      await page.screenshot({ path: testInfo.outputPath(`${theme}-shell-${width}.png`), fullPage: true });

      await gotoAndWaitForSearchReady(page, `/docs/${deepFolder}/overview`);
      await expectSidebarTitleFits();
      await expect(sidebar.locator('[data-pb-folder-toggle][aria-expanded="true"]')).toHaveCount(4);
      const deepLabel = sidebar.locator(`a[href="/docs/${deepFolder}"] span`);
      await expect(deepLabel).toHaveAttribute("title", "Operational rollout procedures and troubleshooting");
      expect(await deepLabel.evaluate((el) => el.scrollWidth > el.clientWidth && getComputedStyle(el).textOverflow === "ellipsis")).toBe(true);
      await page.screenshot({ path: testInfo.outputPath(`${theme}-deep-shell-${width}.png`), fullPage: true });
    }

    await gotoAndWaitForSearchReady(page, `/docs/${folder}/overview`);
    await page.locator("[data-pb-search-trigger]").click();
    await expect(page.locator("[data-pb-search-input]")).toBeFocused();
    await page.keyboard.press("Escape");
    await page.keyboard.press("ControlOrMeta+k");
    await expect(page.locator("[data-pb-search-input]")).toBeFocused();
    await page.keyboard.press("Escape");
    await page.locator("[data-pb-edit-page]").click();
    await expect(page.locator("[data-pb-editor] [data-pb-view-page]")).toBeVisible();
    await expect(page.locator("[data-pb-header] [data-pb-view-page]")).toHaveCount(0);
    await expect(page.locator("[data-pb-header] [data-pb-edit-page]")).toHaveCount(0);
    await page.screenshot({ path: testInfo.outputPath(`${theme}-editor-header.png`), fullPage: true });
    await gotoExpectStatus(page, "/docs/release-notes");
    expect(await page.locator('.pb-sidebar a[aria-current="page"]').evaluate((el) => getComputedStyle(el).fontWeight)).toBe("700");
    const currentFolder = page.locator('.pb-sidebar a[aria-current="page"]').locator("..");
    const toggle = currentFolder.locator("[data-pb-folder-toggle]");
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    const refinementScreenshots = path.resolve("../.crew/ui-designer-round-two-screenshots");
    mkdirSync(refinementScreenshots, { recursive: true });
    await page.screenshot({ path: path.join(refinementScreenshots, `${theme}-current-folder.png`), fullPage: false });
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await page.evaluate(() => history.pushState(null, "", `${location.pathname}?context=folder#same-folder`));
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
  });
}
