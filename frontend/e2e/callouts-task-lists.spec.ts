import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

const PAGE = "/docs/scratch/todo";
const SOURCE = [
  "# Callouts and tasks",
  "",
  "> [!NOTE]",
  "> Read the note body.",
  ">",
  "> - [x] Nested done",
  "> - [ ] Nested open",
  ">",
  "> > [!TIP]",
  "> > Nested tip.",
  "",
  "> [!TIP]",
  "> Try the tip.",
  "",
  "> [!IMPORTANT]",
  "> Keep the source exact.",
  "",
  "> [!WARNING]   ",
  "> Check the warning.",
  "",
  "> [!CAUTION]\\",
  "> Review the caution.",
  "",
  "- [x] Done task",
  "- [ ] Open task",
  "1. [x] Ordered done",
  "",
  "1. Ordinary ordered sibling",
  "2. [!NOTE] stays ordinary",
  "",
  "> Ordinary quoted text.",
  "",
  "## Existing diagram",
  "",
  "```mermaid",
  "flowchart LR",
  "  A[Start] --> B[Finish]",
  "```",
  "",
  "[A safe link](/docs/welcome)",
  "",
].join("\n");

test("renders inline callouts and tasks, then inserts callouts through the existing editor", async ({ page }, testInfo) => {
  await page.emulateMedia({ colorScheme: "light" });
  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  await expect(page.locator("[data-pb-editor]")).toBeVisible();

  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.insertText(SOURCE);
  await page.locator("[data-pb-save]").click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  await gotoExpectStatus(page, PAGE);
  await page.setViewportSize({ width: 1280, height: 800 });
  const prose = page.locator(".pb-prose");
  await expect(page.locator("[data-pb-rail]")).toBeVisible();
  await expect(prose.locator('.pb-callout[data-pb-callout="note"]')).toHaveCount(1);
  await expect(prose.locator('.pb-callout[data-pb-callout="tip"]')).toHaveCount(2);
  await expect(prose.locator('.pb-callout[data-pb-callout="important"]')).toHaveCount(1);
  await expect(prose.locator('.pb-callout[data-pb-callout="warning"]')).toHaveCount(1);
  await expect(prose.locator('.pb-callout[data-pb-callout="caution"]')).toHaveCount(1);
  await expect(prose.locator(".pb-callout-title").filter({ hasText: "NOTE" })).toHaveCount(1);
  await expect(prose.locator(".pb-callout-title").filter({ hasText: "TIP" })).toHaveCount(2);
  await expect(prose.locator("h2").filter({ hasText: "Existing diagram" })).toHaveCount(1);
  await expect(prose.locator("[data-pb-mermaid]")).toHaveCount(1);
  await expect(prose.locator('input.task-list-item-checkbox[disabled][aria-label="Completed task"]')).toHaveCount(3);
  await expect(prose.locator('input.task-list-item-checkbox[disabled][aria-label="Incomplete task"]')).toHaveCount(2);
  await expect(prose.locator('input.task-list-item-checkbox[checked]')).toHaveCount(3);
  const orderedTask = prose.locator("ol > li.task-list-item").filter({ hasText: "Ordered done" });
  await expect(orderedTask).toHaveCount(1);
  await expect(orderedTask).toHaveCSS("list-style-type", "decimal");
  const unorderedTasks = prose.locator("ul > li.task-list-item");
  await expect(unorderedTasks).toHaveCount(4);
  for (let i = 0; i < 4; i++) await expect(unorderedTasks.nth(i)).toHaveCSS("list-style-type", "none");
  await expect(prose.locator("blockquote").filter({ hasText: "Ordinary quoted text." })).toHaveCount(1);

  await page.screenshot({ path: testInfo.outputPath("callouts-inline-light.png"), fullPage: true });
  await page.setViewportSize({ width: 700, height: 800 });
  await page.locator("[data-pb-theme-toggle]").click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.screenshot({ path: testInfo.outputPath("callouts-dark-narrow.png"), fullPage: true });

  await page.setViewportSize({ width: 390, height: 800 });
  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  const editor = page.locator("[data-pb-editor]");
  await expect(editor).toBeVisible();
  const body = page.locator("[data-pb-codemirror] .cm-content");
  await body.click();
  await page.keyboard.press("ControlOrMeta+End");
  await expect(page.locator("[data-pb-callout-type]")).toHaveValue("NOTE");
  await page.locator("[data-pb-fmt-callout]").click();
  await page.keyboard.insertText("NOTE from the editor");

  await body.click();
  await page.keyboard.press("ControlOrMeta+End");
  await page.keyboard.press("Enter");
  await page.keyboard.press("Enter");
  await page.keyboard.insertText("===");
  for (let i = 0; i < 3; i++) await page.keyboard.press("Shift+ArrowLeft");
  await page.locator("[data-pb-callout-type]").selectOption("CAUTION");
  await page.locator("[data-pb-fmt-callout]").click();
  await page.screenshot({ path: testInfo.outputPath("callouts-editor-narrow.png"), fullPage: true });

  await page.locator("[data-pb-preview-toggle]").click();
  const preview = page.locator("[data-pb-preview] .pb-prose");
  await expect(preview.locator('.pb-callout[data-pb-callout="note"]').filter({ hasText: "NOTE from the editor" })).toHaveCount(1);
  const cautionPreview = preview.locator('.pb-callout[data-pb-callout="caution"]').filter({ hasText: "===" });
  await expect(cautionPreview).toHaveCount(1);
  await expect(cautionPreview).toContainText("===");
  await page.locator("[data-pb-preview-toggle]").click();
  await page.locator("[data-pb-save]").click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  await gotoExpectStatus(page, PAGE);
  await page.reload();
  await expect(page.locator('.pb-prose .pb-callout[data-pb-callout="note"]').filter({ hasText: "NOTE from the editor" })).toHaveCount(1);
  const cautionReader = page.locator('.pb-prose .pb-callout[data-pb-callout="caution"]').filter({ hasText: "===" });
  await expect(cautionReader).toHaveCount(1);
  await expect(cautionReader).toContainText("===");
  await expect(page.locator('.pb-prose .pb-callout[data-pb-callout="note"]')).toHaveCount(2);
  await expect(page.locator('input.task-list-item-checkbox[disabled][aria-label="Completed task"]')).toHaveCount(3);
  await expect(page.locator("[data-pb-mermaid]")).toHaveCount(1);
});
