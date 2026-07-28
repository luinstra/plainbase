import { expect, test } from "@playwright/test";

/**
 * A configured root that is NOT SERVING, against a real server booted with its second root's tree
 * absent (playwright.config.ts `multi-root-unavailable` project: SMOKE_ROOTS=multi-missing).
 *
 * A separate SERVER, not a separate page state: availability is decided at boot and is sticky until
 * restart, so this shape cannot be reached from the both-serving one.
 *
 * The server EMPTIES a down root's subtree rather than serving a stale listing, so the thing under
 * test is that the SPA says so. An empty list would tell the reader their docs are gone.
 */
test("the unavailable root's section shows the outage notice, and docs still browses", async ({ page }) => {
  const response = await page.goto("/docs/welcome");
  expect(response?.status()).toBe(200);
  await expect(page.locator(".pb-prose h1")).toContainText("Welcome to Demo Docs");

  // Still ONE aside with both roots' sections: a down root is LISTED, never dropped — so the reader
  // can tell "this root is down" from "this root does not exist".
  await expect(page.locator("aside[data-pb-sidebar]")).toHaveCount(1);
  await expect(page.locator("[data-pb-root-section]")).toHaveCount(2);

  const outage = page.locator('[data-pb-root-section-unavailable="extra"]');
  await expect(outage).toBeVisible();
  await expect(outage).toContainText("Unavailable");
  await expect(outage).toContainText('The "extra" root is not serving right now');
  // The affordance REPLACES the tree — the section carries no nav rows at all, empty or otherwise.
  await expect(page.locator('[data-pb-root-section="extra"] [data-pb-nav-item]')).toHaveCount(0);

  // docs is untouched: one root's outage is not the server's.
  const docsSection = page.locator('[data-pb-root-section="docs"]');
  await docsSection.getByRole("link", { name: "Deploy Guide" }).click();
  await expect(page).toHaveURL("/docs/guides/deploy-guide");
  await expect(page.locator(".pb-prose h1")).toContainText("Deploy Guide");
});
