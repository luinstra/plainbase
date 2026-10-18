import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import type { Locator } from "@playwright/test";
import { expect, test, type Page } from "./smoke-fixtures";
import { gotoAndWaitForSearchReady, gotoExpectStatus } from "./helpers";
import { normalizeColors } from "./theme-colors";
import { themeMarkdown as markdown } from "./themePages";

type Color = [number, number, number, number];
type StyleValue = string | Color;
type Reading = { count: number | "present"; elements: Array<Record<string, StyleValue>> };
type Mode = {
  sections: Record<string, Record<string, Reading>>;
  tokens: Record<string, Color>;
  fonts: { "body-sans": string; "editor-mono": string };
  absent: Record<string, number>;
  mermaid: Record<string, Reading>;
};
type Violation = { violatedDirective: string; blockedURI: string };
type Baseline = {
  provenance: {
    sourceHashes: Record<string, string>;
    browserVersion: string;
    playwrightVersion: string;
    viewport: { width: number; height: number };
    deviceScaleFactor: number;
    rootFontSize: string;
    captureCommand: string;
  };
  modes: { light: Mode; dark: Mode };
};

const captureCommand = "./gradlew :frontend:smokeTest -PsmokeArgs='theme-baseline.spec.ts --project=open' --console=plain";
const colorProperties = new Set(["background-color", "color", "border-color", "border-left-color", "border-right-color", "fill", "stroke"]);
const semanticNames = (JSON.parse(readFileSync("src/__tests__/fixtures/theme/legacy-tokens.json", "utf8")) as { semantic: string[] }).semantic;

async function readStyles(page: Page, selector: string, properties: string[], pseudo?: string): Promise<Reading> {
  const elements: Array<Record<string, StyleValue>> = await page.locator(selector).evaluateAll((elements, { properties, pseudo }) =>
    elements.map((element) => {
      const style = getComputedStyle(element, pseudo);
      return Object.fromEntries(properties.map((property) => [property,
        property === "src" || property.startsWith("data-") ? element.getAttribute(property) ?? "" : style.getPropertyValue(property),
      ]));
    }), { properties, pseudo });
  // SVG no-paint values are computed strings, rather than colours to send through the canvas.
  const isColor = (property: string, value: StyleValue) => colorProperties.has(property)
    && !((property === "fill" || property === "stroke") && value === "none");
  const colors = await normalizeColors(page, elements.flatMap((element) =>
    properties.filter((property) => isColor(property, element[property])).map((property) => element[property] as string)));
  let index = 0;
  for (const element of elements) for (const property of properties) {
    if (isColor(property, element[property])) element[property] = colors[index++];
  }
  return { count: elements.length, elements };
}

async function selectContents(page: Page, target: Locator) {
  await target.evaluate((element) => {
    const range = document.createRange(); range.selectNodeContents(element);
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range);
  });
  expect(await page.evaluate(() => window.getSelection()?.toString().length)).toBeGreaterThan(0);
}

