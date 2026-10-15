import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type Page } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";
import { normalizeColors } from "./theme-colors";

type Color = [number, number, number, number];
const baseline = JSON.parse(readFileSync(new URL("./fixtures/theme-baseline.json", import.meta.url), "utf8")) as {
  modes: { dark: { sections: { editor: Record<string, { elements: Array<Record<string, Color>> }> } } };
};
const editorPath = "/docs/scratch/todo?mode=edit";

async function documentText(page: Page): Promise<string> {
  return page.locator("[data-pb-codemirror] .cm-line").evaluateAll((lines) => lines.map((line) => line.textContent).join("\n"));
}

async function gutterColor(page: Page): Promise<Color> {
  const value = await page.locator(".cm-gutters").evaluate((element) => getComputedStyle(element).backgroundColor);
  const [color] = await normalizeColors(page, [value]);
  return color.map((channel) => Number(channel.toFixed(6))) as Color;
}

async function lineGeometry(page: Page) {
  return page.locator(".cm-editor").evaluate((editor) => {
    const content = editor.querySelector<HTMLElement>(".cm-content")!;
    const scroller = editor.querySelector<HTMLElement>(".cm-scroller")!;
    const viewport = scroller.getBoundingClientRect();
    const lines = [...content.querySelectorAll<HTMLElement>(".cm-line")];
    const gutters = [...editor.querySelectorAll<HTMLElement>(".cm-lineNumbers .cm-gutterElement")];
    return {
      firstLineHeight: lines[0].getBoundingClientRect().height,
      fontSize: getComputedStyle(content).fontSize,
      scroller: { width: viewport.width, height: viewport.height },
      lines: lines.flatMap((line, index) => {
        const rect = line.getBoundingClientRect();
        if (rect.bottom <= Math.max(0, viewport.top) || rect.top >= Math.min(innerHeight, viewport.bottom)) return [];
        const gutter = gutters.find((element) => element.textContent === String(index + 1) && element.getBoundingClientRect().height > 0);
        const gutterHeight = gutter?.getBoundingClientRect().height ?? -1;
        return [{ number: index + 1, lineHeight: rect.height, gutterHeight, delta: Math.abs(gutterHeight - rect.height) }];
      }),
    };
  });
}

test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
});

test("display mode preserves the editor, selection and undo while recoloring", async ({ page }, testInfo) => {
  await gotoExpectStatus(page, editorPath);
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await expect(content).toBeVisible();
  await expect(page.locator("html")).not.toHaveAttribute("data-theme");
  const before = await documentText(page);
  const lightGutter = await gutterColor(page);
  const expectedGutter = baseline.modes.dark.sections.editor[".cm-gutters"].elements[0]["background-color"];
  expect(lightGutter, "light gutter differs from the dark baseline").not.toEqual(expectedGutter);
  const marker = "Unsaved display mode marker";
  const word = "selection";
  await content.focus();
  await content.press("ControlOrMeta+End");
  await page.keyboard.insertText(`\n${marker} ${word}`);
  const typed = await documentText(page);
  expect(typed.endsWith(`${marker} ${word}`)).toBe(true);
  for (let index = 0; index < word.length; index++) await page.keyboard.press("Shift+ArrowLeft");
  expect(await page.evaluate(() => window.getSelection()?.toString()), "the last word is selected before toggle").toBe(word);
  const original = await page.locator(".cm-editor").elementHandle();
  if (!original) throw new Error("Editor did not mount");
  await original.evaluate((element) => { (element as HTMLElement & { __pbRetainedEditor: boolean }).__pbRetainedEditor = true; });

  await page.locator("[data-pb-theme-toggle]").click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  const sameElement = await page.locator(".cm-editor").evaluate((element, original) => element === original, original);
  const tagged = await page.locator(".cm-editor").evaluate((element) =>
    (element as HTMLElement & { __pbRetainedEditor?: boolean }).__pbRetainedEditor === true);
  expect.soft(sameElement, "the editor element is retained").toBe(true);
  expect.soft(tagged, "the editor expando is retained").toBe(true);
  await content.focus();
  await page.keyboard.insertText("Z");
  const edited = await documentText(page);
  expect.soft(edited, "typing replaces the selected word").toBe(typed.slice(0, -word.length) + "Z");
  expect.soft(edited, "the unsaved marker remains in the buffer").toContain(marker);
  const darkGutter = await gutterColor(page);
  expect.soft(darkGutter, "the gutter uses the frozen dark background").toEqual(expectedGutter);

  const undoTexts = [edited];
  for (let presses = 0; presses < 10 && undoTexts.at(-1) !== before; presses++) {
    await content.press("ControlOrMeta+z");
    const text = await documentText(page);
    const previous = undoTexts.at(-1);
    undoTexts.push(text);
    if (text === previous) break;
  }
  expect.soft(undoTexts.at(-1), "undo restores the pre-edit document").toBe(before);
  writeFileSync(testInfo.outputPath("editor-retention.json"), JSON.stringify({
    before, typed, edited, sameElement, tagged, lightGutter, darkGutter, expectedGutter, undoTexts,
  }, null, 2) + "\n");
});

test("display mode keeps visible line-number heights aligned after a font change", async ({ page, request, smokeServer }, testInfo) => {
  writeFileSync(path.join(smokeServer.dataDir, "custom.css"), ':root[data-theme="dark"] .cm-content { font-size: 1.6em; }\n');
  const current = await request.get("/api/v1/pages/by-path/docs/scratch/todo");
  expect(current.ok()).toBe(true);
  const record = await current.json() as { id: string; content_hash: string };
  const lines = Array.from({ length: 8 }, (_, index) => index % 2 === 0
    ? `Short document line ${index + 1}.`
    : `Wrapped document line ${index + 1}: ` + "This sentence makes the editor wrap its visible source. ".repeat(5));
  const saved = await request.put(`/api/v1/pages/${record.id}?root=docs`, {
    headers: { "content-type": "text/markdown", "if-match": `"${record.content_hash}"` },
    data: "---\ntitle: Editor line geometry\n---\n" + lines.join("\n"),
  });
  expect(saved.ok(), await saved.text()).toBe(true);
  await page.setViewportSize({ width: 1280, height: 1800 });
  await gotoExpectStatus(page, editorPath);
  await expect(page.locator(".cm-line")).toHaveCount(lines.length);
  await page.evaluate(() => document.fonts.ready);
  await expect.poll(async () => {
    const reading = await lineGeometry(page);
    return reading.lines.length >= 3 && reading.lines.every((line) => line.delta <= 1);
  }, { timeout: 2000, message: "light line numbers match each visible line" }).toBe(true);
  const light = await lineGeometry(page);
  expect(light.lines.some((line) => line.lineHeight > light.firstLineHeight + 10), "the document includes wrapped lines").toBe(true);

  await page.locator("[data-pb-theme-toggle]").click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  const afterToggle = await lineGeometry(page);
  const heightChange = Math.abs(afterToggle.firstLineHeight - light.firstLineHeight);
  expect.soft(heightChange, "the mode-specific font changes line height by at least ten pixels").toBeGreaterThanOrEqual(10);
  let dark = afterToggle;
  await expect.soft.poll(async () => {
    dark = await lineGeometry(page);
    return dark.lines.length >= 3 && dark.lines.every((line) => line.delta <= 1);
  }, { timeout: 2000, message: "dark line numbers match each visible line" }).toBe(true);
  writeFileSync(testInfo.outputPath("editor-geometry.json"), JSON.stringify({ light, afterToggle, heightChange, dark }, null, 2) + "\n");
});
