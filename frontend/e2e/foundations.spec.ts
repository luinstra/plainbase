import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type Page } from "./smoke-fixtures";
import { gotoAndWaitForSearchReady, gotoExpectStatus } from "./helpers";

async function measure(page: Page, selector: string, pseudo: string | null = null) {
  const elements = page.locator(selector);
  await expect(elements.first(), selector).toBeVisible();
  expect(await elements.count(), selector).toBeGreaterThan(0);
  return elements.evaluateAll((elements, pseudo) => {
    type Color = [number, number, number, number];
    const canvas = document.createElement("canvas").getContext("2d")!;
    function color(value: string): Color {
      if (!CSS.supports("color", value)) throw new Error(`Unsupported color: ${value}`);
      canvas.fillStyle = value;
      const normalized = canvas.fillStyle;
      if (normalized.startsWith("#")) {
        const digits = normalized.slice(1);
        return [0, 2, 4].map((i) => parseInt(digits.slice(i, i + 2), 16) / 255).concat(digits.length === 8 ? parseInt(digits.slice(6), 16) / 255 : 1) as Color;
      }
      const numbers = normalized.match(/[\d.]+/g)?.map(Number);
      if (!numbers || numbers.length < 3) throw new Error(`Unparsed color: ${normalized}`);
      if (normalized.startsWith("color(srgb ")) return [numbers[0], numbers[1], numbers[2], numbers[3] ?? 1];
      if (normalized.startsWith("rgb")) return [numbers[0] / 255, numbers[1] / 255, numbers[2] / 255, numbers[3] ?? 1];
      canvas.clearRect(0, 0, 1, 1);
      canvas.fillRect(0, 0, 1, 1);
      return [...canvas.getImageData(0, 0, 1, 1).data].map((n) => n / 255) as Color;
    }
    const over = (a: Color, b: Color): Color => [0, 1, 2].map((i) => a[i] * a[3] + b[i] * (1 - a[3])).concat(1) as Color;
    const luminance = (c: Color) => c.slice(0, 3).map((v) => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4).reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
    const contrast = (a: Color, b: Color) => { const x = luminance(over(a, b)); const y = luminance(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    return elements.map((element) => {
      const style = getComputedStyle(element, pseudo);
      const probe = document.createElement("span");
      element.parentElement!.appendChild(probe);
      const token = (name: string) => { probe.style.color = `var(--pb-${name})`; return color(getComputedStyle(probe).color); };
      let bg = token("surface");
      const ancestors: Element[] = [];
      for (let node: Element | null = element; node; node = node.parentElement) ancestors.unshift(node);
      for (const node of ancestors.slice(0, -1)) bg = over(color(getComputedStyle(node).backgroundColor), bg);
      const parentBg = bg;
      bg = over(color(getComputedStyle(element).backgroundColor), bg);
      if (pseudo === "::selection") bg = over(color(style.backgroundColor), bg);
      const ink = color(style.color);
      const background = color(style.backgroundColor);
      const result = {
        ink, background, contrast: contrast(ink, bg), fontSize: parseFloat(style.fontSize), family: style.fontFamily,
        weight: style.fontWeight, transform: style.textTransform, opacity: style.opacity,
        tokens: Object.fromEntries(["surface-chrome", "surface-field", "surface-raised", "selection-text", "selection-bg", "text-muted", "primary-text"].map((name) => [name, token(name)])),
        fillContrast: contrast(background, parentBg),
        markContrast: contrast(color(pseudo === "::before" ? style.borderRightColor : style.color), bg),
        size: { width: element.getBoundingClientRect().width, height: element.getBoundingClientRect().height },
      };
      probe.remove();
      return result;
    });
  }, pseudo);
}
async function readable(page: Page, selector: string, pseudo: string | null = null, minimum = 4.5) {
  for (const item of await measure(page, selector, pseudo)) expect.soft(pseudo === "::before" ? item.markContrast : item.contrast, `${selector} ${pseudo ?? ""}`).toBeGreaterThanOrEqual(minimum);
}
async function selected(page: Page, selector: string) {
  await page.locator(selector).first().evaluate((element) => {
    const range = document.createRange(); range.selectNodeContents(element);
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range);
  });
  expect(await page.evaluate(() => window.getSelection()?.toString().length)).toBeGreaterThan(0);
  for (const item of await measure(page, selector, "::selection")) {
    expect.soft(item.ink).toEqual(item.tokens["selection-text"]);
    expect.soft(item.background).toEqual(item.tokens["selection-bg"]);
    expect.soft(item.contrast).toBeGreaterThanOrEqual(4.5);
  }
}
const markdown = [
  "---", "title: Foundations fixture", "owner: Ada Lovelace", "status: active", "---", "",
  "# Foundations fixture", "", "Inline `code` and normal text.", "",
  "[Missing editor guide](missing-editor-foundations.md)", "",
  ...["NOTE", "WARNING", "CAUTION"].flatMap((kind) => [`> [!${kind}]`, "> [Readable link](https://example.com)", ">", `> > [!${kind}]`, "> > [Nested link](https://example.com)", ""]),
  "```javascript", '// comment old', 'const message = "old";', "```", "",
  "```mermaid", "flowchart LR", " A[Start] --> B[Finish]", "```", "",
].join("\n");

test("contrast measurement waits for delayed page metadata", async ({ page, smokeServer }) => {
  writeFileSync(path.join(smokeServer.contentDir, "delayed-foundations.md"), markdown);
  const endpoint = "/api/v1/pages/by-path/docs/delayed-foundations";
  await expect.poll(async () => (await page.request.get(endpoint)).status()).toBe(200);
  let release!: () => void;
  let requested!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  const requestSeen = new Promise<void>((resolve) => { requested = resolve; });
  await page.route(`**${endpoint}`, async (route) => {
    requested();
    await held;
    await route.continue();
  });
  try {
    await gotoExpectStatus(page, "/docs/delayed-foundations");
    await requestSeen;
    const selector = '[data-pb-chip-status="active"]';
    expect(await page.locator(selector).count()).toBe(0);
    const measurement = measure(page, selector).then((items) => ({ items, error: null }), (error: unknown) => ({ items: null, error }));
    // Cross a browser roundtrip while the real metadata response is still held.
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    release();
    const result = await measurement;
    expect(result.error).toBeNull();
    expect(result.items).toHaveLength(1);
    expect(result.items![0].contrast).toBeGreaterThanOrEqual(4.5);
  } finally {
    release();
    await page.unrouteAll({ behavior: "wait" });
  }
});

for (const theme of ["light", "dark"] as const) {
  test(`${theme} foundations chrome, reading, selection and search`, async ({ page, smokeServer }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 1100 });
    await page.emulateMedia({ colorScheme: theme });
    writeFileSync(path.join(smokeServer.contentDir, "foundations.md"), markdown);
    await expect.poll(async () => (await page.request.get("/api/v1/pages/by-path/docs/foundations")).status()).toBe(200);
    await gotoAndWaitForSearchReady(page, "/docs/foundations");
    for (const selector of ["[data-pb-header]", "[data-pb-sidebar]"]) {
      for (const item of await measure(page, selector)) expect.soft(item.background).toEqual(item.tokens["surface-chrome"]);
    }
    for (const item of await measure(page, "[data-pb-search-trigger]")) expect.soft(item.background).toEqual(item.tokens["surface-field"]);
    for (const selector of [".pb-rail-head", ".pb-meta-key"]) {
      for (const item of await measure(page, selector)) { expect.soft(item.fontSize).toBeGreaterThanOrEqual(12); expect.soft(item.family).toContain("IBM Plex Sans"); expect.soft(item.transform).not.toBe("uppercase"); }
    }
    for (const selector of [".pb-callout a", ".pb-discussion-primary", ".hljs-comment", ".pb-avatar", "[data-pb-chip-status]"]) await readable(page, selector);
    for (const item of await measure(page, ".pb-avatar")) { expect.soft(item.fontSize).toBe(12); expect.soft(item.family).toContain("IBM Plex Sans"); expect.soft(item.size.height).toBeGreaterThanOrEqual(20); }
    const avatar = page.locator(".pb-avatar").first();
    const fitting = await avatar.evaluate((element) => {
      const range = document.createRange(); range.selectNodeContents(element);
      const text = range.getBoundingClientRect(); const circle = element.getBoundingClientRect();
      return text.width <= circle.width && text.height <= circle.height;
    });
    expect.soft(fitting, "12px initials fit inside the avatar").toBe(true);
    await readable(page, ".pb-folder-caret", "::before", 3);
    await readable(page, "[data-pb-theme-toggle]", null, 3);
    for (const item of await measure(page, ".pb-prose :not(pre) > code")) expect.soft(item.fontSize).toBeGreaterThanOrEqual(12);
    await expect(page.locator("[data-pb-mermaid] svg")).toBeVisible();
    await page.locator(".pb-callout a").first().hover();
    await readable(page, ".pb-callout a");
    await page.screenshot({ path: testInfo.outputPath(`${theme}-reading-mermaid-avatar.png`), fullPage: true });
    await selected(page, ".pb-breadcrumbs .text-faint");
    await page.screenshot({ path: testInfo.outputPath(`${theme}-selected-faint.png`), fullPage: true });
    await selected(page, ".hljs-comment");
    await page.screenshot({ path: testInfo.outputPath(`${theme}-selected-comment.png`), fullPage: true });
    await page.keyboard.press("ControlOrMeta+k");
    await expect(page.locator("[data-pb-search-input]")).toBeFocused();
    await expect(page.locator("[data-pb-search-panel]")).toBeVisible();
    for (const item of await measure(page, ".pb-search-grouplabel")) { expect.soft(item.fontSize).toBe(12); expect.soft(item.weight).toBe("600"); expect.soft(item.family).toContain("IBM Plex Sans"); }
    await page.locator("[data-pb-search-input]").fill("foundations");
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("[data-pb-search-active] .text-faint")).toBeVisible();
    await readable(page, "[data-pb-search-active] .text-faint");
    await page.screenshot({ path: testInfo.outputPath(`${theme}-search.png`), fullPage: true });
    await page.keyboard.press("Escape");
    mkdirSync(path.join(smokeServer.contentDir, "foundation-states"));
    const statuses = ["active", "draft", "review", "archived", "deprecated"];
    for (const status of statuses) writeFileSync(path.join(smokeServer.contentDir, "foundation-states", status + ".md"), "---\ntitle: " + status + "\nstatus: " + status + "\n---\nStatus fixture\n");
    for (const status of statuses) {
      await expect.poll(async () => (await page.request.get(`/api/v1/pages/by-path/docs/foundation-states/${status}`)).status()).toBe(200);
      await gotoExpectStatus(page, `/docs/foundation-states/${status}`);
      await readable(page, `[data-pb-chip-status="${status}"]`);
    }
    await gotoExpectStatus(page, "/docs/foundation-states");
    await expect(page.locator(".pb-page-row .pb-pdot")).toHaveCount(0);
    for (const status of statuses) {
      const row = `.pb-page-row[data-pb-status="${status}"]`;
      await readable(page, `${row} .pt`);
      await readable(page, `${row} [data-pb-row-chevron]`, null, 3);
      await page.locator(row).hover();
      await readable(page, `${row} .pt`);
      await readable(page, `${row} [data-pb-row-chevron]`, null, 3);
    }
    for (const item of await measure(page, ".pb-listing-label")) { expect.soft(item.fontSize).toBe(13); expect.soft(item.weight).toBe("600"); expect.soft(item.family).toContain("IBM Plex Sans"); }
    await page.screenshot({ path: testInfo.outputPath(`${theme}-statuses.png`), fullPage: true });
  });

  test(`${theme} editor, placeholders and real diff colors`, async ({ page, request }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.emulateMedia({ colorScheme: theme });
    const response = await request.get("/api/v1/pages/by-path/docs/scratch/todo");
    expect(response.ok()).toBe(true);
    const { id } = await response.json() as { id: string };
    async function save(data: string) {
      const current = await request.get(`/api/v1/pages/${id}`);
      const result = await request.put(`/api/v1/pages/${id}`, { headers: { "content-type": "text/markdown", "if-match": current.headers()["etag"] }, data });
      expect(result.ok()).toBe(true);
    }
    await save(markdown);
    await save(markdown.replaceAll("old", "new"));
    await gotoExpectStatus(page, "/docs/scratch/todo?mode=edit");
    await expect(page.locator("[data-pb-editor]")).toBeVisible();
    await expect(page.locator(".pb-editor-link-mark")).toBeVisible();
    await readable(page, ".pb-editor-property summary span, .pb-editor-toolbar button, .pb-editor-modes button, .cm-lineNumbers .cm-gutterElement:not(:empty)");
    await readable(page, ".pb-editor-link-mark", null, 3);
    await page.locator(".pb-editor-property summary").filter({ hasText: "Owner" }).click();
    await page.locator("[data-pb-field-owner]").fill("");
    await readable(page, "[data-pb-field-owner]", "::placeholder");
    for (const item of await measure(page, "[data-pb-field-owner]", "::placeholder")) { expect.soft(item.ink).toEqual(item.tokens["text-muted"]); expect.soft(item.opacity).toBe("1"); }
    await readable(page, "[data-pb-save]");
    for (const item of await measure(page, "[data-pb-save]")) expect.soft(item.ink).toEqual(item.tokens["primary-text"]);
    await page.locator(".cm-content").focus();
    await selected(page, '.cm-line:has-text("comment")');
    await page.screenshot({ path: testInfo.outputPath(`${theme}-editor-selection.png`), fullPage: true });
    await page.locator("[data-pb-save]").click();
    await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();
    await page.route("**/api/v1/pages/*/diff?*", async (route) => {
      const response = await route.fetch();
      const body = await response.json();
      await route.fulfill({ response, json: { ...body, path: "foundations.js" } });
    });
    await gotoExpectStatus(page, "/docs/scratch/todo?mode=history");
    const commits = page.locator("[data-pb-commit] button");
    await expect(commits).toHaveCount(3);
    await commits.nth(1).click(); await commits.nth(2).click();
    await expect(page.locator("[data-pb-diff]")).toBeVisible();
    for (const kind of ["add", "del"]) {
      for (const syntax of ["comment", "string", "keyword"]) await readable(page, `[data-pb-diff-line="${kind}"] .hljs-${syntax}`);
      await readable(page, `[data-pb-diff-line="${kind}"] .pb-diff-gutter`);
    }
    await page.screenshot({ path: testInfo.outputPath(`${theme}-diff.png`), fullPage: true });
    await gotoExpectStatus(page, "/new");
    await expect(page.locator("[data-pb-new-title]")).toBeVisible();
    await readable(page, "[data-pb-new-title]", "::placeholder");
    await readable(page, ".pb-template-card strong, .pb-template-card .text-faint");
    for (const item of await measure(page, "[data-pb-new-title]", "::placeholder")) { expect.soft(item.ink).toEqual(item.tokens["text-muted"]); expect.soft(item.opacity).toBe("1"); }
    await page.locator("[data-pb-new-title]").fill("Foundations new page");
    await readable(page, "[data-pb-new-create]");
    await page.screenshot({ path: testInfo.outputPath(`${theme}-new-page.png`), fullPage: true });
    await gotoExpectStatus(page, "/docs/scratch/todo?mode=edit");
    await expect(page.locator("[data-pb-editor]")).toBeVisible();
    await page.locator(".pb-editor-property summary").filter({ hasText: "Owner" }).click();
    await page.locator("[data-pb-field-owner]").fill("Missing page draft");
    // Exercise the conflict presentation with the same wire fixture as editor-conflict.test.tsx.
    await page.route(`**/api/v1/pages/${id}?*`, async (route) => {
      if (route.request().method() !== "PUT") return route.continue();
      await route.fulfill({ status: 409, json: { error: { code: "conflict", reason: "page_deleted", message: "The page no longer exists on disk.", current_content: null, current_hash: null, current_path: null } } });
    });
    await page.locator("[data-pb-save]").click();
    await expect(page.locator("[data-pb-save-as-new]")).toBeVisible();
    await readable(page, "[data-pb-save-as-new]", null, theme === "dark" ? 6.5 : 4.5);
    for (const item of await measure(page, "[data-pb-save-as-new]")) expect.soft(item.ink).toEqual(item.tokens["primary-text"]);
    await page.screenshot({ path: testInfo.outputPath(`${theme}-save-as-new.png`), fullPage: true });
  });
}
