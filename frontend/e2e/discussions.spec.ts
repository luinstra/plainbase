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
    return ((await response.json()) as { discussions: { id: string }[] }).discussions.some((item) => item.id === discussionId);
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
