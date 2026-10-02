import { openDiscussionActions } from "./discussion-actions";
import { writeFileSync } from "node:fs";
import path from "node:path";
import type { Locator } from "@playwright/test";
import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

async function clickFirstCharacter(paragraph: Locator) {
  const position = await paragraph.evaluate((node) => {
    const text = node.firstChild;
    if (!(text instanceof Text)) throw new Error("caret fixture needs a plain text paragraph");
    const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, 1);
    const glyph = range.getBoundingClientRect();
    const bounds = node.getBoundingClientRect();
    return { x: glyph.left - bounds.left + Math.min(1, glyph.width / 4), y: glyph.top - bounds.top + glyph.height / 2 };
  });
  // Native Home can leave the clicked offset unchanged; start at a verified real caret.
  await paragraph.click({ position });
  await expect.poll(() => paragraph.evaluate((node) => {
    const selection = window.getSelection();
    return selection?.isCollapsed && selection.anchorNode === node.firstChild && selection.anchorOffset === 0;
  })).toBe(true);
}

test("builtin cookie and CSRF allow page, passage and reply writes; expiry keeps the draft", async ({ page, smokeServer }) => {
  const seed = smokeServer.seed;
  if (!seed) throw new Error("auth smoke server did not provide credentials");
  const setup = await page.request.post("/api/v1/setup/consume", {
    data: { token: seed.setupToken, username: "discussion-admin", password: "smoke-pass-1234" },
  });
  expect(setup.status()).toBe(201);
  const csrf = ((await setup.json()) as { csrf_token: string }).csrf_token;
  const pageId = "01970000-0000-7000-8000-00000000f007";
  writeFileSync(path.join(smokeServer.contentDir, "c5-auth-write.md"),
    `---\ntitle: Auth discussion probe\nid: ${pageId}\n---\n\n# Auth discussion probe\n\nCookie selection text.\n`);
  await expect.poll(async () => (await page.request.get("/api/v1/pages/by-path/docs/c5-auth-write")).status()).toBe(200);
  await gotoExpectStatus(page, "/docs/c5-auth-write");

  await page.getByRole("button", { name: "New discussion" }).click();
  await expect(page.getByRole("textbox", { name: "Comment" })).toBeFocused();
  await page.getByRole("textbox", { name: "Comment" }).fill("Cookie page start");
  const pagePost = page.waitForResponse((response) => response.url().includes(`/api/v1/pages/${pageId}/discussions?root=docs`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "Create discussion" }).click();
  const pageResult = await pagePost;
  expect(pageResult.status()).toBe(201);
  const pageDiscussionId = ((await pageResult.json()) as { id: string }).id;
  await expect.poll(async () => (await page.request.get(`/api/v1/discussions/${pageDiscussionId}?root=docs`)).status()).toBe(200);
  await page.getByRole("button", { name: "Reply" }).click();
  await page.getByRole("textbox", { name: "Comment" }).fill("Cookie reply");
  const replyPost = page.waitForResponse((response) => response.url().includes(`/api/v1/discussions/${pageDiscussionId}/comments?root=docs`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "Post reply" }).click();
  expect((await replyPost).status()).toBe(201);
  await expect(page.getByRole("status", { name: /Reply posted/ })).toBeVisible();

  await page.getByRole("button", { name: "Close discussion", exact: true }).click();
  const paragraph = page.getByText("Cookie selection text.", { exact: true });
  await expect(paragraph).toBeVisible();
  await clickFirstCharacter(paragraph);
  await page.keyboard.press("Shift+ArrowRight");
  await page.keyboard.press("Shift+ArrowRight");
  await page.keyboard.press("Shift+ArrowRight");
  await expect.poll(() => page.evaluate(() => window.getSelection()?.toString())).toBe("Coo");
  expect(await paragraph.evaluate((node) => node.closest("[contenteditable]"))).toBeNull();
  for (let index = 0; index < 20; index++) {
    if (await page.getByRole("button", { name: "New discussion" }).evaluate((button) => button === document.activeElement)) break;
    await page.keyboard.press("Tab");
  }
  await expect(page.getByRole("button", { name: "New discussion" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("button", { name: "Confirm passage" })).toBeFocused();
  await page.getByRole("button", { name: "Confirm passage" }).click();
  await page.getByRole("textbox", { name: "Comment" }).fill("Cookie passage start");
  const quotePost = page.waitForResponse((response) => response.url().includes(`/api/v1/pages/${pageId}/discussions?root=docs`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "Create discussion" }).click();
  const quoteResult = await quotePost;
  expect(quoteResult.status()).toBe(201);
  const quoteId = ((await quoteResult.json()) as { id: string }).id;
  await expect.poll(async () => {
    const response = await page.request.get(`/api/v1/discussions/${quoteId}?root=docs`);
    return ((await response.json()) as { discussion: { anchor: { kind: string } } }).discussion.anchor.kind;
  }).toBe("quote");

  await page.getByRole("button", { name: "Reply" }).click();
  await page.getByRole("textbox", { name: "Comment" }).fill("Keep me after logout");
  const logout = await page.request.post("/api/v1/logout", { headers: { "X-CSRF-Token": csrf } });
  expect(logout.ok()).toBe(true);
  const expiredPost = page.waitForResponse((response) => response.url().includes(`/api/v1/discussions/${quoteId}/comments?root=docs`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "Post reply" }).click();
  expect((await expiredPost).status()).toBe(401);
  await expect(page.getByRole("textbox", { name: "Comment" })).toHaveValue("Keep me after logout");
  await expect(page.getByRole("alert").filter({ hasText: "session needs attention" })).toBeVisible();
});

test("builtin lifecycle enforces authorship, starter and admin rights and preserves keyboard reattachment and expired edits", async ({ page, browser, smokeServer }) => {
  test.setTimeout(120_000);
  if (!smokeServer.seed) throw new Error("auth fixture needs bootstrap credentials");
  const password = "lifecycle-pass-1234";
  const setup = await page.request.post("/api/v1/setup/consume", {
    data: { token: smokeServer.seed.setupToken, username: "lifecycle-admin", password },
  });
  expect(setup.status()).toBe(201);
  const adminCsrf = (await setup.json()).csrf_token as string;
  const user = await page.request.post("/api/v1/admin/users", { headers: { "X-CSRF-Token": adminCsrf },
    data: { username: "lifecycle-viewer", display_name: "Lifecycle viewer", role: "viewer" } });
  expect(user.status()).toBe(201);
  const resetToken = (await user.json()).reset_token as string;
  const viewerContext = await browser.newContext({ baseURL: smokeServer.baseURL, viewport: { width: 375, height: 812 } });
  try {
    const consumed = await viewerContext.request.post("/api/v1/password/reset/consume", { data: { token: resetToken, new_password: password } });
    expect(consumed.status()).toBe(204);
    expect((await (await viewerContext.request.get("/api/v1/session")).json()).authenticated).toBe(false);
    const login = await viewerContext.request.post("/api/v1/login", { data: { username: "lifecycle-viewer", password } });
    expect(login.status()).toBe(200);
    const viewerCsrf = (await login.json()).csrf_token as string;
    const viewer = await viewerContext.newPage();
    const pageId = "01970000-0000-7000-8000-00000000f009";
    writeFileSync(path.join(smokeServer.contentDir, "c5-auth-lifecycle.md"),
      `---\ntitle: Auth lifecycle\nid: ${pageId}\n---\n\n# Auth lifecycle\n\nCookie lifecycle selection.\n`);
    await expect.poll(async () => (await page.request.get(`/api/v1/pages/${pageId}/html?root=docs`)).status()).toBe(200);
    const metadata = await (await page.request.get(`/api/v1/pages/${pageId}/html?root=docs`)).json();
    const created = await page.request.post(`/api/v1/pages/${pageId}/discussions?root=docs`, { headers: { "X-CSRF-Token": adminCsrf },
      data: { anchor: { kind: "quote", content_hash: metadata.content_hash, selected_text: "Cookie lifecycle selection." }, body: "Admin starter comment" } });
    expect(created.status()).toBe(201);
    const id = (await created.json()).id as string;
    const added = await viewerContext.request.post(`/api/v1/discussions/${id}/comments?root=docs`, {
      headers: { "X-CSRF-Token": viewerCsrf }, data: { body: "**Viewer original comment** 😀" },
    });
    expect(added.status()).toBe(201);
    const commentId = (await added.json()).comment_id as string;
    const url = `/discussions/docs/${id}`;
    await gotoExpectStatus(viewer, url);
    await openDiscussionActions(viewer.locator("article.pb-discussion-comment").filter({ hasText: "Viewer original comment" }), "Edit comment");
    await viewer.locator("article.pb-discussion-comment").filter({ hasText: "Viewer original comment" }).getByRole("button", { name: "Edit comment" }).click();
    await expect(viewer.getByRole("textbox", { name: "Comment" })).toHaveValue("**Viewer original comment** 😀");
    await viewer.getByRole("textbox", { name: "Comment" }).fill("Viewer edited comment 😀");
    const saved = viewer.waitForResponse((response) => response.url().includes(`/comments/${commentId}/edit?root=docs`));
    await viewer.getByRole("button", { name: "Save comment" }).click();
    expect((await saved).status()).toBe(200);
    await expect(viewer.getByRole("status", { name: "Comment saved" })).toBeVisible();
    await expect(viewer.locator(".pb-discussion-body").filter({ hasText: "Viewer edited comment" })).toBeVisible();

    const deniedResolve = viewer.waitForResponse((response) => response.url().includes(`/discussions/${id}/resolve?root=docs`));
    await openDiscussionActions(viewer, "Resolve discussion");
    await viewer.getByRole("button", { name: "Resolve discussion" }).click();
    expect((await deniedResolve).status()).toBe(403);
    await expect(viewer.getByRole("alert").filter({ hasText: "cannot resolve" })).toBeVisible();
    await openDiscussionActions(viewer.locator("article.pb-discussion-comment").filter({ hasText: "Viewer edited comment" }), "Purge comment (admin)");
    await viewer.locator("article.pb-discussion-comment").filter({ hasText: "Viewer edited comment" }).getByRole("button", { name: "Purge comment (admin)" }).click();
    const purgeConfirm = viewer.getByRole("region", { name: "Confirm purge" });
    await expect(purgeConfirm.getByRole("button", { name: "Cancel" })).toBeFocused();
    const deniedPurge = viewer.waitForResponse((response) => response.url().includes(`/comments/${commentId}/purge?root=docs`));
    await purgeConfirm.getByRole("button", { name: "Purge comment", exact: true }).click();
    expect((await deniedPurge).status()).toBe(403);
    await expect(viewer.getByRole("alert").filter({ hasText: "cannot purge" })).toBeVisible();
    await purgeConfirm.getByRole("button", { name: "Cancel" }).click();

    await openDiscussionActions(viewer, "Reattach");
    await viewer.getByRole("button", { name: "Reattach", exact: true }).click();
    await viewer.locator("[data-pb-reattach-source] p").evaluate((paragraph) => {
      const selection = window.getSelection()!; selection.selectAllChildren(paragraph); document.dispatchEvent(new Event("selectionchange"));
    });
    await viewer.getByRole("button", { name: "Preview selected passage" }).click();
    await viewer.getByRole("button", { name: "Confirm passage" }).click();
    const deniedReattach = viewer.waitForResponse((response) => response.url().includes(`/discussions/${id}/reattach?root=docs`));
    await viewer.getByRole("button", { name: "Reattach discussion", exact: true }).click();
    expect((await deniedReattach).status()).toBe(403);
    await expect(viewer.getByRole("alert").filter({ hasText: "cannot reattach" })).toBeVisible();
    await expect(viewer.getByRole("button", { name: "Passage confirmed" })).toBeVisible();
    await viewer.getByRole("button", { name: "Cancel", exact: true }).click();

    await gotoExpectStatus(page, url);
    await openDiscussionActions(page.locator("article.pb-discussion-comment").filter({ hasText: "Viewer edited comment" }), "Edit comment");
    await page.locator("article.pb-discussion-comment").filter({ hasText: "Viewer edited comment" }).getByRole("button", { name: "Edit comment" }).click();
    await page.getByRole("textbox", { name: "Comment" }).fill("Admin cannot replace viewer text");
    const deniedEdit = page.waitForResponse((response) => response.url().includes(`/comments/${commentId}/edit?root=docs`));
    await page.getByRole("button", { name: "Save comment" }).click();
    expect((await deniedEdit).status()).toBe(403);
    await expect(page.getByRole("textbox", { name: "Comment" })).toHaveValue("Admin cannot replace viewer text");
    await page.getByRole("button", { name: "Cancel", exact: true }).click();

    await openDiscussionActions(viewer.locator("article.pb-discussion-comment").filter({ hasText: "Viewer edited comment" }), "Retract comment");
    await viewer.locator("article.pb-discussion-comment").filter({ hasText: "Viewer edited comment" }).getByRole("button", { name: "Retract comment" }).click();
    const retracted = viewer.waitForResponse((response) => response.url().includes(`/comments/${commentId}/retract?root=docs`));
    await viewer.getByRole("region", { name: "Confirm retraction" }).getByRole("button", { name: "Retract comment" }).click();
    expect((await retracted).status()).toBe(200);
    await expect(viewer.getByRole("status", { name: "Comment retracted" })).toBeVisible();
    await openDiscussionActions(page, "Refresh");
    await page.getByRole("button", { name: "Refresh", exact: true }).click();
    const tombstone = page.locator("article.pb-discussion-comment").filter({ hasText: "retracted by Lifecycle viewer" });
    await expect(tombstone).toBeVisible();
    await expect(tombstone.getByRole("button", { name: "Edit comment" })).toHaveCount(0);
    await openDiscussionActions(tombstone, "Purge comment (admin)");
    await tombstone.getByRole("button", { name: "Purge comment (admin)" }).click();
    await expect(page.getByText("Remove this comment file. Earlier Git history may still contain it.")).toBeVisible();
    const purged = page.waitForResponse((response) => response.url().includes(`/comments/${commentId}/purge?root=docs`));
    await page.getByRole("region", { name: "Confirm purge" }).getByRole("button", { name: "Purge comment", exact: true }).click();
    expect((await purged).status()).toBe(200);
    await expect(page.getByRole("status", { name: "Comment removed" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Discussion on c5-auth-lifecycle.md" })).toBeFocused();
    await openDiscussionActions(page, "Resolve discussion");
    await page.getByRole("button", { name: "Resolve discussion" }).click();
    await expect(page.getByRole("status", { name: "Discussion resolved" })).toBeVisible();
    await openDiscussionActions(page, "Reopen discussion");
    await page.getByRole("button", { name: "Reopen discussion" }).click();
    await expect(page.getByRole("status", { name: "Discussion reopened" })).toBeVisible();

    await page.setViewportSize({ width: 375, height: 812 });
    await gotoExpectStatus(page, "/docs/c5-auth-lifecycle");
    await page.locator("[data-pb-discussion-panel]").getByRole("button", { name: /Cookie lifecycle selection/ }).click();
    await openDiscussionActions(page, "Reattach");
    await page.getByRole("button", { name: "Reattach", exact: true }).click();
    await expect(page.getByRole("button", { name: "New discussion" })).toBeDisabled();
    await page.getByRole("button", { name: "Hide discussions" }).click();
    await page.getByRole("button", { name: "Show discussions" }).click();
    await expect(page.getByRole("heading", { name: "Select a new passage in the displayed page" })).toBeFocused();
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(page.getByLabel("Discussion actions")).toBeFocused();
    await openDiscussionActions(page, "Reattach");
    await page.getByRole("button", { name: "Reattach", exact: true }).click();
    const paragraph = page.locator("[data-pb-page-article] p").filter({ hasText: "Cookie lifecycle selection." });
    await clickFirstCharacter(paragraph);
    for (let index = 0; index < 3; index++) await page.keyboard.press("Shift+ArrowRight");
    await expect.poll(() => page.evaluate(() => window.getSelection()?.toString())).toBe("Coo");
    for (let index = 0; index < 40; index++) {
      if (await page.getByRole("button", { name: "Preview selected passage" }).evaluate((button) => button === document.activeElement)) break;
      await page.keyboard.press("Tab");
    }
    await expect(page.getByRole("button", { name: "Preview selected passage" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("button", { name: "Confirm passage" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("button", { name: "Reattach discussion", exact: true })).toBeFocused();
    const reattached = page.waitForResponse((response) => response.url().includes(`/discussions/${id}/reattach?root=docs`));
    await page.keyboard.press("Enter");
    expect((await reattached).status()).toBe(200);
    await expect(page.getByRole("status", { name: "Discussion reattached" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);

    await openDiscussionActions(page, "Edit comment");
    await page.getByRole("button", { name: "Edit comment" }).click();
    await page.getByRole("textbox", { name: "Comment" }).fill("Keep lifecycle edit after expiry");
    await page.getByRole("button", { name: "Hide discussions" }).click();
    await page.getByRole("button", { name: "Show discussions" }).click();
    await expect(page.getByRole("textbox", { name: "Comment" })).toBeFocused();
    expect((await page.request.post("/api/v1/logout", { headers: { "X-CSRF-Token": adminCsrf } })).ok()).toBe(true);
    const expired = page.waitForResponse((response) => response.url().includes("/edit?root=docs") && response.request().method() === "POST");
    await page.getByRole("button", { name: "Save comment" }).click();
    expect((await expired).status()).toBe(401);
    await expect(page.getByRole("textbox", { name: "Comment" })).toHaveValue("Keep lifecycle edit after expiry");
    await expect(page.getByRole("alert").filter({ hasText: "session needs attention" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
  } finally { await viewerContext.close(); }
});
