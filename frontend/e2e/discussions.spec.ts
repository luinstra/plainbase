import { cpSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

const PAGE_ID = "01970000-0000-7000-8000-00000000f005";
const QUOTE = "Temporary quoted passage.";
const SOURCE = `---\ntitle: C5 temporary page\nid: ${PAGE_ID}\n---\n\n# C5 temporary page\n\n${QUOTE}\n`;

test("rooted discussion reads survive a page deletion and a duplicate id in another root", async ({ page, request, smokeServer }) => {
  const extraDir = smokeServer.extraDir;
  if (!extraDir) throw new Error("discussion smoke needs a temporary multi-root server");
  const docsFile = path.join(smokeServer.contentDir, "c5-temporary.md");
  const extraFile = path.join(extraDir, "c5-temporary.md");
  writeFileSync(docsFile, SOURCE);
  writeFileSync(extraFile, SOURCE);

  await expect.poll(async () => (await request.get("/api/v1/pages/by-path/docs/c5-temporary")).status()).toBe(200);
  await expect.poll(async () => (await request.get("/api/v1/pages/by-path/extra/c5-temporary")).status()).toBe(200);
  const metadata = await request.get("/api/v1/pages/by-path/docs/c5-temporary");
  const { id, content_hash: contentHash } = (await metadata.json()) as { id: string; content_hash: string };
  expect(id).toBe(PAGE_ID);
  const create = await request.post(`/api/v1/pages/${id}/discussions?root=docs`, {
    data: { anchor: { kind: "quote", content_hash: contentHash, selected_text: QUOTE },
      body: "---\n\n## Comment heading\n\n[Safe link](https://example.com)\n\n<unsafe-tag>" },
  });
  expect(create.status()).toBe(201);
  const discussionId = ((await create.json()) as { id: string }).id;
  const collection = path.join(".plainbase", "discussions", discussionId);
  const extraCollection = path.join(extraDir, collection);
  mkdirSync(path.dirname(extraCollection), { recursive: true });
  cpSync(path.join(smokeServer.contentDir, collection), extraCollection, { recursive: true });
  await expect.poll(async () => {
    const response = await request.get("/api/v1/discussions?root=extra");
    return ((await response.json()) as { discussions: { id: string; state: string; quote: string | null }[] }).discussions
      .some((item) => item.id === discussionId && item.state === "exact" && item.quote === QUOTE);
  }).toBe(true);

  await gotoExpectStatus(page, "/discussions/docs");
  await expect(page.getByRole("link", { name: "c5-temporary.md" })).toBeVisible();
  await gotoExpectStatus(page, `/discussions/docs/${discussionId}`);
  await expect(page.getByText(QUOTE).first()).toBeVisible();
  await expect(page.locator(".pb-discussion-body h2")).toHaveText("Comment heading");
  await expect(page.locator(".pb-discussion-body .pb-heading-anchor")).toHaveCount(0);
  await expect(page.locator(".pb-discussion-body a")).toHaveAttribute("href", "https://example.com");
  await expect(page.locator(".pb-discussion-body")).toContainText("<unsafe-tag>");
  await page.reload();
  await expect(page.getByText(QUOTE).first()).toBeVisible();

  await gotoExpectStatus(page, `/discussions/extra/${discussionId}`);
  await expect(page.getByText(QUOTE).first()).toBeVisible();
  await expect(page.locator("[data-pb-new-page]")).toHaveAttribute("href", "/new?root=extra");

  await page.setViewportSize({ width: 375, height: 812 });
  await gotoExpectStatus(page, "/docs/c5-temporary");
  const toggle = page.locator("[data-pb-discussions-toggle]");
  const panelRead = page.waitForRequest((call) => call.url().includes(`/api/v1/pages/${PAGE_ID}/discussions?root=docs&limit=200`));
  await toggle.focus();
  await page.keyboard.press("Enter");
  await panelRead;
  await expect(page.locator("[data-pb-discussion-panel]")).toBeVisible();
  await page.getByRole("button", { name: "c5-temporary.md" }).click();
  await expect(page.getByRole("link", { name: "Open full discussion" })).toBeVisible();
  const panelHeading = page.locator("[data-pb-discussion-panel] h3");
  await expect(panelHeading).toHaveText("Discussion on c5-temporary.md");
  await expect(panelHeading).toBeFocused();
  await expect(page.locator("[data-pb-discussion-panel] h4").filter({ hasText: "Original anchor" })).toHaveCount(1);
  await expect(page.locator("[data-pb-discussion-panel] h4").filter({ hasText: "Comments" })).toHaveCount(1);
  const width = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(width).toBeLessThanOrEqual(375);

  rmSync(docsFile);
  await expect.poll(async () => {
    const response = await request.get("/api/v1/discussions?root=docs");
    const body = (await response.json()) as { discussions: { id: string; state: string }[] };
    return body.discussions.find((item) => item.id === discussionId)?.state;
  }).toBe("orphaned");
  await gotoExpectStatus(page, `/discussions/docs/${discussionId}`);
  await expect(page.getByText("Page no longer found")).toBeVisible();
  await expect(page.getByText(QUOTE).first()).toBeVisible();
  await expect(page.getByText(PAGE_ID)).toBeVisible();
});

test("selection preview, rooted creation, reply and stale-source recovery use real writes", async ({ page, request, smokeServer }) => {
  const extraDir = smokeServer.extraDir;
  if (!extraDir) throw new Error("discussion writes need the multi-root fixture");
  const pageId = "01970000-0000-7000-8000-00000000f006";
  const file = path.join(extraDir, "c5-write.md");
  const source = `---\ntitle: C5 write probe\nid: ${pageId}\n---\n\n# Write probe\n\nbanana\n`;
  writeFileSync(file, source);
  await expect.poll(async () => (await request.get("/api/v1/pages/by-path/extra/c5-write")).status()).toBe(200);
  await page.setViewportSize({ width: 375, height: 812 });
  await gotoExpectStatus(page, "/extra/c5-write");

  async function selectAna() {
    await page.locator("[data-pb-page-article] p").filter({ hasText: "banana" }).scrollIntoViewIfNeeded();
    const points = await page.evaluate(() => {
      const paragraph = [...document.querySelectorAll<HTMLElement>("[data-pb-page-article] p")]
        .find((candidate) => candidate.textContent?.trim() === "banana");
      const text = paragraph?.firstChild;
      if (!(text instanceof Text)) throw new Error("banana text is absent");
      const at = (offset: number, end: boolean) => {
        const range = document.createRange(); range.setStart(text, offset); range.setEnd(text, offset + 1);
        const rect = range.getBoundingClientRect(); return { x: end ? rect.right - 1 : rect.left + 1, y: rect.y + rect.height / 2 };
      };
      return { start: at(1, false), end: at(3, true) };
    });
    await page.mouse.move(points.start.x, points.start.y);
    await page.mouse.down();
    await page.mouse.move(points.end.x, points.end.y, { steps: 8 });
    await page.mouse.up();
    await expect.poll(() => page.evaluate(() => window.getSelection()?.toString())).toBe("ana");
  }

  await selectAna();
  await page.getByRole("button", { name: "Comment on selection" }).click();
  await expect(page.getByText("Whole block selected")).toBeVisible();
  await expect(page.locator(".pb-discussion-panel .pb-discussion-quote")).toContainText("banana");
  await page.getByRole("button", { name: "Confirm passage" }).click();
  const quoteBody = page.getByRole("textbox", { name: "Comment" });
  await expect(quoteBody).toBeFocused();
  await quoteBody.fill("A selected passage comment 😀");
  const quotePost = page.waitForResponse((response) => response.url().includes(`/api/v1/pages/${pageId}/discussions?root=extra`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "Create discussion" }).click();
  const quoteResponse = await quotePost;
  expect(quoteResponse.status()).toBe(201);
  const quoteId = ((await quoteResponse.json()) as { id: string }).id;
  await expect(page.getByRole("status", { name: "Discussion created" })).toBeVisible();
  await expect(page.locator("[data-pb-discussion-panel] h3")).toBeFocused();
  await expect.poll(async () => {
    const response = await request.get(`/api/v1/discussions/${quoteId}?root=extra`);
    const detail = (await response.json()) as { discussion: { anchor: { kind: string; selection: string; quote: string } } };
    return [detail.discussion.anchor.kind, detail.discussion.anchor.selection, detail.discussion.anchor.quote];
  }).toEqual(["quote", "snapped", "banana\n"]);

  await page.getByRole("button", { name: "Reply" }).click();
  await page.getByRole("textbox", { name: "Comment" }).fill("A real reply");
  const replyPost = page.waitForResponse((response) => response.url().includes(`/api/v1/discussions/${quoteId}/comments?root=extra`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "Post reply" }).click();
  expect((await replyPost).status()).toBe(201);
  await expect(page.getByRole("status", { name: /Reply posted/ })).toBeVisible();
  await expect.poll(async () => {
    const response = await request.get(`/api/v1/discussions/${quoteId}?root=extra`);
    return ((await response.json()) as { comments: { markdown: string }[] }).comments.some((comment) => comment.markdown === "A real reply");
  }).toBe(true);

  await page.getByRole("button", { name: "Back to page discussions" }).click();
  await page.getByRole("button", { name: "New page discussion" }).click();
  await page.getByRole("textbox", { name: "Comment" }).fill("Whole page discussion");
  const pagePost = page.waitForResponse((response) => response.url().includes(`/api/v1/pages/${pageId}/discussions?root=extra`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "Create discussion" }).click();
  const pageResponse = await pagePost;
  expect(pageResponse.status()).toBe(201);
  const pageDiscussionId = ((await pageResponse.json()) as { id: string }).id;
  await expect.poll(async () => {
    const response = await request.get(`/api/v1/discussions/${pageDiscussionId}?root=extra`);
    return ((await response.json()) as { discussion: { anchor: { kind: string } } }).discussion.anchor.kind;
  }).toBe("page");

  await selectAna();
  await page.getByRole("button", { name: "Comment on selection" }).click();
  await expect(page.getByRole("button", { name: "Confirm passage" })).toBeVisible();
  await page.getByRole("button", { name: "Confirm passage" }).click();
  await page.getByRole("textbox", { name: "Comment" }).fill("Keep this draft on source change");
  const before = (await (await request.get(`/api/v1/pages/${pageId}/html?root=extra`)).json()) as { content_hash: string };
  writeFileSync(file, `${source}\nA newer source line.\n`);
  await expect.poll(async () => {
    const response = await request.get(`/api/v1/pages/${pageId}/html?root=extra`);
    return ((await response.json()) as { content_hash: string }).content_hash;
  }).not.toBe(before.content_hash);
  await page.getByRole("button", { name: "Create discussion" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "page changed" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Comment" })).toHaveValue("Keep this draft on source change");
  await page.getByRole("button", { name: "Reload page" }).click();
  await expect(page.getByText("A newer source line.")).toBeVisible();
  await selectAna();
  await page.getByRole("button", { name: "Comment on selection" }).click();
  await page.getByRole("button", { name: "Confirm passage" }).click();
  await expect(page.getByRole("textbox", { name: "Comment" })).toHaveValue("Keep this draft on source change");
  await page.getByRole("button", { name: "Create discussion" }).click();
  await expect(page.getByRole("status", { name: "Discussion created" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
});
