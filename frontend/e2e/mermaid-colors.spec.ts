import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type Page } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";
import { normalizeColors } from "./theme-colors";

type Color = [number, number, number, number];
type ColorCase = {
  name: string;
  family?: string;
  source: string;
  anchor?: string;
  alpha?: number;
  computedPrefix?: string;
  paintedPrefix?: string;
};
type AnchoredCase = ColorCase & { anchor: string };
type FillCase = AnchoredCase & { computedPrefix: string; paintedPrefix: string };

const fixture = JSON.parse(readFileSync(new URL("../src/__tests__/fixtures/theme/mermaid-colors.json", import.meta.url), "utf8")) as {
  anchors: Record<string, { srgb: [number, number, number] }>;
  cases: ColorCase[];
};
const fills = fixture.cases.filter((row): row is FillCase => Boolean(row.anchor && row.computedPrefix && row.paintedPrefix));
const diagramPath = "/browse/docs/diagrams/standalone.mmd";
const colorTolerance = 0.002;

function anchor(row: AnchoredCase): Color {
  return [...fixture.anchors[row.anchor].srgb, row.alpha ?? 1];
}

function difference(actual: Color, expected: Color): number {
  return Math.max(...expected.map((value, index) => Math.abs(actual[index] - value)));
}

async function computedRoles(page: Page, roles: string[]): Promise<string[]> {
  return page.evaluate((roles) => {
    const probe = document.createElement("span");
    probe.style.visibility = "hidden";
    document.body.append(probe);
    try {
      return roles.map((role) => {
        probe.style.backgroundColor = `var(${role})`;
        return getComputedStyle(probe).backgroundColor;
      });
    } finally {
      probe.remove();
    }
  }, roles);
}

async function nodeFill(page: Page): Promise<string> {
  const node = page.locator("[data-pb-mermaid] .node rect").first();
  await expect(node).toBeVisible();
  return node.evaluate((element) => getComputedStyle(element).fill);
}

function expectComputedSource(computed: string, row: AnchoredCase): void {
  const prefix = row.computedPrefix ?? row.source.slice(0, row.source.indexOf("(") + 1);
  expect.soft(computed.startsWith(prefix), "computed source family").toBe(true);
}

async function expectPaintedColors(page: Page, readings: Array<{ label: string; value: string }>, row: AnchoredCase): Promise<Color[]> {
  for (const reading of readings) {
    expect.soft(/^rgba?\(/.test(reading.value), `${reading.label} is legacy RGB`).toBe(true);
  }
  const normalized = await normalizeColors(page, readings.map((reading) => reading.value));
  const expected = anchor(row);
  normalized.forEach((color, index) => {
    expect.soft(difference(color, expected), `${readings[index].label} anchor`).toBeLessThanOrEqual(colorTolerance);
    expect.soft(color[3], `${readings[index].label} alpha`).toBeCloseTo(expected[3], 4);
  });
  return normalized;
}

function dropShadowColor(value: string): string {
  const start = value.search(/(?:rgba?|color|oklch|oklab|lab|lch)\(/);
  if (start < 0) throw new Error(`Missing drop-shadow color in ${value}`);
  let depth = 0;
  for (let end = start; end < value.length; end++) {
    if (value[end] === "(") depth++;
    if (value[end] === ")" && --depth === 0) return value.slice(start, end + 1);
  }
  throw new Error(`Unbalanced drop-shadow color in ${value}`);
}

test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
});