async function splitDropShadow(page: Page, value: string): Promise<Record<string, StyleValue>> {
  const start = value.search(/(?:rgba?|color)\(/);
  if (start < 0) throw new Error(`missing drop-shadow colour in ${value}`);
  let depth = 0;
  let end = start;
  for (; end < value.length; end++) {
    if (value[end] === "(") depth++;
    if (value[end] === ")" && --depth === 0) { end++; break; }
  }
  if (depth) throw new Error(`unbalanced drop-shadow colour in ${value}`);
  const [color] = await normalizeColors(page, [value.slice(start, end)]);
  return {
    "drop-shadow-color": color,
    "drop-shadow-offsets": (value.slice(0, start) + value.slice(end)).replace(/\s+/g, " ").trim(),
  };
}

async function readTokens(page: Page): Promise<Record<string, Color>> {
  const values = await page.evaluate((names) => {
    const probe = document.createElement("span");
    document.body.appendChild(probe);
    try {
      return names.map((name) => { probe.style.color = `var(${name})`; return getComputedStyle(probe).color; });
    } finally { probe.remove(); }
  }, semanticNames);
  const colors = await normalizeColors(page, values);
  return Object.fromEntries(semanticNames.map((name, i) => [name, colors[i]]));
}

function compareFrozen(actual: unknown, expected: unknown, location: string) {
  if (Array.isArray(expected)) {
    expect(Array.isArray(actual), location).toBe(true);
    const values = actual as unknown[];
    expect(values.length, location).toBe(expected.length);
    if (expected.length === 4 && expected.every((value) => typeof value === "number")) {
      expected.forEach((value, i) => {
        expect(typeof values[i], `${location}[${i}]`).toBe("number");
        expect(Math.abs((values[i] as number) - value), `${location}[${i}]`).toBeLessThanOrEqual(0.002);
      });
    } else expected.forEach((value, i) => compareFrozen(values[i], value, `${location}[${i}]`));
  } else if (expected !== null && typeof expected === "object") {
    expect(actual !== null && typeof actual === "object", location).toBe(true);
    const values = actual as Record<string, unknown>;
    expect(Object.keys(values).sort(), location).toEqual(Object.keys(expected).sort());
    for (const [key, value] of Object.entries(expected)) compareFrozen(values[key], value, `${location}.${key}`);
  } else expect(actual, location).toBe(expected);
}

test("built-in modes match the frozen appearance baseline", async ({ page, request, browser, smokeServer }, testInfo) => {
  test.setTimeout(120_000);
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.addInitScript(() => {
    const sink: Violation[] = [];
    (window as unknown as { __cspViolations: Violation[] }).__cspViolations = sink;
    window.addEventListener("securitypolicyviolation", (event) => {
      sink.push({ violatedDirective: event.violatedDirective, blockedURI: event.blockedURI });
    });
  });
  expect(semanticNames).toHaveLength(57);
  writeFileSync(path.join(smokeServer.contentDir, "theme-baseline.md"), markdown);
  await expect.poll(async () => (await request.get("/api/v1/pages/by-path/docs/theme-baseline")).status()).toBe(200);
  const response = await request.get("/api/v1/pages/by-path/docs/scratch/todo");
  expect(response.ok()).toBe(true);
  const { id } = await response.json() as { id: string };
  async function save(data: string) {
    const current = await request.get(`/api/v1/pages/${id}`);
    expect(current.ok()).toBe(true);
    const result = await request.put(`/api/v1/pages/${id}`, {
      headers: { "content-type": "text/markdown", "if-match": current.headers()["etag"] }, data,
    });
    expect(result.ok()).toBe(true);
  }
  const revisionA = markdown + "Revision marker: one\n";
  const revisionB = revisionA.replace("Revision marker: one", "Revision marker: two") + "Added in revision two.\n";
  await save(revisionA);
  await save(revisionB);
  await save(revisionB.replace("owner: Ada Lovelace\n", ""));

  async function assertDocument(mode: "light" | "dark") {
    if (mode === "dark") await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    else await expect(page.locator("html")).not.toHaveAttribute("data-theme");
    await page.evaluate(() => document.fonts.ready);
    await page.mouse.move(0, 0);
  }
  async function assertCsp() {
    expect(await page.evaluate(() => (window as unknown as { __cspViolations: Violation[] }).__cspViolations)).toEqual([]);
  }
  const renderedEditorSelections: Record<"light" | "dark", Reading["elements"]> = { light: [], dark: [] };
  async function capture(mode: "light" | "dark"): Promise<Mode> {
    await page.emulateMedia({ colorScheme: mode });
    const result: Mode = { sections: {}, tokens: {}, fonts: { "body-sans": "", "editor-mono": "" }, absent: {}, mermaid: {} };
    async function read(section: string, selector: string, properties: string[], pseudo?: string) {
      const reading = await readStyles(page, selector, properties, pseudo);
      expect(reading.count, `${mode} ${section} ${selector}`).toBeGreaterThan(0);
      (result.sections[section] ??= {})[selector] = reading;
      return reading;
    }

    await gotoAndWaitForSearchReady(page, "/docs/theme-baseline");
    await assertDocument(mode);
    const diagram = ".pb-prose [data-pb-mermaid] svg";
    await expect(page.locator(diagram)).toBeVisible();
    await expect(page.locator(".pb-discussion-primary")).toBeVisible();
    await read("body", "body", ["background-color", "color", "font-family", "font-size"]);
    await read("body", "html", ["color-scheme"]);
    result.fonts["body-sans"] = result.sections.body.body.elements[0]["font-family"] as string;
    for (const selector of [".pb-logo-light", ".pb-logo-dark"]) await read("logo", selector, ["display", "src"]);
    for (const selector of ["[data-pb-header]", "[data-pb-sidebar]"]) await read("chrome", selector, ["background-color"]);
    await read("chrome", '.pb-sidebar [aria-current="page"]', ["background-color", "color"]);
    for (const element of ["h1", "p", "a"]) await read("prose", `.pb-prose ${element}`, ["color"]);
    await read("prose", ".pb-prose blockquote", ["border-left-color", "color"]);
    await read("prose", ".pb-prose th", ["background-color"]);
    await read("prose", ".pb-prose :not(pre) > code", ["background-color", "font-family", "font-size", "border-radius"]);
    await read("prose", ".pb-prose pre", ["background-color", "border-color", "border-radius"]);
    await read("prose", ".pb-prose pre code", ["color", "font-family"]);
    for (const kind of ["keyword", "string", "comment", "number", "title", "attr"]) {
      await read("code", `.pb-prose .hljs-${kind}`, ["color"]);
    }
    await read("callouts", ".pb-prose .pb-callout", ["background-color", "border-left-color", "border-radius", "data-pb-callout"]);
    await read("callouts", ".pb-prose .pb-callout .pb-callout", ["background-color"]);
    await read("callouts", ".pb-prose .pb-callout-title", ["color"]);
    await selectContents(page, page.locator(".pb-prose p").first());
    await read("selection-prose", ".pb-prose p", ["background-color", "color"], "::selection");
    await read("discussion", ".pb-discussion-primary", ["background-color", "color"]);
    for (const selector of ["[data-pb-theme-toggle]", ".pb-avatar"]) await read("radii", selector, ["border-radius"]);
    result.tokens = await readTokens(page);
    expect(Object.keys(result.tokens)).toHaveLength(57);
    // Confirmed Mermaid 12 SVG selectors: nodes, clusters, edge paths, marker paths and edge-label rectangles.
    // Every suffix below has nonzero matches in both modes; capture also saves the actual rendered SVG.
    const diagramProbes: Array<[string, string[]]> = [
      [".node rect", ["fill", "stroke", "filter"]], [".node text", ["fill"]], [".cluster rect", ["fill", "stroke"]],
      [".edgePaths path", ["stroke", "stroke-width"]], ["marker path", ["fill", "stroke"]], [".edgeLabel rect", ["fill"]],
    ];
    writeFileSync(testInfo.outputPath(`${mode}-mermaid.svg`), await page.locator(diagram).evaluate((element) => element.outerHTML));
    for (const [suffix, properties] of diagramProbes) {
      const selector = `${diagram} ${suffix}`;
      const reading = await readStyles(page, selector, properties);
      expect(reading.count, `${mode} mermaid ${selector}`).toBeGreaterThan(0);
      for (const element of reading.elements) if ("filter" in element) {
        const shadow = await splitDropShadow(page, element.filter as string);
        delete element.filter;
        Object.assign(element, shadow);
      }
      result.mermaid[selector] = reading;
    }
    await assertCsp();

    await page.keyboard.press("ControlOrMeta+k");
    await expect(page.locator("[data-pb-search-input]")).toBeFocused();
    await expect(page.locator("[data-pb-search-panel]")).toBeVisible();
    await page.locator("[data-pb-search-input]").fill("theme-baseline");
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("[data-pb-search-active] .text-faint")).toBeVisible();
    for (const selector of [".pb-search", "[data-pb-search-panel]", "[data-pb-search-active]"]) {
      await read("search", selector, ["background-color"]);
    }
    await read("search", "[data-pb-search-active] .text-faint", ["color"]);
    await read("search", ".pb-search-scope", ["border-radius"]);
    await assertCsp();
    await page.keyboard.press("Escape");

    await gotoExpectStatus(page, "/docs/scratch/todo?mode=edit");
    await assertDocument(mode);
    await expect(page.locator("[data-pb-editor]")).toBeVisible();
    await page.locator(".pb-editor-property summary").filter({ hasText: "Owner" }).click();
    await page.locator("[data-pb-field-owner]").fill("Theme baseline");
    await expect(page.locator("[data-pb-save]")).toBeEnabled();
    await expect(page.locator(".cm-content")).toContainText("Theme baseline");
    for (const selector of ["[data-pb-search-trigger]", "[data-pb-field-owner]"]) {
      await read("fields", selector, ["background-color", "border-color"]);
    }
    await read("primary-action", "[data-pb-save]", ["background-color", "border-color", "color"]);
    const content = await read("editor", ".cm-content", ["font-family"]);
    result.fonts["editor-mono"] = content.elements[0]["font-family"] as string;
    await read("editor", ".cm-gutters", ["background-color", "color", "border-right-color"]);
    for (const selector of [".pb-editor-modes", ".pb-editor-modes button"]) await read("editor", selector, ["border-radius"]);
    await page.locator(".cm-content").focus();
    await selectContents(page, page.locator(".cm-line").filter({ hasText: /\S/ }).first());
    const selection = await read("selection-editor", ".cm-line", ["background-color", "color"], "::selection");
    renderedEditorSelections[mode] = selection.elements;
    // CodeMirror virtualizes lines; freeze one sample, then compare every rendered line directly to it.
    result.sections["selection-editor"][".cm-line"] = { count: "present", elements: [selection.elements[0]] };
    for (const selector of [".cm-activeLine", ".cm-selectionBackground"]) {
      result.absent[selector] = await page.locator(selector).count();
      expect(result.absent[selector], selector).toBe(0);
    }
    await assertCsp();

    await gotoExpectStatus(page, "/docs/scratch/todo?mode=history");
    await assertDocument(mode);
    const commits = page.locator("[data-pb-commit] button");
    await expect(commits).toHaveCount(3);
    await commits.nth(1).click();
    await commits.nth(2).click();
    await expect(page.locator("[data-pb-diff]")).toBeVisible();
    for (const kind of ["add", "del"]) await read("diff", `[data-pb-diff-line="${kind}"]`, ["background-color"]);
    await read("diff", ".pb-diff-gutter", ["color"]);
    await assertCsp();

    await gotoExpectStatus(page, "/new");
    await assertDocument(mode);
    await expect(page.locator("[data-pb-new-title]")).toBeVisible();
    await page.locator("[data-pb-new-title]").fill("Theme baseline page");
    await expect(page.locator("[data-pb-new-create]")).toBeEnabled();
    await read("dialog", ".pb-new-dialog", ["background-color", "color", "border-radius"]);
    await read("dialog", ".pb-template-card", ["border-radius"]);
    await read("dialog", "[data-pb-new-create]", ["background-color", "color"]);
    await assertCsp();
    return result;
  }

  const modes = { light: await capture("light"), dark: await capture("dark") };
  const sourcePaths = [
    "src/styles/themes/generic.css", "src/styles/themes/light.css", "src/styles/themes/dark.css",
    "src/styles/app.css", "src/theme.ts", "src/lib/mermaid.ts", "index.html",
    "src/__tests__/contrastResolver.ts", "e2e/theme-baseline.spec.ts", "e2e/theme-colors.ts", "e2e/smoke-fixtures.ts", "playwright.config.ts", "package-lock.json",
  ];
  const baseline: Baseline = {
    provenance: {
      sourceHashes: Object.fromEntries(sourcePaths.map((source) => [
        `frontend/${source}`, createHash("sha256").update(readFileSync(source)).digest("hex"),
      ])),
      browserVersion: browser.version(),
      playwrightVersion: (JSON.parse(readFileSync("node_modules/@playwright/test/package.json", "utf8")) as { version: string }).version,
      viewport: page.viewportSize()!,
      deviceScaleFactor: await page.evaluate(() => window.devicePixelRatio),
      rootFontSize: await page.locator("html").evaluate((element) => getComputedStyle(element).fontSize),
      captureCommand,
    }, modes,
  };
  writeFileSync(testInfo.outputPath("theme-baseline.json"), JSON.stringify(baseline, null, 2) + "\n");
  const fixturePath = "e2e/fixtures/theme-baseline.json";
  expect(existsSync(fixturePath), "no frozen baseline; review the test-results artifact and copy it into the fixtures directory by hand").toBe(true);
  const frozen = JSON.parse(readFileSync(fixturePath, "utf8")) as Baseline;
  for (const key of ["viewport", "deviceScaleFactor", "rootFontSize"] as const) {
    expect(baseline.provenance[key], key).toEqual(frozen.provenance[key]);
  }
  for (const mode of ["light", "dark"] as const) {
    const sample = frozen.modes[mode].sections["selection-editor"][".cm-line"].elements[0];
    renderedEditorSelections[mode].forEach((element, index) =>
      compareFrozen(element, sample, `${mode} rendered editor selection[${index}]`));
  }
  compareFrozen(baseline.modes, frozen.modes, "modes");
});
