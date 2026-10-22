import { writeFileSync } from "node:fs";
import { expect, test, type Page } from "./smoke-fixtures";

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

async function settle(page: Page): Promise<void> {
  await expect(page.locator("[data-pb-page-article]")).toBeVisible();
  await expect(page.locator("[data-pb-initial]")).toHaveCount(0);
  await page.waitForLoadState("networkidle");
}

for (const path of ["/docs/reader/guides/alpha", "/docs/reader/guides", "/docs/guides/deploy-guide"]) {
  test(`reader content at ${path} renders under the served CSP`, async ({ page }, testInfo) => {
    await captureViolations(page);
    const response = await page.goto(path);
    expect(response?.status()).toBe(200);
    expect(response!.headers()["content-security-policy"]).toContain("script-src 'self' 'sha256-");
    await settle(page);
    if (path === "/docs/guides/deploy-guide") {
      const image = page.locator('[data-pb-page-article] img[src*="diagram.svg"]');
      await expect(image).toBeVisible();
      await expect.poll(() => image.evaluate((node) => (node as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
    }
    expect(await readViolations(page)).toEqual([]);

    if (path === "/docs/guides/deploy-guide") {
      await page.evaluate(() => {
        const image = document.createElement("img");
        image.src = "http://example.invalid/pb-csp-probe.png";
        document.body.append(image);
      });
      await expect.poll(async () => (await readViolations(page)).filter(
        (violation) => violation.violatedDirective.startsWith("img-src"),
      )).toHaveLength(1);
      const violations = await readViolations(page);
      expect(violations[0].blockedURI).toBe("http://example.invalid/pb-csp-probe.png");
      const controlLog = testInfo.outputPath("csp-positive-control.json");
      writeFileSync(controlLog, JSON.stringify(violations, null, 2));
      await testInfo.attach("csp-positive-control", {
        path: controlLog, contentType: "application/json",
      });
    }
  });
}