test("edge stroke follows the link role", async ({ page, smokeServer }, testInfo) => {
  writeFileSync(path.join(smokeServer.dataDir, "custom.css"), ":root { --pb-link: var(--pb-danger); }\n");
  await gotoExpectStatus(page, diagramPath);
  await expect(page.locator("[data-pb-mermaid] svg")).toBeVisible();
  const edge = page.locator("[data-pb-mermaid] .edgePaths path").first();
  await expect(edge).toBeAttached();
  const stroke = await edge.evaluate((element) => getComputedStyle(element).stroke);
  const fill = await nodeFill(page);
  const [danger, background, accent] = await computedRoles(page, ["--pb-danger", "--pb-diagram-node-bg", "--pb-accent"]);
  const [paintedEdge, expectedEdge, paintedNode, expectedNode, accentColor] =
    await normalizeColors(page, [stroke, danger, fill, background, accent]);
  const edgeDifference = difference(paintedEdge, expectedEdge);
  const nodeDifference = difference(paintedNode, expectedNode);
  writeFileSync(testInfo.outputPath("line-reading.json"), JSON.stringify({
    stroke, fill, danger, background, accent, edgeDifference, nodeDifference,
  }, null, 2) + "\n");

  expect(difference(accentColor, expectedEdge), "link override separates danger from accent").toBeGreaterThan(0.02);
  expect.soft(edgeDifference, "edge stroke matches the link override").toBeLessThanOrEqual(colorTolerance);
  expect.soft(nodeDifference, "node fill retains its background role").toBeLessThanOrEqual(colorTolerance);
});

for (const row of fills) {
  test(`node fill ${row.name}`, async ({ page, smokeServer, browser }, testInfo) => {
    writeFileSync(path.join(smokeServer.dataDir, "custom.css"), `:root { --pb-diagram-node-bg: ${row.source}; }\n`);
    await gotoExpectStatus(page, diagramPath);
    const [computed] = await computedRoles(page, ["--pb-diagram-node-bg"]);
    const observation = { name: row.name, family: row.family, source: row.source, computed, browserVersion: browser.version() };
    writeFileSync(testInfo.outputPath("color-reading.json"), JSON.stringify(observation, null, 2) + "\n");
    if (computed.startsWith("rgb(")) {
      const channels = computed.slice(4, -1).split(",").map(Number);
      expect(channels.every(Number.isInteger), "legacy computed channels must be integers").toBe(true);
    }
    expect.soft(computed.startsWith(row.computedPrefix), "computed source family").toBe(true);

    const painted = await nodeFill(page);
    const [normalized] = await normalizeColors(page, [painted]);
    const expected = anchor(row);
    writeFileSync(testInfo.outputPath("color-reading.json"), JSON.stringify({
      ...observation, painted, normalized, expected,
    }, null, 2) + "\n");

    expect.soft(painted.startsWith(row.paintedPrefix), "painted fill family").toBe(true);
    expect.soft(difference(normalized, expected), "painted fill anchor").toBeLessThanOrEqual(colorTolerance);
    expect.soft(normalized[3], "painted fill alpha").toBeCloseTo(expected[3], 4);
  });
}

test("a same-mode stylesheet edit changes diagram colors only after reload", async ({ page, smokeServer }, testInfo) => {
  const first = fills.find((row) => row.family === "oklch")!;
  const second = fills.find((row) => row.family === "oklab")!;
  expect(difference(anchor(first), anchor(second)), "reload anchors are visibly distinct").toBeGreaterThan(0.02);
  const cssPath = path.join(smokeServer.dataDir, "custom.css");
  writeFileSync(cssPath, `:root { --pb-diagram-node-bg: ${first.source}; }\n`);
  await gotoExpectStatus(page, diagramPath);
  const [before] = await normalizeColors(page, [await nodeFill(page)]);
  expect.soft(difference(before, anchor(first)), "initial fill uses the first stylesheet").toBeLessThanOrEqual(colorTolerance);

  writeFileSync(cssPath, `:root { --pb-diagram-node-bg: ${second.source}; }\n`);
  const liveCss = await page.evaluate(async () => {
    const response = await fetch("/_plainbase/custom.css");
    return { status: response.status, cacheControl: response.headers.get("cache-control"), text: await response.text() };
  });
  expect(liveCss.status).toBe(200);
  expect(liveCss.cacheControl).toContain("no-store");
  expect(liveCss.text).toContain(second.source);
  const [unchanged] = await normalizeColors(page, [await nodeFill(page)]);
  expect.soft(difference(unchanged, anchor(first)), "existing diagram retains the first stylesheet").toBeLessThanOrEqual(colorTolerance);

  await page.reload();
  const [reloaded] = await normalizeColors(page, [await nodeFill(page)]);
  expect.soft(difference(reloaded, anchor(second)), "reload uses the second stylesheet").toBeLessThanOrEqual(colorTolerance);
  writeFileSync(testInfo.outputPath("reload-reading.json"), JSON.stringify({ before, unchanged, reloaded }, null, 2) + "\n");
});

