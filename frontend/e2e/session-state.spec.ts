import { expect, test } from "./smoke-fixtures";

for (const failure of ["404", "transport"] as const) {
  test(`OFF reconnect ${failure} retains an unsaved editor and discussion mounts do not retry the session error`, async ({ page }) => {
    let requests = 0;
    let release!: () => void;
    const held = new Promise<void>((resolve) => { release = resolve; });
    await page.route("**/api/v1/session", async (route) => {
      requests++;
      if (requests === 2) await held;
      if (failure === "transport" && requests > 1) await route.abort("connectionfailed");
      else await route.continue();
    });
    try {
      expect((await page.goto("/docs/scratch/todo?mode=edit"))?.status()).toBe(200);
      const content = page.locator("[data-pb-codemirror] .cm-content");
      await expect(content).toBeVisible();
      await page.waitForLoadState("networkidle");
      expect(requests).toBe(1);
      await content.focus();
      await content.press("ControlOrMeta+End");
      await page.keyboard.insertText("\nUnsaved reconnect marker");
      await expect(content).toContainText("Unsaved reconnect marker");
      const original = await page.locator(".cm-editor").elementHandle();
      expect(original).not.toBeNull();
      await page.evaluate(() => window.dispatchEvent(new Event("offline")));
      await page.evaluate(() => window.dispatchEvent(new Event("online")));
      await expect.poll(() => requests).toBe(2);
      expect(await page.locator(".cm-editor").evaluate((element, original) => element === original, original)).toBe(true);
      await expect(content).toContainText("Unsaved reconnect marker");
      release();
      const settledRequests = failure === "transport" ? 4 : 2;
      await expect.poll(() => requests, { timeout: 15000 }).toBe(settledRequests);
      await page.waitForLoadState("networkidle");
      expect(await page.locator(".cm-editor").evaluate((element, original) => element === original, original)).toBe(true);
      await expect(content).toContainText("Unsaved reconnect marker");
      await content.press("ControlOrMeta+z");
      await expect(page.getByRole("status", { name: "Save state" })).toHaveText("Saved");
      await page.getByRole("button", { name: "Done editing" }).click();
      await expect(page.locator("[data-pb-discussion-panel]")).toBeVisible();
      await page.waitForLoadState("networkidle");
      expect(requests).toBe(settledRequests);
      await page.getByRole("link", { name: "Edit this page" }).click();
      await expect(content).toBeVisible();
      await page.getByRole("button", { name: "Done editing" }).click();
      await expect(page.locator("[data-pb-discussion-panel]")).toBeVisible();
      await page.waitForLoadState("networkidle");
      expect(requests).toBe(settledRequests);
    } finally {
      release();
    }
  });
}
