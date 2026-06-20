import { expect, test } from "@playwright/test";

/**
 * W6 acceptance #6 (Playwright, real server): the edit→preview→save→reflect flow and the real
 * concurrent-409 `content_changed` path, both end-to-end against the installed server serving
 * fixtures/demo-docs. Mirrors smoke.spec.ts (resolve an id via the by-path API, drive the SPA).
 *
 * These specs MUTATE content via PUT/POST. They run in their own Playwright PROJECT pinned to a webServer
 * with `reuseExistingServer: false` (playwright.config.ts) — so a fresh, isolated `smoke-server.mjs`
 * (serving the throwaway `.smoke-content` copy) ALWAYS boots for them and a write can never land on a
 * foreign server reused over a dev's real docs. The read-only specs keep `reuseExistingServer: !CI` for
 * fast local iteration.
 */

const PAGE = "/docs/guides/deploy-guide";

test("edit a fixture page: preview updates, save persists, the reading view reflects it", async ({ page }) => {
  const marker = `e2e edit ${Date.now()}`;

  await page.goto(`${PAGE}?mode=edit`);
  const editor = page.locator("[data-pb-editor]");
  await expect(editor).toBeVisible();

  // Type into CodeMirror's contenteditable, open the on-demand preview, then assert the debounced server
  // preview re-renders. (Preview is hidden by default — the form rail owns the right pane until toggled.)
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("End");
  await content.pressSequentially(`\n\n${marker}\n`);
  await page.locator("[data-pb-preview-toggle]").click();
  await expect(page.locator("[data-pb-preview] .pb-prose")).toContainText(marker);

  const save = page.locator("[data-pb-save]");
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  // Return to the reading view; the saved marker is part of the rendered page.
  await page.goto(PAGE);
  await expect(page.locator(".pb-prose")).toContainText(marker);
});

test("edit a metadata field via the rail form: save persists, the read view's rail reflects it", async ({ page }) => {
  await page.goto(`${PAGE}?mode=edit`);
  await expect(page.locator("[data-pb-meta-form]")).toBeVisible();

  // Change the status via the rail form's dropdown (a surgical frontmatter edit, not a body edit).
  const status = page.locator("[data-pb-field-status]");
  await status.selectOption("review");

  const save = page.locator("[data-pb-save]");
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  // The read view's rail shows the new status chip.
  await page.goto(PAGE);
  await expect(page.locator('[data-pb-rail] [data-pb-chip-status="review"]')).toBeVisible();
});

test("a concurrent edit shows the content_changed conflict and keeps the buffer", async ({ page, request }) => {
  // Resolve the page id + its current content_hash (the GET ETag IS the accepted If-Match).
  const byPath = await request.get("/api/v1/pages/by-path/guides/deploy-guide");
  expect(byPath.ok()).toBe(true);
  const { id, markdown } = (await byPath.json()) as { id: string; markdown: string };
  const get = await request.get(`/api/v1/pages/${id}`);
  const baseHash = (get.headers()["etag"] ?? "").replaceAll('"', "");

  await page.goto(`${PAGE}?mode=edit`);
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
});