for (const element of [
  { name: "node ink accepts a modern color", role: "--pb-diagram-node-text", selector: ".node text", property: "fill", family: "lab" },
  { name: "node stroke accepts a modern color", role: "--pb-diagram-node-border", selector: ".node rect", property: "stroke", family: "oklab" },
]) {
  test(element.name, async ({ page, smokeServer }, testInfo) => {
    const row = fills.find((row) => row.family === element.family)!;
    writeFileSync(path.join(smokeServer.dataDir, "custom.css"), `:root { ${element.role}: ${row.source}; }\n`);
    await gotoExpectStatus(page, diagramPath);
    await expect(page.locator("[data-pb-mermaid] svg")).toBeVisible();
    const target = page.locator(`[data-pb-mermaid] ${element.selector}`).first();
    await expect(target).toBeAttached();
    const painted = await target.evaluate((target, property) => getComputedStyle(target).getPropertyValue(property), element.property);
    const [computed] = await computedRoles(page, [element.role]);
    const observation = { role: element.role, source: row.source, computed, painted };
    writeFileSync(testInfo.outputPath("element-reading.json"), JSON.stringify(observation, null, 2) + "\n");

    expectComputedSource(computed, row);
    const normalized = await expectPaintedColors(page, [{ label: element.name, value: painted }], row);
    writeFileSync(testInfo.outputPath("element-reading.json"), JSON.stringify({ ...observation, normalized }, null, 2) + "\n");
  });
}

test("edge and arrowhead accept a modern color", async ({ page, smokeServer }, testInfo) => {
  const row = fills.find((row) => row.family === "p3")!;
  writeFileSync(path.join(smokeServer.dataDir, "custom.css"), `:root { --pb-link: ${row.source}; }\n`);
  await gotoExpectStatus(page, diagramPath);
  await expect(page.locator("[data-pb-mermaid] svg")).toBeVisible();
  const edge = await page.locator("[data-pb-mermaid] .edgePaths path").first()
    .evaluate((element) => getComputedStyle(element).stroke);
  const arrowhead = await page.locator("[data-pb-mermaid] marker path").first()
    .evaluate((element) => getComputedStyle(element).fill);
  const [computed] = await computedRoles(page, ["--pb-link"]);
  const observation = { role: "--pb-link", source: row.source, computed, edge, arrowhead };
  writeFileSync(testInfo.outputPath("element-reading.json"), JSON.stringify(observation, null, 2) + "\n");

  expectComputedSource(computed, row);
  const normalized = await expectPaintedColors(page, [
    { label: "edge stroke", value: edge },
    { label: "arrowhead fill", value: arrowhead },
  ], row);
  writeFileSync(testInfo.outputPath("element-reading.json"), JSON.stringify({ ...observation, normalized }, null, 2) + "\n");
});

test("node shadow accepts a modern color with fractional alpha", async ({ page, smokeServer }, testInfo) => {
  const row = fixture.cases.find((row) => row.alpha === 0.14 && row.anchor) as AnchoredCase;
  writeFileSync(path.join(smokeServer.dataDir, "custom.css"), `:root { --pb-diagram-shadow: ${row.source}; }\n`);
  await gotoExpectStatus(page, diagramPath);
  const node = page.locator("[data-pb-mermaid] .node rect").first();
  await expect(node).toBeVisible();
  const filter = await node.evaluate((element) => getComputedStyle(element).filter);
  const [computed] = await computedRoles(page, ["--pb-diagram-shadow"]);
  const observation = { role: "--pb-diagram-shadow", source: row.source, computed, filter };
  writeFileSync(testInfo.outputPath("element-reading.json"), JSON.stringify(observation, null, 2) + "\n");

  expect.soft(filter, "node uses a drop-shadow").toContain("drop-shadow(");
  expectComputedSource(computed, row);
  const painted = dropShadowColor(filter);
  const normalized = await expectPaintedColors(page, [{ label: "shadow color", value: painted }], row);
  writeFileSync(testInfo.outputPath("element-reading.json"), JSON.stringify({ ...observation, painted, normalized }, null, 2) + "\n");
});

