import { openDiscussionActions } from "./discussion-actions";
import { cpSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

const PAGE_ID = "01970000-0000-7000-8000-00000000f005";
const QUOTE = "Temporary quoted passage.";
const SOURCE = `---\ntitle: C5 temporary page\nid: ${PAGE_ID}\n---\n\n# C5 temporary page\n\n${QUOTE}\n`;

for (const width of [1280, 1848, 1024, 375]) {
  test(`page margin geometry stays stable at ${width}px`, async ({ page, request, smokeServer }, testInfo) => {
    if (!smokeServer.extraDir) throw new Error("margin geometry needs the multi-root fixture");
    const id = "01970000-0000-7000-8000-00000000f010";
    const quote = "Before each release, name an owner for rollback and confirm that the previous version can be restored. Keep the checklist short enough to use during an incident.";
    writeFileSync(path.join(smokeServer.extraDir, "release-checklist.md"),
      `---\ntitle: Release checklist\nid: ${id}\nowner: Platform team\nstatus: active\ntags: [releases, operations]\n---\n\n# Release checklist\n\n## Before the release\n\n${quote}\n\n## After the release\n\nWatch the error rate and check in with the support team before closing the release. Record anything we should improve next time.\n`);
    await expect.poll(async () => (await request.get(`/api/v1/pages/${id}/html?root=extra`)).status()).toBe(200);
    const source = await (await request.get(`/api/v1/pages/${id}/html?root=extra`)).json();
    const created = await request.post(`/api/v1/pages/${id}/discussions?root=extra`, {
      data: { anchor: { kind: "quote", content_hash: source.content_hash, selected_text: quote },
        body: width < 1280 ? `Could we name the rollback owner here?\n\n\x60\x60\x60text\n${"long-code-".repeat(50)}\n\x60\x60\x60\n\n| Column | Value |\n| --- | --- |\n| Path | ${"long-path/".repeat(30)} |` :
          "Could we name the rollback owner here? It would help the on-call team know who to contact if the release needs to be reverted.\n\nI suggest confirming the owner at the morning handoff." },
    });
    expect(created.status()).toBe(201);
    const threadId = (await created.json()).id as string;
    expect((await request.post(`/api/v1/pages/${id}/discussions?root=extra`, {
      data: { anchor: { kind: "page", content_hash: source.content_hash }, body: "What should we capture in the release retrospective?" },
    })).status()).toBe(201);
    await page.setViewportSize({ width, height: 1100 });
    const read = page.waitForRequest((call) => call.url().includes(`/api/v1/pages/${id}/discussions?root=extra&limit=200`));
    await gotoExpectStatus(page, "/extra/release-checklist");
    await read;
    const rail = page.locator("[data-pb-rail]");
    const info = rail.locator("[data-pb-rail-meta]");
    const panel = rail.locator("[data-pb-discussion-panel]");
    await expect(panel.locator(".pb-discussion-list > li")).toHaveCount(2);
    await expect(info).toBeVisible();
    await expect(page.locator("[data-pb-discussions-nav]")).toHaveCount(0);
    await expect(page.locator(".pb-reading-column button.pb-discussion-action")).toHaveCount(0);
    const geometry = () => page.evaluate(() => {
      const rect = (selector: string) => {
        const box = document.querySelector(selector)!.getBoundingClientRect();
        return { x: box.x, width: box.width, top: box.top, bottom: box.bottom };
      };
      return { article: rect("[data-pb-page-article] .pb-prose"), main: rect("[data-pb-main]"),
        rail: rect("[data-pb-rail]"), info: rect("[data-pb-rail-meta]") };
    });
    const before = await geometry();
    const newDiscussion = await panel.getByRole("button", { name: "New discussion" }).boundingBox();
    const refresh = await panel.getByRole("button", { name: "Refresh", exact: true }).boundingBox();
    expect(refresh!.x - (newDiscussion!.x + newDiscussion!.width)).toBeGreaterThanOrEqual(8);
    const first = await panel.locator(".pb-discussion-list > li").first().boundingBox();
    expect(before.info.bottom).toBeLessThanOrEqual(first!.y);
    if (width >= 1280) {
      await expect(page.locator("[data-pb-sidebar]")).toBeVisible();
      // Main's 48px left padding leaves breathing room beside the visible navigation sidebar.
      expect(Math.abs(before.article.x - before.main.x - 48)).toBeLessThanOrEqual(1);
      expect(before.article.x + before.article.width).toBeLessThanOrEqual(before.rail.x);
      await expect(rail.locator("[data-pb-toc]")).toBeVisible();
      expect(await rail.evaluate((node) => getComputedStyle(node).scrollbarGutter)).toBe("stable");
    } else {
      expect(before.article.bottom).toBeLessThanOrEqual(before.info.top);
      await expect(rail.locator("[data-pb-toc]")).toBeHidden();
      expect(await rail.evaluate((node) => getComputedStyle(node).maxHeight)).toBe("none");
      await expect(page.getByRole("button", { name: "New discussion" })).toBeVisible();
    }
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).scrollbarGutter)).toBe("stable");
    expect(await panel.evaluate((node) => getComputedStyle(node).fontSize)).toBe("16px");
    async function screenshot(state: string) {
      if (width !== 1848 && width !== 1280 && width !== 1024) return;
      const name = `${width}-${state}.png`;
      const file = testInfo.outputPath(name);
      // Scroll geometry updates before Chromium necessarily paints the sticky layer. Let two
      // frames settle it, then verify the actual header/title geometry before a full-page capture.
      const captureGeometry = await page.evaluate(async () => {
        const measure = () => {
          const header = document.querySelector("[data-pb-header]")!.getBoundingClientRect();
          const title = document.querySelector("[data-pb-page-article] h1")!.getBoundingClientRect();
          return { scrollY: window.scrollY, headerTop: header.top, headerBottom: header.bottom, titleTop: title.top };
        };
        const beforeScroll = measure();
        window.scrollTo({ top: 0, behavior: "instant" });
        const afterScroll = measure();
        await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
        return { beforeScroll, afterScroll, settled: measure() };
      });
      await expect.poll(() => page.evaluate(() => ({ scrollY: window.scrollY,
        headerTop: document.querySelector("[data-pb-header]")!.getBoundingClientRect().top })))
        .toEqual({ scrollY: 0, headerTop: 0 });
      expect(captureGeometry.settled.titleTop).toBeGreaterThanOrEqual(captureGeometry.settled.headerBottom);
      const geometryFile = testInfo.outputPath(`${name}-geometry.json`);
      writeFileSync(geometryFile, JSON.stringify(captureGeometry, null, 2));
      await testInfo.attach(`${name}-geometry`, { path: geometryFile, contentType: "application/json" });
      await page.screenshot({ path: file, fullPage: true });
      await testInfo.attach(name, { path: file, contentType: "image/png" });
      if (width >= 1280) await rail.screenshot({ path: testInfo.outputPath(`${width}-${state}-rail.png`) });
    }
    async function themes(state: string) {
      if (width < 1280) return;
      if (state === "composer") await panel.locator("form").scrollIntoViewIfNeeded();
      const openMenu = panel.locator(".pb-discussion-actions[open] > summary");
      const openLabel = await openMenu.count() ? await openMenu.getAttribute("aria-label") : null;
      await screenshot(`light-${state}`);
      await page.getByRole("button", { name: "Switch to dark mode" }).click();
      if (openLabel) await panel.getByLabel(openLabel, { exact: true }).click();
      await screenshot(`dark-${state}`);
      await page.getByRole("button", { name: "Switch to light mode" }).click();
      if (openLabel) await panel.getByLabel(openLabel, { exact: true }).click();
    }
    async function stable() {
      const after = await geometry();
      if (width >= 1280) for (const part of ["article", "rail", "info"] as const) {
        expect(Math.abs(after[part].x - before[part].x)).toBeLessThanOrEqual(1);
        expect(Math.abs(after[part].width - before[part].width)).toBeLessThanOrEqual(1);
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    }
    await themes("list");
    if (width === 1024) await screenshot("list");
    await page.getByRole("button", { name: "Hide discussions" }).click();
    await expect(panel).toBeHidden();
    await expect(info).toBeVisible();
    await expect(page.getByRole("button", { name: "Show discussions" })).toBeFocused();
    await stable();
    await screenshot("hidden");
    await page.getByRole("button", { name: "Show discussions" }).click();
    await expect(panel).toBeVisible();
    await stable();
    await panel.locator(`[data-pb-discussion-id='${threadId}']`).click();
    await expect(panel.getByRole("heading", { name: "Discussion on release-checklist.md" })).toBeFocused();
    await expect(panel.locator(".pb-discussion-body")).toContainText("Could we name the rollback owner here?");
    await expect(panel.getByRole("link", { name: "Discussions in extra" })).toHaveCount(0);
    await stable();
    await themes("thread");
    await page.getByRole("button", { name: "Reply", exact: true }).click();
    await expect(page.getByRole("textbox", { name: "Comment" })).toBeFocused();
    await stable();
    await page.getByRole("textbox", { name: "Comment" }).fill("Good point. I'll add the rollback owner to the handoff checklist.");
    await themes("composer");
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(page.getByRole("button", { name: "Reply", exact: true })).toBeFocused();
    if (width >= 1280) {
      const actions = panel.getByLabel("Discussion actions");
      await actions.focus();
      const headerBefore = await panel.locator(".pb-discussion-thread-header").boundingBox();
      const commentsBefore = await panel.locator(".pb-discussion-comments").boundingBox();
      await page.keyboard.press("Enter");
      await expect(panel.getByRole("button", { name: "Resolve discussion" })).toBeVisible();
      const headerAfter = await panel.locator(".pb-discussion-thread-header").boundingBox();
      const commentsAfter = await panel.locator(".pb-discussion-comments").boundingBox();
      expect(Math.abs(headerAfter!.height - headerBefore!.height)).toBeLessThanOrEqual(1);
      expect(Math.abs(commentsAfter!.y - commentsBefore!.y)).toBeLessThanOrEqual(1);
      async function popupFits(trigger: typeof actions) {
        const popup = trigger.locator("..").locator(".pb-discussion-action-items");
        await expect(popup).toBeVisible();
        await expect.poll(async () => {
          const box = (await popup.boundingBox())!;
          const railBox = (await rail.boundingBox())!;
          return box.x >= railBox.x && box.x + box.width <= railBox.x + railBox.width &&
            box.y >= railBox.y && box.y + box.height <= railBox.y + railBox.height;
        }).toBe(true);
      }
      await popupFits(actions);
      await panel.getByRole("button", { name: "Resolve discussion" }).focus();
      await expect(panel.locator(".pb-discussion-actions[open]")).toHaveCount(1);
      await page.keyboard.press("Tab");
      await expect(panel.getByRole("button", { name: "Reattach", exact: true })).toBeFocused();
      await expect(panel.locator(".pb-discussion-actions[open]")).toHaveCount(1);
      const otherActions = panel.getByLabel(/Actions for .*'s comment/).first();
      await otherActions.click();
      await expect(panel.getByRole("button", { name: "Resolve discussion" })).toBeHidden();
      await expect(panel.getByRole("button", { name: "Edit comment" })).toBeVisible();
      await expect(panel.locator(".pb-discussion-actions[open]")).toHaveCount(1);
      // A click on ordinary, non-focusable prose dismisses the menu without stealing focus.
      await page.locator("[data-pb-page-article] h1").click();
      await expect(panel.locator(".pb-discussion-actions[open]")).toHaveCount(0);
      await actions.click();
      await panel.getByRole("link", { name: "Open full discussion", exact: true }).focus();
      await page.keyboard.press("Tab");
      await expect(otherActions).toBeFocused();
      await expect(panel.locator(".pb-discussion-actions[open]")).toHaveCount(0);
      await actions.click();
      await themes("maintenance");
      await actions.focus();
      await page.keyboard.press("Escape");
      await expect(actions).toBeFocused();
      await expect(panel.getByRole("button", { name: "Resolve discussion" })).toBeHidden();
      const commentActions = panel.getByLabel(/Actions for .*'s comment/).first();
      // Put a real comment trigger at the scrollable rail's lower edge. The same popup
      // must open above it without enlarging the header or moving the comment stream.
      await page.setViewportSize({ width, height: 480 });
      await commentActions.evaluate((trigger) => {
        const rail = trigger.closest<HTMLElement>("[data-pb-rail]")!;
        rail.scrollTop += trigger.getBoundingClientRect().bottom - rail.getBoundingClientRect().bottom + 12;
      });
      await commentActions.focus();
      await page.keyboard.press("Enter");
      await popupFits(commentActions);
      await expect(commentActions.locator("..")).toHaveAttribute("data-side", "above");
      await themes("maintenance-lower");
      await commentActions.focus();
      await page.keyboard.press("Escape");
      await expect(commentActions).toBeFocused();
      await expect(panel.getByRole("button", { name: "Edit comment" })).toBeHidden();
      await page.setViewportSize({ width, height: 1100 });
      await commentActions.click();
      await panel.getByRole("button", { name: "Edit comment" }).click();
      await expect(panel.getByRole("button", { name: "Edit comment" })).toBeHidden();
      await page.getByRole("button", { name: "Cancel", exact: true }).click();
      await expect(commentActions).toBeFocused();
      await commentActions.click();
      await panel.getByRole("button", { name: "Edit comment" }).click();
      await commentActions.click();
      await page.getByRole("button", { name: "Cancel", exact: true }).click();
      await expect(commentActions).toBeFocused();
      await page.keyboard.press("Escape");
      await expect(commentActions).toBeFocused();
      await openDiscussionActions(panel, "Reattach");
      await panel.getByRole("button", { name: "Reattach", exact: true }).click();
      await page.locator("[data-pb-page-article] p").filter({ hasText: quote }).evaluate((paragraph) => {
        window.getSelection()!.selectAllChildren(paragraph); document.dispatchEvent(new Event("selectionchange"));
      });
      await page.getByRole("button", { name: "Preview selected passage" }).click();
      await expect(page.getByRole("button", { name: "Confirm passage" })).toBeFocused();
      await themes("reattachment");
      await stable();
      await page.getByRole("button", { name: "Cancel", exact: true }).click();
      await expect(actions).toBeFocused();
      // Clear the reattachment selection: New discussion now intentionally uses selected text.
      await page.locator("[data-pb-page-article] p").filter({ hasText: quote }).click();
      await expect.poll(() => page.evaluate(() => window.getSelection()?.isCollapsed)).toBe(true);
    }
    await page.getByRole("button", { name: "Close discussion" }).click();
    await expect(panel.locator(`[data-pb-discussion-id='${threadId}']`)).toBeFocused();
    await page.getByRole("button", { name: "New discussion" }).click();
    await expect(page.getByRole("heading", { name: "New page discussion" })).toBeVisible();
    await expect(page.getByRole("textbox", { name: "Comment" })).toBeFocused();
    await stable();
    await themes("new-discussion");
    if (width >= 1280) {
      writeFileSync(path.join(smokeServer.extraDir, "release-retrospective.md"),
        `---\ntitle: Release retrospective\nid: 01970000-0000-7000-8000-00000000f011\n---\n\n# Release retrospective\n\nA place to record what went well and what we should change for the next release.\n`);
      await expect.poll(async () => (await request.get("/api/v1/pages/by-path/extra/release-retrospective")).status()).toBe(200);
      await gotoExpectStatus(page, "/extra/release-retrospective");
      await expect(page.getByText("Start the conversation")).toBeVisible();
      await themes("empty");
    }
  });
}

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
  const panelRead = page.waitForRequest((call) => call.url().includes(`/api/v1/pages/${PAGE_ID}/discussions?root=docs&limit=200`));
  await gotoExpectStatus(page, "/docs/c5-temporary");
  await panelRead;
  await expect(page.locator("[data-pb-discussion-panel]")).toBeVisible();
  await page.locator("[data-pb-discussion-panel]").getByRole("button", { name: /Temporary quoted passage/ }).click();
  const panelHeading = page.locator("[data-pb-discussion-panel] h3");
  await expect(panelHeading).toHaveText("Conversation");
  await expect(panelHeading).toBeFocused();
  await openDiscussionActions(page, "Refresh");
  await expect(page.getByRole("link", { name: "Open full discussion" })).toBeVisible();
  await page.getByText("Discussion details", { exact: true }).click();
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
  await page.getByText("Discussion details", { exact: true }).click();
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
    await page.locator("[data-pb-page-article] p").filter({ hasText: "banana" }).evaluate(async (paragraph) => {
      paragraph.scrollIntoView({ block: "center", behavior: "instant" });
      await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
    });
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
  await page.getByRole("button", { name: "New discussion" }).click();
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

  await page.getByRole("button", { name: "Close discussion" }).click();
  await page.getByRole("button", { name: "New discussion" }).click();
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
  await page.getByRole("button", { name: "New discussion" }).click();
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
  await page.getByRole("button", { name: "Reselect", exact: true }).click();
  await page.getByRole("button", { name: "Confirm passage" }).click();
  await expect(page.getByRole("textbox", { name: "Comment" })).toHaveValue("Keep this draft on source change");
  await page.getByRole("button", { name: "Create discussion" }).click();
  await expect(page.getByRole("status", { name: "Discussion created" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
});

test("extra-root lifecycle reattaches twice, recovers a stale preview and retains actions after source deletion", async ({ page, request, smokeServer }, testInfo) => {
  test.setTimeout(90_000);
  if (!smokeServer.extraDir) throw new Error("lifecycle smoke needs the extra root");
  const pageId = "01970000-0000-7000-8000-00000000f008";
  const file = path.join(smokeServer.extraDir, "c5-lifecycle.md");
  const originalQuote = "Before each release, confirm the rollback owner and the last known good version. " +
    "Review the changes with the on-call team and write down which dashboards to watch. " +
    "Check that the backup is recent enough and that someone has tried restoring it. " +
    "Keep the release checklist short enough to use during an incident, but include the contact for each service. " +
    "If the error rate rises, pause the rollout and ask the incident lead whether to revert. " +
    "After the release, record what worked well and what should change before the next handoff. " +
    "Share the remaining follow-ups with the team so that the next owner can pick them up without repeating the investigation.";
  const initial = `---\ntitle: Release handoff\nid: ${pageId}\n---\n\n# Release handoff\n\n${originalQuote}\n`;
  writeFileSync(file, initial);
  await expect.poll(async () => (await request.get(`/api/v1/pages/${pageId}/html?root=extra`)).status()).toBe(200);
  const metadata = await (await request.get(`/api/v1/pages/${pageId}/html?root=extra`)).json();
  const created = await request.post(`/api/v1/pages/${pageId}/discussions?root=extra`, {
    data: { anchor: { kind: "quote", content_hash: metadata.content_hash, selected_text: originalQuote },
      body: "Could we add the rollback owner to the handoff checklist?\n\nThat would help the on-call team find the right person quickly." },
  });
  expect(created.status()).toBe(201);
  const id = (await created.json()).id as string;
  const detailUrl = `/api/v1/discussions/${id}?root=extra`;
  const original = (await (await request.get(detailUrl)).json()).discussion.anchor;
  await page.setViewportSize({ width: 1280, height: 1100 });
  await gotoExpectStatus(page, "/extra/c5-lifecycle");
  const panel = page.locator("[data-pb-discussion-panel]");
  await panel.locator(`[data-pb-discussion-id='${id}']`).click();
  const toggle = panel.getByRole("button", { name: /^(Show full passage|Collapse passage)$/ });
  const quotation = panel.locator(".pb-discussion-context blockquote");
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(quotation).toHaveText(originalQuote);
  const previewHeight = (await quotation.boundingBox())!.height;
  expect(previewHeight).toBeLessThanOrEqual(140);
  await page.screenshot({ path: testInfo.outputPath("1280-light-long-passage-collapsed.png"), fullPage: true });
  await toggle.focus();
  await page.keyboard.press("Enter");
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  expect((await quotation.boundingBox())!.height).toBeGreaterThan(previewHeight);
  await page.screenshot({ path: testInfo.outputPath("1280-light-long-passage-expanded.png"), fullPage: true });
  await page.getByRole("button", { name: "Switch to dark mode" }).click();
  await page.screenshot({ path: testInfo.outputPath("1280-dark-long-passage-expanded.png"), fullPage: true });
  await toggle.focus();
  await page.keyboard.press("Enter");
  await expect(toggle).toBeFocused();
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  expect((await quotation.boundingBox())!.height).toBe(previewHeight);
  await expect(quotation).toHaveText(originalQuote);
  await page.screenshot({ path: testInfo.outputPath("1280-dark-long-passage-collapsed.png"), fullPage: true });
  await openDiscussionActions(panel, "Resolve discussion");
  await panel.getByRole("button", { name: "Resolve discussion" }).click();
  await expect(page.getByRole("status", { name: "Discussion resolved" })).toBeVisible();
  await openDiscussionActions(panel, "Reattach");
  const unavailable = panel.getByRole("group", { name: "Reattach unavailable" });
  const tooltip = panel.getByRole("tooltip");
  const popup = panel.locator(".pb-discussion-actions[open] .pb-discussion-action-items");
  const popupBeforeHint = await popup.boundingBox();
  await expect(panel.getByRole("button", { name: "Reattach", exact: true })).toBeDisabled();
  await expect(tooltip).toBeHidden();
  await unavailable.hover();
  await expect(tooltip).toHaveText("Reopen this discussion before reattaching.");
  await expect(tooltip).toBeVisible();
  expect(await popup.boundingBox()).toEqual(popupBeforeHint);
  const tipBox = (await tooltip.boundingBox())!;
  const railBox = (await page.locator("[data-pb-rail]").boundingBox())!;
  expect(tipBox.x).toBeGreaterThanOrEqual(railBox.x);
  expect(tipBox.y).toBeGreaterThanOrEqual(railBox.y);
  await page.screenshot({ path: testInfo.outputPath("1280-dark-unavailable-action.png"), fullPage: true });
  await page.locator("[data-pb-page-article] h1").hover();
  await expect(tooltip).toBeHidden();
  await unavailable.focus();
  await expect(tooltip).toBeVisible();
  await page.keyboard.press("Enter");
  await expect(panel.getByRole("region", { name: "Reattach discussion" })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(panel.getByLabel("Discussion actions")).toBeFocused();
  await expect(tooltip).toBeHidden();
  await openDiscussionActions(panel, "Reopen discussion");
  await panel.getByRole("button", { name: "Reopen discussion" }).click();
  await expect(page.getByRole("status", { name: "Discussion reopened" })).toBeVisible();
  await page.getByRole("button", { name: "Switch to light mode" }).click();
  await gotoExpectStatus(page, `/discussions/extra/${id}`);
  await page.setViewportSize({ width: 375, height: 812 });
  await openDiscussionActions(page, "Resolve discussion");
  await page.getByRole("button", { name: "Resolve discussion" }).click();
  await expect(page.getByRole("status", { name: "Discussion resolved" })).toBeVisible();
  await expect(page.getByLabel("Discussion actions")).toBeFocused();
  await openDiscussionActions(page, "Reattach");
  await expect(page.getByRole("button", { name: "Reattach", exact: true })).toBeDisabled();
  await openDiscussionActions(page, "Reopen discussion");
  await page.getByRole("button", { name: "Reopen discussion" }).click();
  await expect(page.getByRole("status", { name: "Discussion reopened" })).toBeVisible();

  const changed = initial.replace(originalQuote, "banana\n\nOther location 😀");
  writeFileSync(file, changed);
  await expect.poll(async () => (await (await request.get(detailUrl)).json()).discussion.state).toBe("changed");
  await openDiscussionActions(page, "Refresh");
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByText("Was around here", { exact: true })).toBeVisible();

  async function selectPassage(text: string, start = 0, end = text.length) {
    await page.locator("[data-pb-reattach-source] p").filter({ hasText: text }).scrollIntoViewIfNeeded();
    await page.locator("[data-pb-reattach-source]").evaluate((area, selection) => {
      const paragraph = [...area.querySelectorAll("p")].find((p) => p.textContent === selection.text);
      if (!paragraph?.firstChild) throw new Error("source passage absent");
      const range = document.createRange(); range.setStart(paragraph.firstChild, selection.start); range.setEnd(paragraph.firstChild, selection.end);
      const selected = window.getSelection()!; selected.removeAllRanges(); selected.addRange(range);
      document.dispatchEvent(new Event("selectionchange"));
    }, { text, start, end });
  }
  async function preview(text: string, start = 0, end = text.length) {
    await selectPassage(text, start, end);
    await page.getByRole("button", { name: "Preview selected passage" }).click();
    await expect(page.getByRole("button", { name: "Confirm passage" })).toBeFocused();
    await expect(page.getByRole("button", { name: "Reattach discussion", exact: true })).toBeDisabled();
    await page.getByRole("button", { name: "Confirm passage" }).click();
    await expect(page.getByRole("button", { name: "Reattach discussion", exact: true })).toBeFocused();
  }
  await openDiscussionActions(page, "Reattach");
  await page.getByRole("button", { name: "Reattach", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Select a new passage", exact: true })).toBeFocused();
  await preview("banana", 1, 4);
  await expect(page.getByText("Whole block selected")).toBeVisible();
  await page.getByRole("button", { name: "Reattach discussion", exact: true }).click();
  await expect(page.getByRole("status", { name: "Discussion reattached" })).toBeVisible();
  await expect.poll(async () => (await (await request.get(detailUrl)).json()).discussion.state).toBe("exact");
  expect((await (await request.get(detailUrl)).json()).discussion.anchor).toEqual(original);
  await openDiscussionActions(page, "Reattach");
  await page.getByRole("button", { name: "Reattach", exact: true }).click();
  await preview("Other location 😀");
  await page.getByRole("button", { name: "Reattach discussion", exact: true }).click();
  await expect(page.getByRole("status", { name: "Discussion reattached" })).toBeVisible();
  const repeated = (await (await request.get(detailUrl)).json()).discussion;
  expect(repeated.anchor).toEqual(original);
  expect(repeated.reattachment.anchor.quote).toContain("Other location 😀");

  await openDiscussionActions(page, "Reattach");
  await page.getByRole("button", { name: "Reattach", exact: true }).click();
  await preview("banana");
  const priorHash = (await (await request.get(`/api/v1/pages/${pageId}/html?root=extra`)).json()).content_hash;
  writeFileSync(file, `${changed}\nSource changed after preview.\n`);
  await expect.poll(async () => (await (await request.get(`/api/v1/pages/${pageId}/html?root=extra`)).json()).content_hash).not.toBe(priorHash);
  const refused = page.waitForResponse((response) => response.url().includes(`/discussions/${id}/reattach?root=extra`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "Reattach discussion", exact: true }).click();
  expect((await refused).status()).toBe(409);
  await expect(page.getByRole("alert").filter({ hasText: "page changed" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Confirm passage" })).toHaveCount(0);
  await page.getByRole("button", { name: "Reload page" }).click();
  await expect(page.getByText("Source changed after preview.")).toBeVisible();
  await preview("banana");
  await page.getByRole("button", { name: "Reattach discussion", exact: true }).click();
  await expect(page.getByRole("status", { name: "Discussion reattached" })).toBeVisible();
  expect((await (await request.get(detailUrl)).json()).discussion.anchor).toEqual(original);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);

  rmSync(file);
  await expect.poll(async () => (await (await request.get(detailUrl)).json()).discussion.state).toBe("orphaned");
  await openDiscussionActions(page, "Refresh");
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByText("Page no longer found", { exact: true })).toBeVisible();
  await openDiscussionActions(page, "Reattach");
  await page.getByRole("button", { name: "Reattach", exact: true }).click();
  await page.getByRole("button", { name: "Reload page" }).click();
  await expect(page.getByText(/stored source page could not be found/)).toBeVisible();
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await openDiscussionActions(page, "Edit comment");
  await page.getByRole("button", { name: "Edit comment" }).click();
  await page.getByRole("textbox", { name: "Comment" }).fill("Edited after source deletion 😀");
  await page.getByRole("button", { name: "Save comment" }).click();
  await expect(page.getByRole("status", { name: "Comment saved" })).toBeVisible();
  await openDiscussionActions(page, "Resolve discussion");
  await page.getByRole("button", { name: "Resolve discussion" }).click();
  await expect(page.getByRole("status", { name: "Discussion resolved" })).toBeVisible();
});
