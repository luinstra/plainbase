import { expect, test } from "@playwright/test";

/**
 * W7 master criterion 6 (Playwright, real server): an edited page shows updated content AND updated
 * history WITHOUT a server restart. Git mode is forced ON in the smoke env (smoke-server.mjs / D-9), so
 * `/history` returns git_enabled:true. The `.smoke-content` repo is freshly `git init`ed with NO seeded
 * commit (MF-4) — so baseline history is EMPTY and the FIRST save creates the first commit. The single
 * long-lived webServer (playwright.config.ts) never restarts across the flow, so "no restart" is
 * inherent; the meaningful assertions are baseline-empty → edit+save → the list GREW + the read view
 * reflects the edit, all served by the same running process.
 */

// A page NO other smoke spec mutates (edit.spec.ts owns deploy-guide; smoke.spec.ts only navigates to
// getting-started). An isolated page keeps this spec's baseline-empty → grows-after-save flow clean,
// regardless of test ordering on the shared git-on server.
const PATH = "/docs/guides/getting-started";

test("editing a page grows its history without a server restart", async ({ page }) => {
  const marker = `e2e history ${Date.now()}`;

  // 1. Baseline history is EMPTY — the repo is `git init`ed, not seeded (MF-4): git_enabled:true but no commits.
  await page.goto(`${PATH}?mode=history`);
  await expect(page.locator("[data-pb-history]")).toBeVisible();
  // The git-off copy must NOT show (Git is forced on); the empty state OR zero commit rows is the baseline.
  await expect(page.locator("[data-pb-history-disabled]")).toHaveCount(0);
  await expect(page.locator("[data-pb-history-empty]")).toBeVisible();
  await expect(page.locator("[data-pb-commit]")).toHaveCount(0);

  // 2. Edit + save (reuse edit.spec.ts's steps). The save commits → the first commit is created.
  await page.goto(`${PATH}?mode=edit`);
  await expect(page.locator("[data-pb-editor]")).toBeVisible();
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("End");
  await content.pressSequentially(`\n\n${marker}\n`);
  await expect(page.locator("[data-pb-preview] .pb-prose")).toContainText(marker);
  const save = page.locator("[data-pb-save]");
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  // 3a. The history GREW — at least one commit row now exists where there was none (the same running
  // server now reports the first commit; no restart).
  await page.goto(`${PATH}?mode=history`);
  await expect(page.locator("[data-pb-history]")).toBeVisible();
  await expect(page.locator("[data-pb-commit]").first()).toBeVisible();
  await expect(page.locator("[data-pb-history-empty]")).toHaveCount(0);

  // 3b. The read view reflects the edited content (the watcher reindexed; the UI shows it).
  await page.goto(PATH);
  await expect(page.locator(".pb-prose")).toContainText(marker);
});
