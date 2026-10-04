import { expect, test } from "./smoke-fixtures";
import { expectNoReload, gotoExpectStatus, plantNoReloadMarker } from "./helpers";

/**
 * W6 acceptance #6 (Playwright, real server): the edit→preview→save→reflect flow and the real
 * concurrent-409 `content_changed` path, both end-to-end against the installed server serving
 * fixtures/demo-docs. Mirrors smoke.spec.ts (resolve an id via the by-path API, drive the SPA).
 *
 * Each test gets a fresh fixture-owned server and content copy, so retries and repeats cannot reuse a
 * prior metadata or content mutation.
 */

const PAGE = "/docs/guides/deploy-guide";

test("edit a fixture page: preview updates, save persists, the reading view reflects it", async ({ page }) => {
  const marker = `e2e edit ${Date.now()}`;

  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  const editor = page.locator("[data-pb-editor]");
  await expect(editor).toBeVisible();
  const view = page.locator("[data-pb-editor]").getByRole("button", { name: "Done editing" });
  await expect(view).toHaveText("Done");

  // Type into CodeMirror's contenteditable, open the on-demand preview, then assert the debounced server
  // preview re-renders. (Preview is hidden by default — the form rail owns the right pane until toggled.)
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("End");
  await content.pressSequentially(`\n\n${marker}\n`);

  // Canceling Done keeps the unsaved buffer and mode intact.
  page.once("dialog", async (dialog) => {
    expect(dialog.type()).toBe("confirm");
    expect(dialog.message()).toContain("Discard unsaved changes");
    await dialog.dismiss();
  });
  await view.click();
  await expect(page).toHaveURL(`${PAGE}?mode=edit`);
  await expect(content).toContainText(marker);
  await page.locator("[data-pb-preview-toggle]").click();
  await expect(page.locator("[data-pb-preview] .pb-prose")).toContainText(marker);

  const save = page.locator("[data-pb-save]");
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  // Return to the reading view; the saved marker is part of the rendered page.
  const dialogs: string[] = [];
  page.on("dialog", async (dialog) => { dialogs.push(dialog.message()); await dialog.dismiss(); });
  await plantNoReloadMarker(page);
  await view.click();
  await expect(page).toHaveURL(PAGE);
  await expect(page.locator("[data-pb-selection-surface]")).toContainText(marker);
  await expect(page.locator("[data-pb-header] [data-pb-edit-page]")).toBeVisible();
  await expect(page.locator("[data-pb-view-page]")).toHaveCount(0);
  await expectNoReload(page);
  expect(dialogs).toEqual([]);
});

test("format body text via the toolbar: bold persists and renders as <strong>", async ({ page }) => {
  const word = `bold${Date.now()}`;

  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  await expect(page.locator("[data-pb-editor]")).toBeVisible();

  // Type a fresh word at the end of the body, then select it back so the Bold op wraps it.
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("End");
  await content.pressSequentially(`\n\n${word}`);
  // Select the just-typed word (it sits at the line end; Shift+Home would grab the blank lines too).
  for (let i = 0; i < word.length; i++) await page.keyboard.press("Shift+ArrowLeft");

  await page.locator("[data-pb-fmt-bold]").click();

  const save = page.locator("[data-pb-save]");
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  // The reading view renders the bolded word inside a <strong>.
  await gotoExpectStatus(page, PAGE);
  await expect(page.locator(".pb-prose strong")).toContainText(word);
});

test("edit a metadata field via the rail form: save persists, the read view's rail reflects it", async ({ page }) => {
  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  await expect(page.locator("[data-pb-meta-form]")).toBeVisible();

  // A tag commits on blur, immediately before View's click. That fresh edit must
  // participate in the discard check even if no render happened between the events.
  await page.locator(".pb-editor-property summary").filter({ hasText: "Tags" }).click();
  await page.getByRole("textbox", { name: "Add tag" }).fill("view-guard");
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.locator("[data-pb-view-page]").click();
  await expect(page).toHaveURL(`${PAGE}?mode=edit`);
  await expect(page.locator(".pb-editor-property summary").filter({ hasText: "Tags" })).toContainText("view-guard");

  // Change the status via the rail form's dropdown (a surgical frontmatter edit, not a body edit).
  await page.locator(".pb-editor-property summary").filter({ hasText: "Status" }).click();
  const status = page.locator("[data-pb-field-status]");
  await status.selectOption("review");

  const save = page.locator("[data-pb-save]");
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  // The read view's rail shows the new status chip.
  await page.locator("[data-pb-view-page]").click();
  await expect(page).toHaveURL(PAGE);
  await expect(page.locator('[data-pb-rail-meta] [data-pb-chip-status="review"]')).toBeVisible();
  await expect(page.locator("[data-pb-rail-meta]")).toContainText("view-guard");
});

test("a concurrent edit shows the content_changed conflict and keeps the buffer", async ({ page, request }) => {
  // Resolve the page id + its current content_hash (the GET ETag IS the accepted If-Match).
  const byPath = await request.get("/api/v1/pages/by-path/docs/guides/deploy-guide");
  expect(byPath.ok()).toBe(true);
  const { id, markdown } = (await byPath.json()) as { id: string; markdown: string };
  const get = await request.get(`/api/v1/pages/${id}`);
  const baseHash = (get.headers()["etag"] ?? "").replaceAll('"', "");

  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  await expect(page.locator("[data-pb-editor]")).toBeVisible();

  // Mutate the SAME page out-of-band (a second writer), so the editor's base_hash is now stale.
  const oob = await request.put(`/api/v1/pages/${id}`, {
    headers: { "content-type": "text/markdown", "if-match": `"${baseHash}"` },
    data: `${markdown}\nout-of-band landed.\n`,
  });
  expect(oob.ok()).toBe(true);

  // Make a local edit and save the stale buffer → the real 409 content_changed.
  const content = page.locator("[data-pb-codemirror] .cm-content");
  const myEdit = "my unsaved edit";
  await content.click();
  await page.keyboard.press("End");
  await content.pressSequentially(`\n\n${myEdit}\n`);
  await page.locator("[data-pb-save]").click();

  const banner = page.locator('[data-pb-conflict][data-pb-conflict-reason="content_changed"]');
  await expect(banner).toBeVisible();
  await expect(banner).toContainText("out-of-band landed."); // the server's current_content is shown
  // The buffer is preserved — the user's in-progress edit is never discarded.
  await expect(page.locator("[data-pb-codemirror]")).toContainText(myEdit);

  // View can discard an explicitly confirmed draft without writing it to the page.
  page.once("dialog", (dialog) => dialog.accept());
  await page.locator("[data-pb-view-page]").click();
  await expect(page).toHaveURL(PAGE);
  await expect(page.locator("[data-pb-selection-surface]")).not.toContainText(myEdit);
  await expect(page.locator("[data-pb-selection-surface]")).toContainText("out-of-band landed.");
});
