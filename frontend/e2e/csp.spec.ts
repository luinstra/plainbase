import { expect, test, type Page } from "@playwright/test";

/**
 * C1a item 3 (the binding gate for items 3/6): the SPA mounts and renders under the REAL served
 * Content-Security-Policy with ZERO `securitypolicyviolation` events. A header-presence unit test passes
 * while the app is broken in a browser — only the real CSP, applied by a real engine, proves the editor
 * (CodeMirror runtime `<style>` injection under `style-src 'unsafe-inline'`), the rendered Prose
 * (`dangerouslySetInnerHTML`), an external `https:` markdown image (`img-src … https:`), and the History
 * diff (hljs `dangerouslySetInnerHTML`) all load.
 *
 * Isolation: uses the `scratch/` docs no mutating spec owns (edit.spec.ts owns deploy-guide; history.spec.ts
 * owns getting-started). The read-only legs use `/docs/scratch/todo`; the diff leg (the one place a Save is
 * required) is isolated to `/docs/scratch/ideas`.
 */

type Violation = { violatedDirective: string; blockedURI: string };

async function captureViolations(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const sink: Violation[] = [];
    (window as unknown as { __cspViolations: Violation[] }).__cspViolations = sink;
    window.addEventListener("securitypolicyviolation", (e) => {
      sink.push({ violatedDirective: e.violatedDirective, blockedURI: e.blockedURI });
    });
  });
}

const readViolations = (page: Page): Promise<Violation[]> =>
  page.evaluate(() => (window as unknown as { __cspViolations: Violation[] }).__cspViolations);

test("the shell navigation response carries the hash-pinned, unsafe-inline-styled CSP", async ({ page }) => {
  const response = await page.goto("/docs/scratch/todo");
  const csp = response?.headers()["content-security-policy"] ?? "";
  expect(csp).toContain("style-src 'self' 'unsafe-inline'");
  expect(csp).toMatch(/script-src 'self' 'sha256-/);
});

test("editor + Prose + an external https image render under the real CSP with no violation", async ({ page }) => {
  await captureViolations(page);
  await page.goto("/docs/scratch/todo?mode=edit");

  // CodeMirror mounts under the real CSP (style-mod injects its runtime <style> via style-src 'unsafe-inline').
  await expect(page.locator("[data-pb-editor]")).toBeVisible();

  // Type an external https image line BEFORE opening the preview (the preview overlay covers the editor).
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("End");
  await content.pressSequentially("\n\n![x](https://example.com/x.png)\n");

  // Open the on-demand preview → Prose renders via dangerouslySetInnerHTML, and the server-rendered
  // <img src="https://…"> mounts in the shell document (governed by img-src). Do NOT Save.
  await page.locator("[data-pb-preview-toggle]").click();
  await expect(page.locator("[data-pb-preview] .pb-prose")).toBeVisible();
  // Settle the debounced preview: the img-src violation (if any) fires at mount, so the assertion must run
  // AFTER the <img> is in the DOM or the gate passes vacuously and flakes.
  await expect(page.locator('[data-pb-preview] img[src^="https://"]')).toBeAttached();

  const violations = await readViolations(page);
  expect(violations.filter((v) => v.violatedDirective.startsWith("img-src"))).toEqual([]);
  expect(violations).toEqual([]);
});

test("the History diff renders under the real CSP with no violation", async ({ page }) => {
  await captureViolations(page);

  // Two commits on an isolated page — the .smoke-content repo is git-init'd with no seed, so each save commits.
  await makeCommit(page, "/docs/scratch/ideas", `csp diff one ${Date.now()}`);
  await makeCommit(page, "/docs/scratch/ideas", `csp diff two ${Date.now()}`);

  await page.goto("/docs/scratch/ideas?mode=history");
  await expect(page.locator("[data-pb-history]")).toBeVisible();
  const commitButtons = page.locator("[data-pb-commit] button");
  await expect(async () => expect(await commitButtons.count()).toBeGreaterThanOrEqual(2)).toPass();

  // DiffPane renders the diff ONLY when two commits are selected (selectedCount < 2 shows the hint).
  await commitButtons.nth(0).click();
  await commitButtons.nth(1).click();
  await expect(page.locator("[data-pb-diff]")).toBeVisible();

  const violations = await readViolations(page);
  expect(violations).toEqual([]);
});

/** One edit+save cycle on [path] (each save commits on the git-on smoke server). Mirrors history.spec.ts. */
async function makeCommit(page: Page, path: string, marker: string): Promise<void> {
  await page.goto(`${path}?mode=edit`);
  await expect(page.locator("[data-pb-editor]")).toBeVisible();
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("End");
  await content.pressSequentially(`\n\n${marker}\n`);
  const save = page.locator("[data-pb-save]");
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();
}
