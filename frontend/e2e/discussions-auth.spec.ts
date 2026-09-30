import { writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

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

  await page.getByRole("button", { name: "Show discussions" }).click();
  await page.getByRole("button", { name: "New page discussion" }).click();
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

  await page.getByRole("button", { name: "Back to page", exact: true }).click();
  const paragraph = page.getByText("Cookie selection text.", { exact: true });
  await expect(paragraph).toBeVisible();
  await paragraph.click();
  await page.keyboard.press("Home");
  await page.keyboard.press("Shift+ArrowRight");
  await page.keyboard.press("Shift+ArrowRight");
  await page.keyboard.press("Shift+ArrowRight");
  await expect.poll(() => page.evaluate(() => window.getSelection()?.toString())).toBe("Coo");
  expect(await paragraph.evaluate((node) => node.closest("[contenteditable]"))).toBeNull();
  for (let index = 0; index < 20; index++) {
    if (await page.getByRole("button", { name: "Comment on selection" }).evaluate((button) => button === document.activeElement)) break;
    await page.keyboard.press("Shift+Tab");
  }
  await expect(page.getByRole("button", { name: "Comment on selection" })).toBeFocused();
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
