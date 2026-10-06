import { createHash } from "node:crypto";
import { mkdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";
import { PAGE_TEMPLATES } from "../src/lib/pageTemplates";

const current = "/docs/guides/deploy-guide";
async function open(page: import("@playwright/test").Page) {
  await expect(page.locator("a[data-pb-new-page]")).toBeVisible();
  await page.locator("[data-pb-new-page]").click();
  await expect(page.getByRole("dialog", { name: "New page" })).toBeVisible();
}

test("ordinary dialog creation persists typed frontmatter and the exact template bytes", async ({ page, smokeServer }) => {
  // No route interception: these are the actual POST response and persisted server bytes.
  await gotoExpectStatus(page, current);
  await open(page);
  await page.locator("[data-pb-new-title]").fill("OKF Reference Smoke");
  await page.getByRole("radio", { name: /^Meeting notes/ }).check();
  const responsePromise = page.waitForResponse((response) => response.request().method() === "POST"
    && new URL(response.url()).pathname === "/api/v1/pages");
  await page.locator("[data-pb-new-create]").click();
  const response = await responsePromise;
  expect(response.status()).toBe(201);
  expect(response.request().postDataJSON().type).toBe("Reference");
  const created = await response.json() as { id: string; content_hash: string; url: string };
  const template = PAGE_TEMPLATES.find((candidate) => candidate.id === "meeting")!;
  const expected = Buffer.from(`---\nid: ${created.id}\ntype: "Reference"\ntitle: "OKF Reference Smoke"\n---\n\n${template.body}`, "utf8");
  const persisted = readFileSync(path.join(smokeServer.contentDir, "guides", "okf-reference-smoke.md"));
  expect(persisted.equals(expected)).toBe(true);
  expect(new TextDecoder("utf-8", { fatal: true }).decode(persisted)).toBe(expected.toString("utf8"));
  expect(created.content_hash).toBe(`sha256:${createHash("sha256").update(persisted).digest("hex")}`);
  await expect(page).toHaveURL(`${created.url}?mode=edit`);
  await expect(page.locator(".cm-content")).toContainText("## Action items");
});

test("creation is a native modal over the page with history, focus, scroll and native-link behavior", async ({ page, context }) => {
  await gotoExpectStatus(page, current);
  await page.evaluate(() => window.scrollTo(0, 250));
  const originalScroll = await page.evaluate(() => scrollY);
  const link = page.locator("a[data-pb-new-page]");
  await expect(link).toHaveAttribute("href", "/new?root=docs&folder=guides");
  const [tab] = await Promise.all([context.waitForEvent("page"), link.click({ modifiers: ["ControlOrMeta"] })]);
  await expect(tab.getByRole("dialog", { name: "New page" })).toBeVisible();
  await expect(tab.locator("[data-pb-new-folder]")).toHaveValue(JSON.stringify(["folder", "guides"]));
  await tab.close();
  await page.locator("[data-pb-page-article] h1").evaluate((node) => { (node as HTMLElement).dataset.retained = "yes"; });
  await open(page);
  await expect(page).toHaveURL(current);
  await expect(page.locator("[data-pb-new-title]")).toBeFocused();
  expect(await page.locator("[data-pb-new-dialog]").evaluate((node) => node.matches(":modal"))).toBe(true);
  for (let i = 0; i < 14; i++) {
    await page.keyboard.press(i % 2 ? "Shift+Tab" : "Tab");
    expect(await page.evaluate(() => !!document.activeElement?.closest("dialog"))).toBe(true);
  }
  await page.keyboard.press("ControlOrMeta+k");
  await expect(page.locator("[data-pb-search]")).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(link).toBeFocused();
  expect(await page.evaluate(() => scrollY)).toBe(originalScroll);
  await expect(page.locator("[data-pb-page-article] h1")).toHaveAttribute("data-retained", "yes");
  await open(page);
  await page.locator("[data-pb-new-title]").fill("Discard this modal draft");
  await page.reload();
  await expect(page.getByRole("dialog", { name: "New page" })).toBeVisible();
  await expect(page.locator("[data-pb-new-folder]")).toHaveValue(JSON.stringify(["folder", "guides"]));
  await expect(page.locator("[data-pb-new-title]")).toHaveValue("");
  await page.locator("[data-pb-new-title]").fill("Another modal draft");
  await page.goBack();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.goForward();
  await expect(page.locator("[data-pb-new-title]")).toHaveValue("");
  await page.mouse.click(2, 2);
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

for (const outcome of ["failure", "success"] as const) {
  test(`pending create blocks Back, Forward, GO and duplicate posts until ${outcome}`, async ({ page }) => {
    await gotoExpectStatus(page, "/docs/welcome");
    await page.locator('.pb-prose a[href="/docs/guides/getting-started"]').click();
    await expect(page).toHaveURL("/docs/guides/getting-started");
    await open(page);
    // Put a real destination ahead of the modal so Forward exercises a pop blocker,
    // rather than succeeding vacuously at the newest history entry.
    await page.locator('.pb-sidebar a[href="/docs"]').evaluate((node) => (node as HTMLAnchorElement).click());
    await expect(page).toHaveURL("/docs");
    await page.goBack();
    await expect(page.getByRole("dialog", { name: "New page" })).toBeVisible();
    let release!: () => void;
    const held = new Promise<void>((done) => { release = done; });
    let posts = 0;
    await page.route("**/api/v1/pages", async (route) => {
      if (route.request().method() !== "POST") return route.continue();
      posts++;
      await held;
      if (outcome === "failure") await route.fulfill({ status: 409, json: { error: { code: "page_exists", message: "Already exists", path: "guides/taken.md" } } });
      else await route.continue();
    });
    await page.locator("[data-pb-new-title]").fill(`Pending ${Date.now()}`);
    await page.locator("[data-pb-new-create]").click();
    await expect.poll(() => posts).toBe(1);
    for (const action of ["back", "forward", "go"] as const) {
      await page.evaluate((kind) => { if (kind === "go") history.go(-2); else history[kind](); }, action);
      await page.waitForTimeout(100);
      await expect(page.getByRole("dialog", { name: "New page" })).toBeVisible();
      await expect(page).toHaveURL("/docs/guides/getting-started");
    }
    await page.keyboard.press("Escape");
    await page.locator("[data-pb-new-page-form] form").evaluate((form) => (form as HTMLFormElement).requestSubmit());
    expect(posts).toBe(1);
    await expect(page.locator("[data-pb-new-create]")).toBeDisabled();
    await expect(page.getByRole("combobox", { name: "Folder" })).toBeDisabled();
    release();
    if (outcome === "failure") {
      await expect(page.getByRole("alert")).toContainText("taken.md");
      await expect(page.locator("[data-pb-new-create]")).toBeEnabled();
      await page.goBack();
      await expect(page.getByRole("dialog")).toHaveCount(0);
    } else {
      await expect(page.locator("[data-pb-editor]")).toBeVisible();
      await expect(page).toHaveURL(/\?mode=edit$/);
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await page.goBack();
      await expect(page).toHaveURL("/docs/guides/getting-started");
    }
  });
}

test("dirty editor survives cancel and failed create, and discard refusal sends no request", async ({ page }) => {
  await gotoExpectStatus(page, `${current}?mode=edit`);
  const editor = page.locator(".cm-content");
  await expect(editor).toBeVisible();
  await editor.click();
  await page.keyboard.press("ControlOrMeta+End");
  await page.keyboard.type("\nUNSAVED ORIGINAL BUFFER");
  const original = await editor.innerText();
  await editor.evaluate((node) => { (node as HTMLElement).dataset.retained = "yes"; });
  await open(page);
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(editor).toHaveAttribute("data-retained", "yes");
  expect(await editor.innerText()).toBe(original);
  await open(page);
  await page.locator("[data-pb-new-title]").fill("Another page");
  let posts = 0;
  await page.route("**/api/v1/pages", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    posts++;
    await route.fulfill({ status: 409, json: { error: { code: "page_exists", message: "Already exists", path: "taken.md" } } });
  });
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.locator("[data-pb-new-create]").click();
  expect(posts).toBe(0);
  await expect(page.getByRole("dialog", { name: "New page" })).toBeVisible();
  page.once("dialog", (dialog) => dialog.accept());
  await page.locator("[data-pb-new-create]").click();
  await expect(page.getByRole("alert")).toContainText("taken.md");
  expect(posts).toBe(1);
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(editor).toHaveAttribute("data-retained", "yes");
  expect(await editor.innerText()).toBe(original);
});

for (const theme of ["light", "dark"] as const) {
  test(`${theme} dialog templates, editable address and desktop layout`, async ({ page }, testInfo) => {
    await page.emulateMedia({ colorScheme: theme });
    await page.setViewportSize({ width: theme === "light" ? 1280 : 1440, height: 1000 });
    await gotoExpectStatus(page, current);
    await open(page);
    const screenshots = path.resolve("../.crew/ui-new-page-dialog-screenshots");
    mkdirSync(screenshots, { recursive: true });
    expect(await page.getByText("The page address will follow your title", { exact: true }).evaluate((node) => getComputedStyle(node).fontFamily)).toContain("IBM Plex Sans");
    await expect(page.getByRole("combobox", { name: "Folder" }).locator("option:checked")).toHaveText("Guides");
    await page.locator("[data-pb-new-title]").fill("A thoughtful guide to incident response");
    await expect(page.locator("[data-pb-new-preview]")).toContainText("/docs/guides/a-thoughtful-guide-to-incident-response");
    expect(await page.locator("[data-pb-new-preview]").evaluate((node) => getComputedStyle(node).fontFamily)).toContain("JetBrains Mono");
    await page.screenshot({ path: path.join(screenshots, `${theme}-default.png`) });
    await page.getByRole("button", { name: "Edit URL" }).click();
    await expect(page.locator("[data-pb-new-slug]")).toBeFocused();
    await page.locator("[data-pb-new-slug]").fill("incident-notes");
    await expect(page.locator("[data-pb-new-preview]")).toContainText("/incident-notes");
    await page.locator("[data-pb-new-slug]").fill("");
    await expect(page.locator("[data-pb-new-preview]")).toContainText("/a-thoughtful-guide");
    await page.getByRole("radio", { name: /^Meeting notes/ }).check();
    await page.locator("[data-pb-new-folder]").selectOption(JSON.stringify(["custom"]));
    await page.locator("[data-pb-new-custom-folder]").fill("guides/long-location-name-for-the-new-team/incident-response-planning");
    const shot = path.join(screenshots, `${theme}-options.png`);
    await page.screenshot({ path: shot });
    await testInfo.attach(`${theme}-options`, { path: shot, contentType: "image/png" });
    await page.locator("[data-pb-new-create]").click();
    await expect(page.locator("[data-pb-editor]")).toBeVisible();
    await expect(page).toHaveURL(/\?mode=edit$/);
    await expect(page.locator(".cm-content")).toContainText("## Attendees");
    await expect(page.locator(".cm-content")).toContainText("## Action items");
    await page.locator("[data-pb-view-page]").click();
    await expect(page.locator("[data-pb-page-article]")).toBeVisible();
  });
}
