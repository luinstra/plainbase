import { expect, test, type Page } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

const PAGE = "/docs/scratch/todo";

async function captureCspViolations(page: Page): Promise<void> {
  await page.addInitScript(() => {
    (window as unknown as { __pbCspViolations: string[] }).__pbCspViolations = [];
    window.addEventListener("securitypolicyviolation", (event) => {
      (window as unknown as { __pbCspViolations: string[] }).__pbCspViolations.push(event.violatedDirective);
    });
  });
}

async function diagramColors(page: Page, selector: string): Promise<string[]> {
  return page.locator(selector).first().evaluate((svg) =>
    [...svg.querySelectorAll<SVGElement>("rect, path, line, polygon, polyline, text")]
      .flatMap((element) => {
        const style = getComputedStyle(element);
        return [style.fill, style.stroke];
      })
      .filter((color) => color !== "none"),
  );
}

test("renders multiple Mermaid diagrams in page preview and rerenders with the theme", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await captureCspViolations(page);

  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  await expect(page.locator("[data-pb-editor]")).toBeVisible();
  const source = [
    "",
    "```mermaid",
    "flowchart LR",
    "  A[Start] --> B[Finish]",
    "```",
    "",
    "~~~mermaid",
    "sequenceDiagram",
    "  Alice->>Bob: Hello",
    "~~~",
    "",
    "~~~mermaid",
    "this is not a diagram",
    "~~~",
    "",
  ].join("\n");
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("End");
  await page.keyboard.insertText(source);
  await page.locator("[data-pb-preview-toggle]").click();

  const preview = page.locator("[data-pb-preview] .pb-prose");
  await expect(preview).toBeVisible();
  await expect(preview.locator("pre code.language-mermaid")).toHaveCount(3);
  await expect(preview.locator("[data-pb-mermaid] svg")).toHaveCount(2);
  await expect(preview.locator("pre").nth(0)).toBeHidden();
  await expect(preview.locator("pre").nth(1)).toBeHidden();
  await expect(preview.locator("pre").nth(2)).toBeVisible();
  await expect(preview.locator(".pb-mermaid-error")).toHaveRole("status");

  const lightColors = await diagramColors(page, '[data-pb-preview] [data-pb-mermaid] svg');
  expect(lightColors.length).toBeGreaterThan(0);
  for (const svg of await preview.locator("[data-pb-mermaid] svg").all()) {
    const box = await svg.boundingBox();
    expect(box?.width).toBeGreaterThan(0);
    expect(box?.height).toBeGreaterThan(0);
  }

  await page.locator("[data-pb-theme-toggle]").click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(preview.locator('[data-pb-mermaid-theme="dark"]')).toHaveCount(2);
  await expect(preview.locator("[data-pb-mermaid] svg")).toHaveCount(2);
  const darkColors = await diagramColors(page, '[data-pb-preview] [data-pb-mermaid] svg');
  expect(darkColors).not.toEqual(lightColors);

  await page.locator("[data-pb-theme-toggle]").click();
  await expect(page.locator("html")).not.toHaveAttribute("data-theme");
  await expect(preview.locator('[data-pb-mermaid-theme="light"]')).toHaveCount(2);
  expect(await diagramColors(page, '[data-pb-preview] [data-pb-mermaid] svg')).toEqual(lightColors);

  const violations = await page.evaluate(() => (window as unknown as { __pbCspViolations: string[] }).__pbCspViolations);
  expect(violations).toEqual([]);
});

