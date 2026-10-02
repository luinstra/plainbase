import type { Locator, Page } from "@playwright/test";
import { expect } from "@playwright/test";

/** Open the same native disclosure a reader uses; never locate hidden operations. */
export async function openDiscussionActions(scope: Page | Locator, name: string) {
  const button = scope.getByRole("button", { name, exact: true });
  const commentAction = name.includes("comment");
  const trigger = scope.getByLabel(commentAction ? /Actions for .*'s comment/ : "Discussion actions").first();
  await expect(trigger).toBeVisible();
  if (await button.count() === 1 && await button.isVisible()) return;
  if (!await trigger.evaluate((node) => (node.parentElement as HTMLDetailsElement).open)) await trigger.click();
}