test("unsupported node colors keep the diagram source readable", async ({ page, smokeServer }, testInfo) => {
  const row = fixture.cases.find((row) => row.name === "rejects XYZ D65")!;
  const source = readFileSync(path.join(smokeServer.contentDir, "diagrams/standalone.mmd"), "utf8");
  writeFileSync(path.join(smokeServer.dataDir, "custom.css"), `:root { --pb-diagram-node-bg: ${row.source}; }\n`);
  await page.addInitScript(() => {
    (window as unknown as { __pbCspViolations: string[] }).__pbCspViolations = [];
    window.addEventListener("securitypolicyviolation", (event) => {
      (window as unknown as { __pbCspViolations: string[] }).__pbCspViolations.push(event.violatedDirective);
    });
  });
  await gotoExpectStatus(page, diagramPath);
  await expect(page.locator("[data-pb-mermaid] svg, .pb-mermaid-error")).toHaveCount(1);
  const [computed] = await computedRoles(page, ["--pb-diagram-node-bg"]);
  const observation = await page.evaluate(() => ({
    svgCount: document.querySelectorAll("[data-pb-mermaid] svg").length,
    statusCount: document.querySelectorAll(".pb-mermaid-error[role=status]").length,
    probeCount: document.querySelectorAll("[data-pb-mermaid-probe]").length,
    scratchCount: document.querySelectorAll("[data-pb-mermaid-scratch]").length,
    violations: (window as unknown as { __pbCspViolations: string[] }).__pbCspViolations,
  }));
  writeFileSync(testInfo.outputPath("unsupported-reading.json"), JSON.stringify({ ...observation, computed }, null, 2) + "\n");

  expect.soft(computed.startsWith(row.source.split(" ")[0]), "unsupported source reaches the role").toBe(true);
  expect.soft(observation.svgCount, "no diagram SVG").toBe(0);
  await expect.soft(page.locator("[data-pb-diagram] pre"), "canonical source is visible").toBeVisible();
  await expect.soft(page.locator("[data-pb-diagram] pre code"), "canonical source is unchanged").toHaveText(source);
  expect.soft(observation.statusCount, "one failure status").toBe(1);
  expect.soft(observation.probeCount, "no sampling probe").toBe(0);
  expect.soft(observation.scratchCount, "no rendering scratch").toBe(0);
  expect.soft(observation.violations, "no CSP violations").toEqual([]);
});

type DiagramInsertion = { source: string; theme: string | null; svgCount: number };
type DiagramLog = { insertions: DiagramInsertion[]; violations: string[]; flush: () => void };

async function captureDiagramInsertions(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const insertions: DiagramInsertion[] = [];
    const violations: string[] = [];
    function record(records: MutationRecord[]) {
      for (const record of records) {
        for (const added of record.addedNodes) {
          if (!(added instanceof Element)) continue;
          const diagrams = [...added.querySelectorAll<HTMLElement>("[data-pb-mermaid]")];
          if (added.matches("[data-pb-mermaid]")) diagrams.unshift(added as HTMLElement);
          for (const diagram of diagrams) {
            insertions.push({
              source: diagram.previousElementSibling?.querySelector("code.language-mermaid")?.textContent ?? "",
              theme: diagram.getAttribute("data-pb-mermaid-theme"),
              svgCount: diagram.querySelectorAll("svg").length,
            });
          }
        }
      }
    }
    const observer = new MutationObserver(record);
    observer.observe(document, { childList: true, subtree: true });
    (window as unknown as { __pbDiagramLog: DiagramLog }).__pbDiagramLog = {
      insertions, violations, flush: () => record(observer.takeRecords()),
    };
    window.addEventListener("securitypolicyviolation", (event) => violations.push(event.violatedDirective));
  });
}