test("renders a saved page diagram with accessibility and hostile-input protections", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await captureCspViolations(page);
  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  await expect(page.locator("[data-pb-editor]")).toBeVisible();
  const bodyStyle = await page.evaluate(() => {
    const style = getComputedStyle(document.body);
    return { backgroundColor: style.backgroundColor, display: style.display };
  });
  const source = [
    "",
    "~~~mermaid",
    "---",
    "config:",
    "  securityLevel: loose",
    "  startOnLoad: true",
    "  htmlLabels: true",
    "  flowchart:",
    "    htmlLabels: true",
    "  theme: dark",
    '  themeCSS: "body { display: none !important; }"',
    "  dompurifyConfig:",
    "    ADD_TAGS: [script]",
    "  maxTextSize: 1",
    "  maxEdges: 1",
    "  themeVariables:",
    '    primaryColor: "red"',
    "---",
    '%%{init: {"securityLevel":"loose","startOnLoad":true,"theme":"dark","htmlLabels":true,"themeCSS":"body { display: none !important; }","dompurifyConfig":{"ADD_TAGS":["script"]},"maxTextSize":1,"maxEdges":1}}%%',
    "flowchart LR",
    "accTitle: Embedded security diagram",
    "accDescr: A safe accessible description",
    'A["<img src=x onerror=alert(1)>"] --> B[Finish]',
    'click A "javascript:alert(1)"',
    "~~~",
    "",
  ].join("\n");
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("End");
  await page.keyboard.insertText(source);
  await page.locator("[data-pb-save]").click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  await gotoExpectStatus(page, PAGE);
  const svg = page.locator(".pb-prose [data-pb-mermaid] svg");
  await expect(svg).toHaveCount(1);
  await expect(svg.locator("title")).toHaveText("Embedded security diagram");
  await expect(svg.locator("desc")).toHaveText("A safe accessible description");
  const accessibility = await svg.evaluate((element) => ({
    titleId: element.querySelector("title")?.id,
    descriptionId: element.querySelector("desc")?.id,
    labelledBy: element.getAttribute("aria-labelledby"),
    describedBy: element.getAttribute("aria-describedby"),
  }));
  expect(accessibility.labelledBy).toContain(accessibility.titleId);
  expect(accessibility.describedBy).toContain(accessibility.descriptionId);

  const mermaidModuleUrl = await page.evaluate(() =>
    performance
      .getEntriesByType("resource")
      .map((entry) => entry.name)
      .find((name) => name.includes("/mermaid.core-")),
  );
  if (!mermaidModuleUrl) throw new Error("Mermaid core chunk was not loaded");
  const effectiveConfig = await page.evaluate(async (url) => {
    const mermaid = (await import(url)).default;
    return mermaid.mermaidAPI.getConfig();
  }, mermaidModuleUrl);
  expect(effectiveConfig).toMatchObject({
    securityLevel: "strict",
    startOnLoad: false,
    maxTextSize: 50000,
    maxEdges: 500,
    suppressErrorRendering: true,
    theme: "base",
    themeCSS: "",
    htmlLabels: false,
  });
  expect(effectiveConfig).not.toHaveProperty("dompurifyConfig");
  expect(effectiveConfig.flowchart.htmlLabels).not.toBe(true);
  expect(effectiveConfig.secure).toEqual([
    "secure",
    "securityLevel",
    "startOnLoad",
    "maxTextSize",
    "suppressErrorRendering",
    "maxEdges",
    "theme",
    "themeCSS",
    "themeVariables",
    "fontFamily",
    "altFontFamily",
    "fontSize",
    "htmlLabels",
    "dompurifyConfig",
  ]);

  const unsafeNodes = await svg.evaluate((element) => ({
    foreignObjects: element.querySelectorAll("foreignObject").length,
    eventHandlers: element.querySelectorAll("[onclick], [onerror], [onload]").length,
    javascriptLinks: [...element.querySelectorAll("a")]
      .map((link) => link.getAttribute("href"))
      .filter((href) => href?.toLowerCase().startsWith("javascript:")),
  }));
  expect(unsafeNodes).toEqual({ foreignObjects: 0, eventHandlers: 0, javascriptLinks: [] });
  expect(await page.evaluate(() => {
    const style = getComputedStyle(document.body);
    return { backgroundColor: style.backgroundColor, display: style.display };
  })).toEqual(bodyStyle);
  expect(await page.locator("[data-pb-mermaid-scratch]").count()).toBe(0);

  const violations = await page.evaluate(() => (window as unknown as { __pbCspViolations: string[] }).__pbCspViolations);
  expect(violations).toEqual([]);
});

test("does not request Mermaid chunks for ordinary page prose", async ({ page }) => {
  await gotoExpectStatus(page, "/docs/welcome");
  await expect(page.locator(".pb-prose h1")).toContainText("Welcome to Demo Docs");
  await page.waitForLoadState("networkidle");
  const mermaidRequests = await page.evaluate(() =>
    performance
      .getEntriesByType("resource")
      .map((entry) => entry.name)
      .filter((name) => name.includes("mermaid") || name.includes("elk-")),
  );
  expect(mermaidRequests).toEqual([]);
});

test("renders a standalone mmd source through the shell and guarded asset URL", async ({ page, request }) => {
  await gotoExpectStatus(page, "/browse/docs/diagrams/standalone.mmd");
  await expect(page.locator("[data-pb-diagram]")).toBeVisible();
  await expect(page.locator("[data-pb-diagram] h1")).toHaveText("standalone.mmd");
  await expect(page.locator("[data-pb-mermaid] svg")).toHaveCount(1);
  await expect(page.locator("pre")).toBeHidden();

  const sourceLink = page.locator("[data-pb-diagram-source]");
  await expect(sourceLink).toHaveAttribute("href", "/assets/docs/diagrams/standalone.mmd");
  const source = await request.get("/assets/docs/diagrams/standalone.mmd");
  expect(source.status()).toBe(200);
  expect(source.headers()["content-type"]).toContain("application/octet-stream");
  expect(await source.text()).toBe("flowchart LR\n  A[Standalone] --> B[Mermaid]\n");

  await gotoExpectStatus(page, "/browse/docs/diagrams/missing.mmd");
  await expect(page.locator("[data-pb-not-found]")).toBeVisible();
});

test("preserves BOM and CRLF source bytes while rendering encoded paths", async ({ page, request }) => {
  await gotoExpectStatus(page, "/browse/docs/diagrams/bom-crlf.mmd");
  await expect(page.locator("[data-pb-diagram] h1")).toHaveText("bom-crlf.mmd");
  await expect(page.locator("[data-pb-mermaid] svg")).toHaveCount(1);
  const body = await (await request.get("/assets/docs/diagrams/bom-crlf.mmd")).body();
  expect([...body.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
  expect(body.toString("utf8")).toContain("\r\n");

  await gotoExpectStatus(page, "/browse/docs/diagrams/space%20name!'().mmd");
  await expect(page.locator("[data-pb-diagram] h1")).toHaveText("space name!'().mmd");
  await expect(page.locator("[data-pb-diagram-source]")).toHaveAttribute(
    "href",
    "/assets/docs/diagrams/space%20name%21%27%28%29.mmd",
  );
});

test("keeps hostile invalid standalone source readable without inserting it as markup", async ({ page }) => {
  await gotoExpectStatus(page, "/browse/docs/diagrams/fallback.mmd");
  await expect(page.locator("[data-pb-mermaid] svg")).toHaveCount(0);
  await expect(page.locator("[data-pb-diagram] pre")).toBeVisible();
  await expect(page.locator("[data-pb-diagram] code")).toContainText("<script>alert('xss')</script>");
  await expect(page.locator("[data-pb-diagram] script")).toHaveCount(0);
});
