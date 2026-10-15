import { expect, test, type Page } from "./smoke-fixtures";
import { gotoAndWaitForSearchReady, waitForSearchReady } from "./helpers";

async function changeSystemMode(page: Page, mode: "light" | "dark"): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as { __pbSystemChange: Promise<boolean> }).__pbSystemChange = new Promise((resolve) => {
      window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", (event) => resolve(event.matches), { once: true });
    });
  });
  await page.emulateMedia({ colorScheme: mode });
  expect(await page.evaluate(() => (window as unknown as { __pbSystemChange: Promise<boolean> }).__pbSystemChange)).toBe(mode === "dark");
}

test("without a stored choice the loaded app follows system changes", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await gotoAndWaitForSearchReady(page, "/docs/scratch/todo");
  await expect(page.locator("html")).not.toHaveAttribute("data-theme");
  await changeSystemMode(page, "dark");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
});

test("failed persistence keeps toggles in the session until reload", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await page.addInitScript(() => {
    localStorage.setItem("pb-theme", "light");
    Storage.prototype.setItem = () => { throw new Error("Storage write unavailable"); };
  });
  await gotoAndWaitForSearchReady(page, "/docs/scratch/todo");
  const root = page.locator("html");
  const toggle = page.locator("[data-pb-theme-toggle]");
  await expect(root).not.toHaveAttribute("data-theme");
  await toggle.click();
  await expect.soft(root, "first click keeps the session choice").toHaveAttribute("data-theme", "dark");
  await changeSystemMode(page, "dark");
  await expect.soft(root, "session choice survives dark system change").toHaveAttribute("data-theme", "dark");
  await changeSystemMode(page, "light");
  await expect.soft(root, "session choice survives light system change").toHaveAttribute("data-theme", "dark");
  await page.reload();
  await waitForSearchReady(page);
  await expect.soft(root, "reload resolves the stored choice").not.toHaveAttribute("data-theme");
  await toggle.click();
  await expect.soft(root, "first click after reload").toHaveAttribute("data-theme", "dark");
  await toggle.click();
  await expect.soft(root, "second click after reload").not.toHaveAttribute("data-theme");
});

test("unavailable color-scheme queries allow mounting and toggling", async ({ page }) => {
  await page.addInitScript(() => {
    window.matchMedia = (query) => {
      if (query.includes("prefers-color-scheme")) throw new Error("Color-scheme query unavailable");
      return {
        matches: false, media: query, onchange: null,
        addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent() { return false; },
      };
    };
  });
  await gotoAndWaitForSearchReady(page, "/docs/scratch/todo");
  await expect(page.locator("html")).not.toHaveAttribute("data-theme");
  await page.locator("[data-pb-theme-toggle]").click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
});