async function readDiagramLog(page: Page, reset = false) {
  return page.evaluate((reset) => {
    const log = (window as unknown as { __pbDiagramLog: DiagramLog }).__pbDiagramLog;
    log.flush();
    const result = {
      insertions: [...log.insertions],
      violations: [...log.violations],
      probeCount: document.querySelectorAll("[data-pb-mermaid-probe]").length,
      scratchCount: document.querySelectorAll("[data-pb-mermaid-scratch]").length,
    };
    if (reset) log.insertions.length = 0;
    return result;
  }, reset);
}

async function diagramFills(page: Page, scope: string): Promise<string[]> {
  return page.locator(`${scope} [data-pb-mermaid]`).evaluateAll((diagrams) => diagrams.map((diagram) => {
    const node = diagram.querySelector(".node rect");
    if (!node) throw new Error("Diagram has no flowchart node");
    return getComputedStyle(node).fill;
  }));
}

test("display mode uses a custom dark node fill and restores the light fill", async ({ page, smokeServer }, testInfo) => {
  const row = fills.find((row) => row.family === "oklch")!;
  writeFileSync(path.join(smokeServer.dataDir, "custom.css"),
    `:root[data-theme="dark"] { --pb-diagram-node-bg: ${row.source}; }\n`);
  await gotoExpectStatus(page, diagramPath);
  const [lightRole] = await computedRoles(page, ["--pb-diagram-node-bg"]);
  const [light, expectedLight] = await normalizeColors(page, [await nodeFill(page), lightRole]);
  expect.soft(difference(light, expectedLight), "light fill matches its role").toBeLessThanOrEqual(colorTolerance);
  expect(difference(expectedLight, anchor(row)), "custom dark fill differs from light").toBeGreaterThan(0.02);

  await page.locator("[data-pb-theme-toggle]").click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect.soft(page.locator('[data-pb-mermaid-theme="dark"]'), "dark diagram is committed").toHaveCount(1);
  const [darkRole] = await computedRoles(page, ["--pb-diagram-node-bg"]);
  expectComputedSource(darkRole, row);
  const dark = await expectPaintedColors(page, [{ label: "dark node fill", value: await nodeFill(page) }], row);

  await page.locator("[data-pb-theme-toggle]").click();
  await expect(page.locator("html")).not.toHaveAttribute("data-theme");
  await expect.soft(page.locator('[data-pb-mermaid-theme="light"]'), "light diagram is restored").toHaveCount(1);
  const [restored] = await normalizeColors(page, [await nodeFill(page)]);
  expect.soft(difference(restored, expectedLight), "restored fill matches light").toBeLessThanOrEqual(colorTolerance);
  writeFileSync(testInfo.outputPath("mode-fill-reading.json"), JSON.stringify({ lightRole, light, expectedLight, darkRole, dark, restored }, null, 2) + "\n");
});

const modeSources = [
  "flowchart LR\n  A[Open a document] --> B[Read it]",
  "flowchart TD\n  C[Edit a document] --> D[Save it]",
];
const modeMarkdown = modeSources.map((source) => ["```mermaid", source, "```", ""].join("\n")).join("\n");

