import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

for (const theme of ["dark", "light"] as const) test(`discussion updates, filters and passage in ${theme}`, async ({ page, request, smokeServer }) => {
  test.setTimeout(90_000);
  if (!smokeServer.extraDir) throw new Error("discussion polish requires the extra root");
  const id = theme === "dark" ? "01970000-0000-7000-8000-00000000f080" : "01970000-0000-7000-8000-00000000f081";
  const name = `discussion-polish-${theme}`;
  const quote = "Confirm the rollback owner before releasing.";
  const passageText = `${quote} Follow release notes and release_id.`;
  writeFileSync(path.join(smokeServer.extraDir, `${name}.md`), `---\nid: ${id}\ntitle: Release discussion\nowner: Platform team\nstatus: active\n---\n\n# Release discussion\n\n## Before release\n\n${quote} Follow [release notes](https://example.com) and \`release_id\`.\n\n## Afterwards\n\nRecord what we learned.\n`);
  await expect.poll(async () => (await request.get(`/api/v1/pages/${id}/html?root=extra`)).status()).toBe(200);
  const source = await (await request.get(`/api/v1/pages/${id}/html?root=extra`)).json();
  const created = await request.post(`/api/v1/pages/${id}/discussions?root=extra`, { data: {
    anchor: { kind: "quote", content_hash: source.content_hash, selected_text: quote }, body: "Can the on-call engineer own the rollback?" } });
  expect(created.status()).toBe(201);
  const thread = (await created.json()).id;
  await page.emulateMedia({ colorScheme: theme });
  await page.setViewportSize({ width: theme === "dark" ? 1440 : 1280, height: 1050 });
  await gotoExpectStatus(page, `/extra/${name}`);
  expect(await page.locator("html").getAttribute("data-theme")).toBe(theme === "dark" ? "dark" : null);
  const panel = page.locator('[data-pb-discussion-panel]');
  await expect(panel.locator('.pb-discussion-list > li')).toHaveCount(1);
  expect((await request.post(`/api/v1/pages/${id}/discussions?root=extra`, { data: {
    anchor: { kind: "page", content_hash: source.content_hash }, body: "What belongs in the retrospective?" } })).status()).toBe(201);
  await expect(panel.locator('.pb-discussion-list > li')).toHaveCount(2, { timeout: 25_000 });
  await expect(panel.getByRole('button', { name: 'Refresh', exact: true })).toHaveCount(0);
  await expect(panel.locator('[data-pb-discussion-avatar]')).toHaveCount(2);
  const filters = panel.getByRole('group', { name: 'Discussion status' });
  await filters.getByRole('button', { name: 'Resolved', exact: true }).click();
  await expect(panel.getByText('No resolved discussions in the loaded results.')).toBeVisible();
  await filters.getByRole('button', { name: 'All', exact: true }).click();
  const shots = path.resolve('../.crew/ui-discussions-screenshots'); mkdirSync(shots, { recursive: true });
  await page.screenshot({ path: path.join(shots, `${theme}-list.png`), fullPage: true });
  await panel.locator(`[data-pb-discussion-id="${thread}"]`).click();
  const passage = page.locator('[data-pb-page-article] .pb-discussion-passage');
  await expect(passage).toHaveText(passageText);
  const selected = await passage.evaluate((element) => {
    const before = element.getAttribute('data-pb-src'); const range = document.createRange(); range.selectNodeContents(element);
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range);
    return { text: selection.toString(), source: before, after: element.getAttribute('data-pb-src') };
  });
  expect(selected.text).toBe(passageText); expect(selected.source).toBe(selected.after); expect(selected.source).toMatch(/^\d+-\d+$/);
  await page.evaluate(() => window.getSelection()?.removeAllRanges());
  await page.screenshot({ path: path.join(shots, `${theme}-thread.png`), fullPage: true });
  const checkContrast = async (selectors: string[]) => { for (const selector of selectors) {
    const values = await page.locator(selector).evaluateAll((nodes) => nodes.map((node) => {
      const ctx = document.createElement('canvas').getContext('2d')!;
      const rgb = (value: string) => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = value; ctx.fillRect(0, 0, 1, 1); return [...ctx.getImageData(0, 0, 1, 1).data].map((v) => v / 255); };
      const over = (a: number[], b: number[]) => [0, 1, 2].map((i) => a[i] * a[3] + b[i] * (1 - a[3])).concat(1);
      const chain: Element[] = []; for (let parent: Element | null = node; parent; parent = parent.parentElement) chain.unshift(parent);
      let background = [1, 1, 1, 1]; for (const parent of chain) background = over(rgb(getComputedStyle(parent).backgroundColor), background);
      const style = getComputedStyle(node);
      const luminance = (color: number[]) => color.slice(0, 3).map((v) => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4).reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
      const a = luminance(over(rgb(style.color), background)); const b = luminance(background);
      return { contrast: (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05), size: parseFloat(style.fontSize) };
    }));
    expect(values.length).toBeGreaterThan(0);
    for (const value of values) { expect(value.contrast, selector).toBeGreaterThanOrEqual(4.5); expect(value.size).toBeGreaterThanOrEqual(12); }
  } };
  await checkContrast(['.pb-discussion-avatar', '.pb-discussion-status', '.pb-discussion-passage', '.pb-discussion-passage a', '.pb-discussion-passage code']);
  expect((await request.post(`/api/v1/discussions/${thread}/resolve?root=extra`, { data: {} })).status()).toBe(200);
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(panel.locator('.pb-discussion-thread-status .pb-discussion-status')).toHaveText('Resolved');
  await page.getByRole('button', { name: 'Hide discussions' }).click();
  await expect(passage).toHaveCount(0);
  await page.getByRole('button', { name: 'Show discussions' }).click();
  await expect(passage).toHaveCount(1);
  await panel.getByRole('button', { name: 'Close discussion' }).click();
  await expect(passage).toHaveCount(0);
  await filters.getByRole('button', { name: 'Resolved', exact: true }).click();
  await expect(panel.locator('.pb-discussion-list > li')).toHaveCount(1);
  const headingCreated = await request.post(`/api/v1/pages/${id}/discussions?root=extra`, { data: {
    anchor: { kind: 'quote', content_hash: source.content_hash, selected_text: 'Before release' }, body: 'Heading context' } });
  expect(headingCreated.status()).toBe(201);
  const headingThread = (await headingCreated.json()).id;
  await filters.getByRole('button', { name: 'All', exact: true }).click();
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await panel.locator(`[data-pb-discussion-id="${headingThread}"]`).click();
  await expect(page.locator('h2.pb-discussion-passage')).toContainText('Before release');
  await checkContrast(['h2.pb-discussion-passage']);
});
