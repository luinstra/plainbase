import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

/**
 * W7 master criterion 6 (Playwright, real server): an edited page shows updated content AND updated
 * history WITHOUT a server restart. Each test attempt owns a fresh Git-enabled content copy, so baseline
 * history is EMPTY and the FIRST save creates the first commit.
 */
const PATH = "/docs/guides/getting-started";

test("editing a page grows its history without a server restart", async ({ page }) => {
  const marker = `e2e history ${Date.now()}`;

  // 1. Baseline history is EMPTY — the repo is `git init`ed, not seeded (MF-4): git_enabled:true but no commits.
  await gotoExpectStatus(page, `${PATH}?mode=history`);
  await expect(page.locator("[data-pb-history]")).toBeVisible();
  // The git-off copy must NOT show (Git is forced on); the empty state OR zero commit rows is the baseline.
  await expect(page.locator("[data-pb-history-disabled]")).toHaveCount(0);
  await expect(page.locator("[data-pb-history-empty]")).toBeVisible();
  await expect(page.locator("[data-pb-commit]")).toHaveCount(0);

  // 2. Edit + save (reuse edit.spec.ts's steps). The save commits → the first commit is created.
  await gotoExpectStatus(page, `${PATH}?mode=edit`);
  await expect(page.locator("[data-pb-editor]")).toBeVisible();
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("End");
  await content.pressSequentially(`\n\n${marker}\n`);
  // Confirm the edit registered via the on-demand preview (hidden by default — open it via the toggle).
  await page.locator("[data-pb-preview-toggle]").click();
  await expect(page.locator("[data-pb-preview] .pb-prose")).toContainText(marker);
  const save = page.locator("[data-pb-save]");
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  // 3a. The same server now reports the first commit; the test does not restart it between save and history.
  await gotoExpectStatus(page, `${PATH}?mode=history`);
  await expect(page.locator("[data-pb-history]")).toBeVisible();
  await expect(page.locator("[data-pb-commit]").first()).toBeVisible();
  await expect(page.locator("[data-pb-history-empty]")).toHaveCount(0);

  // 3b. The read view reflects the edited content (the watcher reindexed; the UI shows it).
  await gotoExpectStatus(page, PATH);
  await expect(page.locator(".pb-prose")).toContainText(marker);
});