for (const surface of ["saved prose", "editor preview", "a standalone diagram"]) {
  test(`display mode replaces diagrams in ${surface}`, async ({ page, request, smokeServer }, testInfo) => {
    await captureDiagramInsertions(page);
    let scope: string;
    let count: number;
    if (surface === "saved prose") {
      writeFileSync(path.join(smokeServer.contentDir, "diagram-modes.md"), modeMarkdown);
      await expect.poll(async () => (await request.get("/api/v1/pages/by-path/docs/diagram-modes")).status()).toBe(200);
      await gotoExpectStatus(page, "/docs/diagram-modes");
      scope = ".pb-prose";
      count = modeSources.length;
    } else if (surface === "editor preview") {
      await page.setViewportSize({ width: 1848, height: 1400 });
      await gotoExpectStatus(page, "/docs/scratch/todo?mode=edit");
      await expect(page.locator("[data-pb-editor]")).toBeVisible();
      await page.locator("[data-pb-codemirror] .cm-content").click();
      await page.keyboard.press("End");
      await page.keyboard.insertText(`\n${modeMarkdown}`);
      await page.locator("[data-pb-preview-toggle]").click();
      scope = "[data-pb-preview] .pb-prose";
      count = modeSources.length;
    } else {
      await gotoExpectStatus(page, diagramPath);
      scope = "[data-pb-diagram]";
      count = 1;
    }
    await expect(page.locator(`${scope} [data-pb-mermaid] svg`)).toHaveCount(count);
    await expect.soft(page.locator(`${scope} [data-pb-mermaid-theme="light"]`), "all initial diagrams are light").toHaveCount(count);
    const sources = await page.locator(`${scope} pre > code.language-mermaid`).allTextContents();
    expect(sources).toHaveLength(count);
    expect(new Set(sources).size, "diagram sources are distinct").toBe(count);
    const [lightRole] = await computedRoles(page, ["--pb-diagram-node-bg"]);
    const [expectedLight, ...lightFills] = await normalizeColors(page, [lightRole, ...await diagramFills(page, scope)]);
    lightFills.forEach((color, index) => {
      expect.soft(difference(color, expectedLight), `light fill ${index + 1} matches its role`).toBeLessThanOrEqual(colorTolerance);
    });
    const initial = await readDiagramLog(page, true);
    expect.soft(initial.insertions.length, "initial insertions are observed").toBeGreaterThanOrEqual(count);
    expect.soft(initial.insertions.every((entry) => entry.theme === "light"), "initial insertions are light").toBe(true);

    await page.locator("[data-pb-theme-toggle]").click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await expect.soft(page.locator(`${scope} [data-pb-mermaid-theme="dark"]`), "all diagrams become dark").toHaveCount(count);
    await expect.soft(page.locator(`${scope} [data-pb-mermaid] svg`), "all diagrams retain one SVG").toHaveCount(count);
    const [darkRole] = await computedRoles(page, ["--pb-diagram-node-bg"]);
    const [expectedDark, ...darkFills] = await normalizeColors(page, [darkRole, ...await diagramFills(page, scope)]);
    expect(difference(expectedLight, expectedDark), "mode fill roles differ").toBeGreaterThan(0.02);
    darkFills.forEach((color, index) => {
      expect.soft(difference(color, expectedDark), `dark fill ${index + 1} matches its role`).toBeLessThanOrEqual(colorTolerance);
    });
    const afterToggle = await readDiagramLog(page);
    expect.soft(afterToggle.insertions, "one post-toggle insertion per diagram").toHaveLength(count);
    for (const source of sources) {
      const inserted = afterToggle.insertions.filter((entry) => entry.source === source);
      expect.soft(inserted, `one post-toggle insertion for ${source}`).toHaveLength(1);
      expect.soft(inserted.map((entry) => entry.svgCount), `one inserted SVG for ${source}`).toEqual([1]);
      expect.soft(inserted.map((entry) => entry.theme), `inserted mode for ${source}`).toEqual(["dark"]);
    }
    const firstDark = afterToggle.insertions.findIndex((entry) => entry.theme === "dark");
    expect.soft(firstDark, "a dark insertion is observed").toBeGreaterThanOrEqual(0);
    expect.soft(afterToggle.insertions.slice(firstDark + 1).filter((entry) => entry.theme === "light"), "no light insertion after dark").toEqual([]);
    expect.soft(afterToggle.probeCount, "no sampling probe").toBe(0);
    expect.soft(afterToggle.scratchCount, "no rendering scratch").toBe(0);
    expect.soft(afterToggle.violations, "no CSP violations").toEqual([]);
    writeFileSync(testInfo.outputPath("mode-insertions.json"), JSON.stringify({
      surface, sources, lightRole, expectedLight, lightFills, darkRole, expectedDark, darkFills, initial, afterToggle,
    }, null, 2) + "\n");
  });
}
