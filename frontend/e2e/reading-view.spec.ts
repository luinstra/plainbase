import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

for (const theme of ["light", "dark"] as const) {
  test(`${theme} source links explain real outside-root versus missing-target outcomes`, async ({ page, smokeServer }) => {
    await page.emulateMedia({ colorScheme: theme });
    writeFileSync(path.join(smokeServer.contentDir, "source-links.md"), "# Source links\n\n[Source file](../server/src/main/kotlin/com/plainbase/domain/service/PageIdentityService.kt)\n\n[Missing local file](missing.kt)\n");
    await expect.poll(async () => (await page.request.get("/api/v1/pages/by-path/docs/source-links")).status()).toBe(200);
    await gotoExpectStatus(page, "/docs/source-links");
    for (const [label, reason, title] of [["Source file", "outside_content_root", "Outside this space"], ["Missing local file", "broken_missing", "Target not found"]]) {
      const trigger = page.getByRole("button", { name: label, exact: true });
      await expect(trigger).toHaveAttribute("data-pb-link-error", reason);
      await trigger.focus();
      const explanation = page.getByRole("dialog", { name: title });
      await expect(explanation).toBeVisible();
      await expect(explanation.getByRole("link", { name: "Create page" })).toHaveCount(0);
      await expect(explanation.getByRole("link", { name: "Edit link" })).toHaveAttribute("href", "/docs/source-links?mode=edit");
      await page.keyboard.press("Escape");
    }
  });
  test(`${theme} matching secondary title is shown once with its original source heading`, async ({ page, smokeServer }) => {
    await page.emulateMedia({ colorScheme: theme });
    writeFileSync(path.join(smokeServer.contentDir, "secondary-title.md"), "---\ntitle: what's up\n---\n## What's up\n\nThe original page body.\n");
    await expect.poll(async () => (await page.request.get("/api/v1/pages/by-path/docs/secondary-title")).status()).toBe(200);
    await gotoExpectStatus(page, "/docs/secondary-title");
    await expect(page.locator("[data-pb-page-article] h1, [data-pb-page-article] h2")).toHaveCount(1);
    const title = page.locator(".pb-title-prose h2");
    await expect(title).toContainText("What's up");
    await expect(title).toHaveAttribute("data-pb-src", /\d+-\d+/);
    const properties = page.getByRole("group", { name: "Page properties" });
    expect((await properties.boundingBox())!.y).toBeGreaterThan((await title.boundingBox())!.y);
    await expect(page.locator("[data-pb-prose]")).toContainText("The original page body.");
    await expect(page.locator("[data-pb-toc]")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Start a discussion" })).toHaveCount(1);
    await expect(page.getByRole("group", { name: "Discussion status" })).toHaveCount(0);
  });
  test(`${theme} reading properties, outline and contextual tools preserve source selections`, async ({ page, smokeServer }, testInfo) => {
    await page.emulateMedia({ colorScheme: theme });
    const token = `HEAD ${"a".repeat(40)}; non-Markdown content SHA-256 ${"b".repeat(64)}`;
    const longTarget = `${"missing-segment/".repeat(140)}page.md`;
    mkdirSync(path.join(smokeServer.contentDir, "reading"), { recursive: true });
    writeFileSync(path.join(smokeServer.contentDir, "reading", "layout.md"), `---\ntitle: Reading improvements\nowner: Ada Lovelace\nstatus: active\n---\n# Reading improvements\n\nA readable page with clear properties.\n\n## First section\n\n[Missing guide](missing-guide.md)\n\n[Source target](missing.kt)\n\n[Outside target](../../outside.md)\n\n[Long target](${longTarget})\n\n\x60${token}\x60\n\n${"A paragraph for scrolling.\n\n".repeat(22)}## Second section\n\nClosing thoughts.\n`);
    await expect.poll(async () => (await page.request.get("/api/v1/pages/by-path/docs/reading/layout")).status()).toBe(200);
    for (const width of [1280, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      await gotoExpectStatus(page, "/docs/reading/layout");
      const properties = page.getByRole("group", { name: "Page properties" });
      await expect(properties).toContainText("Ada Lovelace");
      await expect(properties).toContainText("reading/layout.md");
      const titleBox = await page.locator("[data-pb-page-article] h1").boundingBox();
      const propertiesBox = await properties.boundingBox();
      expect(propertiesBox!.y).toBeGreaterThan(titleBox!.y + titleBox!.height);
      expect(await properties.evaluate((node) => node.closest(".pb-prose") === null)).toBe(true);
      const rail = page.locator("[data-pb-rail]");
      expect(await rail.locator("[data-pb-toc]").evaluate((node) => node === node.parentElement!.firstElementChild)).toBe(true);
      await page.locator("#second-section").scrollIntoViewIfNeeded();
      await expect(page.getByRole("link", { name: "Second section", exact: true })).toHaveAttribute("aria-current", "location");
      await page.evaluate(() => window.scrollTo(0, 0));
      const missing = page.getByRole("button", { name: "Missing guide" });
      await missing.focus();
      const card = page.getByRole("dialog", { name: "This page doesn't exist" });
      await expect(card).toContainText("missing-guide.md");
      await page.keyboard.press("Tab");
      await expect(card.getByRole("link", { name: "Create page" })).toBeFocused();
      await page.keyboard.press("Escape");
      await expect(card).toHaveCount(0);
      await expect(missing).toBeFocused();
      for (const [label, title] of [["Source target", "Target not found"], ["Outside target", "Outside this space"]]) {
        await page.getByRole("button", { name: label, exact: true }).focus();
        const explanation = page.getByRole("dialog", { name: title });
        await expect(explanation).toBeVisible();
        await expect(explanation.getByRole("link", { name: "Create page" })).toHaveCount(0);
        await page.keyboard.press("Tab");
        await expect(explanation.getByRole("link", { name: "Edit link" })).toBeFocused();
        await page.keyboard.press("Escape");
      }
      await page.getByRole("button", { name: "Long target", exact: true }).focus();
      await expect(card).toBeVisible();
      const cardBox = await card.boundingBox();
      expect(cardBox!.y).toBeGreaterThanOrEqual(0);
      expect(cardBox!.y + cardBox!.height).toBeLessThanOrEqual(1000);
      await page.keyboard.press("Tab");
      await expect(card.getByRole("link", { name: "Create page" })).toBeFocused();
      await expect(card.getByRole("link", { name: "Create page" })).toBeInViewport();
      await page.keyboard.press("Escape");
      await expect(card).toHaveCount(0);
      await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
      const chip = page.locator(".pb-inline-token code");
      await expect(chip).toHaveText(token);
      expect(await chip.evaluate((node) => node.scrollWidth > node.clientWidth)).toBe(true);
      expect((await chip.boundingBox())!.width).toBeLessThan(400);
      const clippedHeight = (await chip.boundingBox())!.height;
      const reveal = page.getByRole("button", { name: "Show full code" });
      await reveal.focus();
      await page.keyboard.press("Enter");
      const collapse = page.getByRole("button", { name: "Collapse code" });
      await expect(collapse).toHaveAttribute("aria-expanded", "true");
      await expect(collapse).toBeFocused();
      await expect(chip).toHaveText(token);
      expect(await chip.evaluate((node) => node.scrollWidth <= node.clientWidth + 1)).toBe(true);
      expect((await chip.boundingBox())!.height).toBeGreaterThan(clippedHeight);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      const refinementScreenshots = path.resolve("../.crew/ui-designer-round-two-screenshots");
      mkdirSync(refinementScreenshots, { recursive: true });
      await page.screenshot({ path: path.join(refinementScreenshots, `${theme}-expanded-code-${width}.png`), fullPage: false });
      await collapse.click();
      await expect(reveal).toBeFocused();
      expect(await chip.evaluate((node) => node.scrollWidth > node.clientWidth)).toBe(true);
      await reveal.click();
      await expect(collapse).toHaveAttribute("aria-expanded", "true");
      await page.getByRole("button", { name: "Copy code" }).click();
      expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(token);
      await expect(collapse).toHaveAttribute("aria-expanded", "true");
      await page.evaluate(() => {
        const code = document.querySelector(".pb-inline-token code")!;
        const control = document.querySelector('.pb-inline-token button[aria-expanded="true"]')!;
        const range = document.createRange(); range.setStart(code.firstChild!, 0); range.setEndAfter(control);
        const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range);
        document.dispatchEvent(new Event("selectionchange"));
      });
      await page.getByRole("button", { name: "Start a discussion" }).click();
      await expect(page.getByRole("alert")).toContainText("Select only page text, without page properties or controls");
      await page.evaluate(() => {
        const title = document.querySelector("[data-pb-page-article] h1")!.firstChild!;
        const body = document.querySelector("[data-pb-page-article] [data-pb-prose] p")!.firstChild!;
        const range = document.createRange(); range.setStart(title, 0); range.setEnd(body, 5);
        const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range);
        document.dispatchEvent(new Event("selectionchange"));
      });
      await page.getByRole("button", { name: "Start a discussion" }).click();
      await expect(page.getByRole("alert")).toContainText("Select only page text, without page properties or controls");
      await expect(page.getByRole("button", { name: "Confirm passage" })).toHaveCount(0);
      await page.evaluate(() => {
        const code = document.querySelector(".pb-inline-token code")!;
        const range = document.createRange(); range.selectNodeContents(code);
        const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range);
        document.dispatchEvent(new Event("selectionchange"));
      });
      await page.getByRole("button", { name: "Start a discussion" }).click();
      await expect(page.getByRole("heading", { name: "Discuss selected passage" })).toBeVisible();
      await expect(page.locator("[data-pb-discussion-panel] blockquote")).toContainText(token);
      await page.getByRole("button", { name: "Cancel", exact: true }).click();
      await collapse.click();
      await page.evaluate(() => { window.getSelection()?.removeAllRanges(); window.scrollTo(0, 0); });
      if (theme === "dark") await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
      else await expect(page.locator("html")).not.toHaveAttribute("data-theme", "dark");
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      await page.screenshot({ path: testInfo.outputPath(`${theme}-reading-${width}.png`), fullPage: false });
    }
  });
}
