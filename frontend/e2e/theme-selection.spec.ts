import { existsSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import type { APIRequestContext, TestInfo } from "@playwright/test";
import { parse, type Color } from "../src/__tests__/contrastResolver";
import {
  assemble, builtinCss, builtinSlots, fixtureDefinitions, fourInputDefinition, genericCss, primitiveNames,
  readFrozen, selectors, tokenValue, type FixtureMode, type Mode,
} from "../src/__tests__/themeCandidates";
import { gotoAndWaitForSearchReady, gotoExpectStatus } from "./helpers";
import { expect, test, type Page } from "./smoke-fixtures";
import type { SmokeServer } from "./smoke-server";
import { normalizeColors } from "./theme-colors";
import { themeMarkdown } from "./themePages";

type Readings = Array<Record<string, unknown>>;
type Violation = { violatedDirective: string; blockedURI: string };
type BodyBaseline = {
  modes: Record<Mode, { sections: { body: { body: { elements: Array<Record<string, Color>> } } } }>;
};

const frozen = readFrozen();
const baseline = JSON.parse(readFileSync("e2e/fixtures/theme-baseline.json", "utf8")) as BodyBaseline;
const { probes } = fixtureDefinitions();
const modes: Mode[] = ["light", "dark"];
const lightFile = { "opaque-light.css": fourInputDefinition("opaque-light") };
const darkFile = { "opaque-dark.css": fourInputDefinition("opaque-dark") };

function distance(left: Color, right: Color): number {
  return Math.max(...left.map((value, index) => Math.abs(value - right[index])));
}

async function retain(testInfo: TestInfo, readings: Readings) {
  const output = testInfo.outputPath("selection-readings.json");
  writeFileSync(output, JSON.stringify(readings, null, 2) + "\n");
  await testInfo.attach("selection-readings", { path: output, contentType: "application/json" });
}

async function openDocument(page: Page, request: APIRequestContext, server: SmokeServer, mode: Mode) {
  writeFileSync(path.join(server.contentDir, "theme-baseline.md"), themeMarkdown);
  await expect.poll(async () => (await request.get("/api/v1/pages/by-path/docs/theme-baseline")).status()).toBe(200);
  await page.emulateMedia({ colorScheme: mode });
  await gotoAndWaitForSearchReady(page, "/docs/theme-baseline");
  if (mode === "dark") await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  else await expect(page.locator("html")).not.toHaveAttribute("data-theme");
}

async function readColor(page: Page, selector: string, property: string): Promise<Color> {
  const target = page.locator(selector).first();
  await expect(target).toBeVisible();
  const value = await target.evaluate((element, property) => getComputedStyle(element).getPropertyValue(property), property);
  return (await normalizeColors(page, [value]))[0];
}

async function customColor(page: Page, mode: Mode, selector: string, property: string, expected: Color, role: string, readings: Readings) {
  const builtin = tokenValue(assemble(mode, builtinSlots()), role) as Color;
  const actual = await readColor(page, selector, property);
  const separation = distance(expected, builtin);
  const difference = distance(actual, expected);
  const observable = `${mode} ${selector} ${property}`;
  readings.push({ observable, role, actual, expected, builtin, separation, difference });
  expect.soft(separation, `flip ${observable}`).toBeGreaterThanOrEqual(0.02);
  expect.soft(difference, `effect ${observable}`).toBeLessThanOrEqual(0.002);
}

async function customAppearance(page: Page, mode: Mode, fixture: FixtureMode, readings: Readings) {
  const roles = frozen.fixtures[fixture].roles;
  for (const [selector, property, role] of [
    ["body", "background-color", "--pb-surface"],
    ["body", "color", "--pb-text"],
    ["[data-pb-header]", "background-color", "--pb-surface-chrome"],
    [".pb-prose pre", "border-top-color", "--pb-code-border"],
  ]) await customColor(page, mode, selector, property, roles[role] as Color, role, readings);
  await gotoExpectStatus(page, "/new");
  await customColor(page, mode, ".pb-new-dialog", "background-color", roles["--pb-surface-raised"] as Color, "--pb-surface-raised", readings);
}

async function baselineBody(page: Page, mode: Mode, readings: Readings) {
  const body = baseline.modes[mode].sections.body.body.elements[0];
  for (const property of ["background-color", "color"]) {
    const actual = await readColor(page, "body", property);
    const expected = body[property];
    const difference = distance(actual, expected);
    readings.push({ observable: `${mode} body ${property}`, actual, expected, difference });
    expect.soft(difference, `baseline ${mode} body ${property}`).toBeLessThanOrEqual(0.002);
  }
}

test("color probes remain distinguishable", async ({}, testInfo) => {
  const colors = Object.entries(probes).filter(([, probe]) => probe.kind === "colour");
  const readings: Readings = [];
  for (let index = 0; index < colors.length; index++) for (const [right, probe] of colors.slice(index + 1)) {
    const [left, other] = colors[index];
    const separation = distance(parse(other.value), parse(probe.value));
    readings.push({ left, right, separation });
    expect.soft(separation, `flip ${left} and ${right}`).toBeGreaterThanOrEqual(0.02);
  }
  await retain(testInfo, readings);
});

test("default stylesheet endpoints preserve canonical bytes", async ({ request }, testInfo) => {
  const readings: Readings = [];
  for (const [url, source] of [
    ["/_plainbase/theme-light.css", builtinCss("light")],
    ["/_plainbase/theme-dark.css", builtinCss("dark")],
    ["/plainbase-theme-generic.css", genericCss()],
  ]) {
    const response = await request.get(url);
    const actual = await response.body();
    const expected = Buffer.from(source);
    readings.push({ url, status: response.status(), actualBytes: actual.length, expectedBytes: expected.length, equal: actual.equals(expected) });
    expect.soft(response.status(), url).toBe(200);
    expect.soft(actual, url).toEqual(expected);
  }
  await retain(testInfo, readings);
});

test.describe("a selected light definition", () => {
  test.use({ smokeData: { files: lightFile, theme: { light: "opaque-light.css" } } });

  test("theme-only configuration keeps the content root available", async ({ page, request, smokeServer }, testInfo) => {
    const configuration = readFileSync(path.join(smokeServer.dataDir, "plainbase.conf"), "utf8");
    expect.soft(configuration).toBe('theme {\n  light = "opaque-light.css"\n}\n');
    const health = await request.get("/healthz");
    expect.soft(health.status()).toBe(200);
    await openDocument(page, request, smokeServer, "light");
    await smokeServer.stop();
    const log = readFileSync(testInfo.outputPath("server.log"), "utf8");
    expect.soft(log, "known boot line").toContain("Application started in");
    expect.soft(log, "no serve refusal").not.toMatch(/serve:/);
    await retain(testInfo, [{ configuration, healthStatus: health.status(), knownBootLine: log.includes("Application started in"), serveRefusal: /serve:/.test(log) }]);
  });

  test("light uses the selected definition", async ({ page, request, smokeServer }, testInfo) => {
    const readings: Readings = [];
    await openDocument(page, request, smokeServer, "light");
    await customAppearance(page, "light", "opaque-light", readings);
    await retain(testInfo, readings);
  });

  test("dark retains the built-in appearance", async ({ page, request, smokeServer }, testInfo) => {
    const readings: Readings = [];
    await openDocument(page, request, smokeServer, "dark");
    await baselineBody(page, "dark", readings);
    await retain(testInfo, readings);
  });
});

test.describe("selected file contents and startup selection", () => {
  test.use({ smokeData: { files: lightFile, theme: { light: "opaque-light.css" } } });

  test("a selection is fixed until restart while its file is reread on reload", async ({ page, request, smokeServer }, testInfo) => {
    const readings: Readings = [];
    await openDocument(page, request, smokeServer, "light");
    const initial = await readColor(page, "body", "background-color");
    const original = parse(fixtureDefinitions()["opaque-light"]["--pb-theme-background"]);
    const expected = parse(fixtureDefinitions()["opaque-dark"]["--pb-theme-background"]);
    const builtin = tokenValue(assemble("light", builtinSlots()), "--pb-surface") as Color;
    readings.push({ observable: "initial selected body background", actual: initial, expected: original, difference: distance(initial, original) });
    expect.soft(distance(initial, original), "initial selected body background").toBeLessThanOrEqual(0.002);
    for (const [observable, other] of [["edited background differs from initial", initial], ["edited background differs from bundled light", builtin]] as const) {
      const separation = distance(expected, other);
      readings.push({ observable, expected, other, separation });
      expect.soft(separation, observable).toBeGreaterThanOrEqual(0.02);
    }
    writeFileSync(path.join(smokeServer.dataDir, "opaque-light.css"), fourInputDefinition("opaque-dark").replace(selectors.dark, selectors.light));
    await page.reload();
    const edited = await readColor(page, "body", "background-color");
    const editedDifference = distance(edited, expected);
    readings.push({ observable: "selected file edit after reload", actual: edited, expected, difference: editedDifference });
    expect.soft(editedDifference, "selected file edit after reload").toBeLessThanOrEqual(0.002);

    const configurationPath = path.join(smokeServer.dataDir, "plainbase.conf");
    const before = readFileSync(configurationPath, "utf8");
    const blocks = before.match(/^theme \{\n  light = "opaque-light\.css"\n\}\n$/gm) ?? [];
    expect(blocks, "exact appended theme block").toHaveLength(1);
    const after = before.replace(blocks[0]!, "");
    writeFileSync(configurationPath, after);
    const stored = readFileSync(configurationPath, "utf8");
    expect.soft(stored, "configuration rewrite is stored").toBe(after);
    expect.soft(stored, "configuration selection removed").not.toContain("theme");
    expect.soft(stored, "configuration rewrite changes the file").not.toBe(before);
    readings.push({ observable: "configuration rewrite", before, after, stored, changed: stored !== before });
    await page.reload();
    const persisted = await readColor(page, "body", "background-color");
    const persistedDifference = distance(persisted, edited);
    readings.push({ observable: "startup selection after configuration rewrite", actual: persisted, expected: edited, difference: persistedDifference });
    expect.soft(persistedDifference, "startup selection after configuration rewrite").toBeLessThanOrEqual(0.002);
    await retain(testInfo, readings);
  });
});

test.describe("light scoped override order", () => {
  test.use({ smokeData: {
    files: { ...lightFile, "custom.css": `${selectors.light} { --pb-theme-primary: ${probes["--pb-theme-primary"].value}; }` },
    theme: { light: "opaque-light.css" },
  } });

  test("a light-only override with the light scope selector wins by order", async ({ page, request, smokeServer }, testInfo) => {
    const readings: Readings = [];
    const probe = parse(probes["--pb-theme-primary"].value);
    const selected = parse(fixtureDefinitions()["opaque-light"]["--pb-theme-primary"]);
    const builtinDark = tokenValue(assemble("dark", builtinSlots()), "--pb-primary-bg") as Color;
    for (const [observable, other] of [["probe differs from selected light primary", selected], ["probe differs from bundled dark primary", builtinDark]] as const) {
      const separation = distance(probe, other);
      readings.push({ observable, probe, other, separation });
      expect.soft(separation, observable).toBeGreaterThanOrEqual(0.02);
    }
    for (const mode of modes) {
      await openDocument(page, request, smokeServer, mode);
      await gotoExpectStatus(page, "/new");
      await page.locator("[data-pb-new-title]").fill("Scoped theme page");
      await expect(page.locator("[data-pb-new-create]")).toBeEnabled();
      const actual = await readColor(page, "[data-pb-new-create]", "background-color");
      const expected = mode === "light" ? probe : builtinDark;
      const difference = distance(actual, expected);
      const observable = `${mode} scoped primary action`;
      readings.push({ observable, actual, expected, difference });
      expect.soft(difference, observable).toBeLessThanOrEqual(0.002);
    }
    await retain(testInfo, readings);
  });
});

test.describe("theme stylesheet security", () => {
  test.use({ smokeData: { files: { "custom.css": [
    '@import url("https://example.invalid/pb-remote.css");',
    '@font-face { font-family: "PbRemoteSans"; src: url("https://example.invalid/pb-remote.woff2"); }',
    '@font-face { font-family: "PbDataMono"; src: url("data:font/woff2;base64,AAAA"); }',
    ':root { --pb-theme-font-sans: "PbRemoteSans", sans-serif; --pb-theme-font-mono: "PbDataMono", monospace; }',
    '[data-pb-header] { background-image: url("https://example.invalid/pb-remote.png"); }',
  ].join("\n") } } });

  test("theme CSS obeys the shell CSP", async ({ page, request, smokeServer }, testInfo) => {
    const readings: Readings = [];
    await page.addInitScript(() => {
      const sink: Violation[] = [];
      (window as unknown as { __cspViolations: Violation[] }).__cspViolations = sink;
      window.addEventListener("securitypolicyviolation", (event) => {
        sink.push({ violatedDirective: event.violatedDirective, blockedURI: event.blockedURI });
      });
    });
    const requests: string[] = [];
    page.on("request", (request) => requests.push(request.url()));
    await openDocument(page, request, smokeServer, "light");
    await expect(page.locator(".pb-prose pre code").first()).toBeVisible();
    await page.evaluate(async () => { await document.fonts.ready; });
    const violations = () => page.evaluate(() => (window as unknown as { __cspViolations: Violation[] }).__cspViolations);
    const classes = [
      ["remote stylesheet violation", "style-src", "remote"],
      ["remote font violation", "font-src", "remote"],
      ["data font violation", "font-src", "data"],
    ] as const;
    const count = (events: Violation[], directive: string, source: string) => events.filter((event) =>
      event.violatedDirective.startsWith(directive) && (source === "data"
        ? event.blockedURI.startsWith("data") : /^https:\/\/example\.invalid(?:\/|$)/.test(event.blockedURI))).length;
    for (const [observable, directive, source] of classes) {
      await expect.soft.poll(async () => count(await violations(), directive, source), { message: observable }).toBeGreaterThanOrEqual(1);
    }
    const backgroundImage = await page.locator("[data-pb-header]").evaluate((element) => getComputedStyle(element).backgroundImage);
    expect.soft(backgroundImage, "remote image is applied").toContain("pb-remote.png");
    await expect.soft.poll(() => requests.filter((url) => url === "https://example.invalid/pb-remote.png").length,
      { message: "remote image request is observed" }).toBeGreaterThanOrEqual(1);
    const observed = await violations();
    const imageViolations = observed.filter((event) => event.violatedDirective.startsWith("img-src"));
    expect.soft(imageViolations, "remote image is allowed by CSP").toEqual([]);
    readings.push({ observable: "theme CSS CSP", violations: observed,
      violationCounts: Object.fromEntries(classes.map(([name, directive, source]) => [name, count(observed, directive, source)])),
      backgroundImage, requests, imageRequests: requests.filter((url) => url === "https://example.invalid/pb-remote.png").length,
      imageViolations, fontReady: true, codeBlocks: await page.locator(".pb-prose pre code").count() });
    await retain(testInfo, readings);
  });
});

test.describe("a selected dark definition", () => {
  test.use({ smokeData: { files: darkFile, theme: { dark: "opaque-dark.css" } } });

  test("dark uses the selected definition", async ({ page, request, smokeServer }, testInfo) => {
    const readings: Readings = [];
    await openDocument(page, request, smokeServer, "dark");
    await customAppearance(page, "dark", "opaque-dark", readings);
    await retain(testInfo, readings);
  });

  test("light retains the built-in appearance", async ({ page, request, smokeServer }, testInfo) => {
    const readings: Readings = [];
    await openDocument(page, request, smokeServer, "light");
    await baselineBody(page, "light", readings);
    await retain(testInfo, readings);
  });
});

test.describe("two selected definitions", () => {
  test.use({ smokeData: {
    files: { ...lightFile, "translucent-dark.css": fourInputDefinition("translucent-dark") },
    theme: { light: "opaque-light.css", dark: "translucent-dark.css" },
  } });

  for (const mode of modes) {
    test(`${mode} uses its selected definition`, async ({ page, request, smokeServer }, testInfo) => {
      const readings: Readings = [];
      await openDocument(page, request, smokeServer, mode);
      await customAppearance(page, mode, mode === "light" ? "opaque-light" : "translucent-dark", readings);
      await retain(testInfo, readings);
    });

    test(`${mode} exposes no built-in primitives`, async ({ page, request, smokeServer }, testInfo) => {
      await openDocument(page, request, smokeServer, mode);
      const values = await page.locator("html").evaluate((element, names) => {
        const style = getComputedStyle(element);
        return names.map((name) => ({ name, value: style.getPropertyValue(name).trim() }));
      }, primitiveNames());
      for (const { name, value } of values) expect.soft(value, `primitive ${mode} ${name}`).toBe("");
      await retain(testInfo, values);
    });
  }
});

test.describe("invalid selection at startup", () => {
  test.use({ smokeData: { files: darkFile, theme: { light: "pb-rejected-probe.txt", dark: "opaque-dark.css" } } });

  test("an invalid selection warns at startup and serves the bundled definition", async ({ page, request, smokeServer }, testInfo) => {
    const readings: Readings = [];
    await openDocument(page, request, smokeServer, "light");
    await baselineBody(page, "light", readings);
    await openDocument(page, request, smokeServer, "dark");
    const expected = parse(fixtureDefinitions()["opaque-dark"]["--pb-theme-background"]);
    await customColor(page, "dark", "body", "background-color", expected, "--pb-surface", readings);
    await smokeServer.stop();
    const log = readFileSync(testInfo.outputPath("server.log"), "utf8");
    const rejection = /\bWARN\b\s+\S+ - theme(?:\.(?:light|dark))? /;
    const rejections = log.split("\n").filter((line) => rejection.test(line));
    expect.soft(rejections, "one rejected selection warning").toHaveLength(1);
    for (const line of rejections) {
      expect.soft(line, "warning names the setting").toContain("theme.light");
      expect.soft(line, "warning names the fallback").toContain("serving the bundled light theme");
    }
    expect.soft(log, "submitted value is not logged").not.toContain("pb-rejected-probe");
    expect.soft(log, "known boot line").toContain("Application started in");
    readings.push({ observable: "invalid selection boot warning", rejections,
      submittedValueLogged: log.includes("pb-rejected-probe"), knownBootLine: log.includes("Application started in") });
    await retain(testInfo, readings);
  });
});

test.describe("unreadable selected definitions", () => {
  test.use({ smokeData: {
    files: { "bad-dark.css": Buffer.from([0xff]) },
    theme: { light: "missing-light.css", dark: "bad-dark.css" },
  } });

  for (const mode of modes) test(`${mode} falls back to the built-in appearance`, async ({ page, request, smokeServer }, testInfo) => {
    const readings: Readings = [];
    await openDocument(page, request, smokeServer, mode);
    await baselineBody(page, mode, readings);
    await smokeServer.stop();
    const log = readFileSync(testInfo.outputPath("server.log"), "utf8");
    const rejection = /\bWARN\b\s+\S+ - theme(?:\.(?:light|dark))? /;
    const rejections = log.split("\n").filter((line) => rejection.test(line));
    const warnings = log.split("\n").filter((line) => line.includes("Theme stylesheet"));
    // LocalThemeStylesheets rate-limits to one warn per window, so either read failure can be the observed warning.
    const readFailure = warnings.find((line) => /Theme stylesheet (?:theme\.light missing-light\.css MISSING|theme\.dark bad-dark\.css INVALID_UTF8)\b/.test(line));
    const synthetic = "00:00:00.000 WARN  c.p.Application - theme.light is not a DATA_DIR stylesheet name; serving the bundled light theme";
    const shape = { synthetic, syntheticMatches: rejection.test(synthetic), readFailure, readFailureMatches: rejection.test(readFailure ?? "") };
    expect.soft(log, "known boot line").toContain("Application started in");
    expect.soft(rejections, "theme keys are accepted").toEqual([]);
    expect.soft(readFailure, "selected stylesheet read failure").toBeDefined();
    expect.soft(readFailure ?? "", "observed warning uses the logger separator").toMatch(/\bWARN\b\s+\S+ - /);
    expect.soft(shape.syntheticMatches, "configuration rejection shape").toBe(true);
    expect.soft(shape.readFailureMatches, "read failure is not a configuration rejection").toBe(false);
    readings.push({ knownBootLine: log.includes("Application started in"), rejections, warnings, shape });
    await retain(testInfo, readings);
  });
});

test.describe("partial overrides with a selected light definition", () => {
  test.use({ smokeData: {
    files: { ...lightFile, "custom.css": [
      `:root { --pb-theme-font-sans: ${probes["--pb-theme-font-sans"].value}; }`,
      `:root:not([data-theme="dark"]) { --pb-theme-primary: ${probes["--pb-theme-primary"].value}; }`,
      `:where(:root[data-theme="dark"]) { --pb-theme-primary: ${probes["--pb-theme-ring"].value}; }`,
    ].join("\n") },
    theme: { light: "opaque-light.css" },
  } });

  for (const mode of modes) {
    test(`${mode} keeps the override font`, async ({ page, request, smokeServer }, testInfo) => {
      await openDocument(page, request, smokeServer, mode);
      const actual = await page.locator("html").evaluate((element) => getComputedStyle(element).getPropertyValue("--pb-font-sans").trim());
      const expected = probes["--pb-theme-font-sans"].value;
      const builtin = tokenValue(assemble(mode, builtinSlots()), "--pb-theme-font-sans");
      expect.soft(expected, `flip ${mode} font`).not.toBe(builtin);
      expect.soft(actual, `effect ${mode} font`).toBe(expected);
      await retain(testInfo, [{ observable: `${mode} root font`, actual, expected, builtin, separated: expected !== builtin }]);
    });

    test(`${mode} keeps the override primary action`, async ({ page, request, smokeServer }, testInfo) => {
      const readings: Readings = [];
      await openDocument(page, request, smokeServer, mode);
      await gotoExpectStatus(page, "/new");
      await page.locator("[data-pb-new-title]").fill("Selected theme page");
      await expect(page.locator("[data-pb-new-create]")).toBeEnabled();
      const probe = mode === "light" ? "--pb-theme-primary" : "--pb-theme-ring";
      await customColor(page, mode, "[data-pb-new-create]", "background-color", parse(probes[probe].value), "--pb-primary-bg", readings);
      await retain(testInfo, readings);
    });
  }
});

type Consumer = {
  input: string;
  selector: string;
  property: string;
  view?: "search" | "dialog";
  pseudo?: string;
  factor?: number;
};
type ConsumerValue = Color | string;

const consumerRows: Array<{ name: string; consumers: Consumer[] }> = [
  { name: "color inputs reach page and action consumers", consumers: [
    { input: "--pb-theme-background", selector: "body", property: "background-color" },
    { input: "--pb-theme-foreground", selector: "body", property: "color" },
    { input: "--pb-theme-muted", selector: "[data-pb-header]", property: "background-color" },
    { input: "--pb-theme-muted-foreground", selector: ".pb-rail-head", property: "color" },
    { input: "--pb-theme-border", selector: ".pb-prose pre", property: "border-top-color" },
    { input: "--pb-theme-ring", selector: ":focus-visible", property: "outline-color" },
    { input: "--pb-theme-selection", selector: ".pb-prose p", property: "background-color", pseudo: "::selection" },
    { input: "--pb-theme-selection-foreground", selector: ".pb-prose p", property: "color", pseudo: "::selection" },
    { input: "--pb-theme-destructive", selector: '[data-pb-callout="caution"]', property: "border-left-color" },
    { input: "--pb-theme-warning", selector: '[data-pb-callout="warning"]', property: "border-left-color" },
    { input: "--pb-theme-success", selector: '.pb-chip[data-pb-chip-status="active"]', property: "color" },
    { input: "--pb-theme-syntax-keyword", selector: ".pb-prose .hljs-keyword", property: "color" },
    { input: "--pb-theme-syntax-string", selector: ".pb-prose .hljs-string", property: "color" },
    { input: "--pb-theme-syntax-comment", selector: ".pb-prose .hljs-comment", property: "color" },
    { input: "--pb-theme-syntax-literal", selector: ".pb-prose .hljs-number", property: "color" },
    { input: "--pb-theme-syntax-title", selector: ".pb-prose .hljs-title", property: "color" },
    { input: "--pb-theme-syntax-attribute", selector: ".pb-prose .hljs-attr", property: "color" },
    { input: "--pb-theme-primary", selector: "[data-pb-new-create]", property: "background-color", view: "dialog" },
    { input: "--pb-theme-primary-foreground", selector: "[data-pb-new-create]", property: "color", view: "dialog" },
    { input: "--pb-theme-card", selector: ".pb-new-dialog", property: "background-color", view: "dialog" },
  ] },
  { name: "card ink reaches the dialog", consumers: [
    { input: "--pb-theme-card-foreground", selector: ".pb-new-dialog", property: "color", view: "dialog" },
  ] },
  { name: "font inputs reach body and code", consumers: [
    { input: "--pb-theme-font-sans", selector: "body", property: "font-family" },
    { input: "--pb-theme-font-mono", selector: ".pb-prose pre code", property: "font-family" },
  ] },
  { name: "radius reaches decorated consumers", consumers: [
    { input: "--pb-theme-radius", selector: "[data-pb-theme-toggle]", property: "border-radius", factor: 1 },
    { input: "--pb-theme-radius", selector: ".pb-prose :not(pre) > code", property: "border-radius", factor: 1 },
    { input: "--pb-theme-radius", selector: "[data-pb-search-trigger]", property: "border-radius", factor: 1.5 },
    { input: "--pb-theme-radius", selector: "[data-pb-search-panel]", property: "border-radius", factor: 3, view: "search" },
    { input: "--pb-theme-radius", selector: ".pb-new-dialog", property: "border-radius", factor: 4, view: "dialog" },
  ] },
  { name: "card ink reaches diagram nodes", consumers: [
    { input: "--pb-theme-card-foreground", selector: ".pb-prose [data-pb-mermaid] svg .node text", property: "fill" },
  ] },
];

async function readConsumers(page: Page, consumers: Consumer[]): Promise<ConsumerValue[]> {
  await gotoAndWaitForSearchReady(page, "/docs/theme-baseline");
  await page.evaluate(() => document.fonts.ready);
  await page.mouse.move(0, 0);
  let view = "document";
  const values: ConsumerValue[] = [];
  for (const consumer of consumers) {
    if (consumer.view === "dialog" && view !== "dialog") {
      await gotoExpectStatus(page, "/new");
      await page.locator("[data-pb-new-title]").fill("Complete theme page");
      await expect(page.locator("[data-pb-new-create]")).toBeEnabled();
      view = "dialog";
    } else if (consumer.view === "search" && view !== "search") {
      await page.keyboard.press("ControlOrMeta+k");
      await expect(page.locator("[data-pb-search-input]")).toBeFocused();
      view = "search";
    }
    if (consumer.input === "--pb-theme-ring") await page.keyboard.press("Tab");
    const target = page.locator(consumer.selector).first();
    await expect(target).toBeVisible();
    if (consumer.pseudo) {
      await target.evaluate((element) => {
        const range = document.createRange();
        range.selectNodeContents(element);
        const selection = window.getSelection()!;
        selection.removeAllRanges();
        selection.addRange(range);
      });
      expect(await page.evaluate(() => window.getSelection()?.toString().length)).toBeGreaterThan(0);
    }
    const value = await target.evaluate((element, { property, pseudo }) =>
      getComputedStyle(element, pseudo).getPropertyValue(property), consumer);
    values.push(probes[consumer.input].kind === "colour" ? (await normalizeColors(page, [value]))[0] : value);
  }
  return values;
}

async function probeValue(page: Page, consumer: Consumer): Promise<ConsumerValue> {
  const probe = probes[consumer.input];
  if (probe.kind === "colour") return parse(probe.value);
  if (probe.kind === "font") return page.evaluate((value) => {
    const style = document.createElement("span").style;
    style.fontFamily = value;
    return style.fontFamily;
  }, probe.value);
  expect(probe.value).toMatch(/^\d+(?:\.\d+)?px$/);
  return `${Number.parseFloat(probe.value) * consumer.factor!}px`;
}

for (const scenario of [
  { name: "complete overrides with built-in definitions", data: {} },
  { name: "complete overrides with selected definitions", data: {
    files: { ...lightFile, ...darkFile }, theme: { light: "opaque-light.css", dark: "opaque-dark.css" },
  } },
]) test.describe(scenario.name, () => {
  test.use({ smokeData: scenario.data });

  for (const mode of modes) for (const row of consumerRows) test(`${mode} ${row.name}`, async ({ page, request, smokeServer }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 1100 });
    const override = path.join(smokeServer.dataDir, "custom.css");
    expect(existsSync(override), "override absent before unprobed readings").toBe(false);
    await openDocument(page, request, smokeServer, mode);
    const before = await readConsumers(page, row.consumers);
    expect(Object.keys(probes), "complete public input probes").toHaveLength(24);
    writeFileSync(override, `:root {\n${Object.entries(probes).map(([name, probe]) => `  ${name}: ${probe.value};`).join("\n")}\n}\n`);
    await page.reload();
    const after = await readConsumers(page, row.consumers);
    const readings: Readings = [];
    for (const [index, consumer] of row.consumers.entries()) {
      const input = consumer.input;
      const expected = await probeValue(page, consumer);
      const unprobed = before[index];
      const actual = after[index];
      const observable = `${mode} ${consumer.selector}${consumer.pseudo ?? ""} ${consumer.property}`;
      const separation = Array.isArray(expected) ? distance(unprobed as Color, expected) : undefined;
      const difference = Array.isArray(expected) ? distance(actual as Color, expected) : undefined;
      const flip = separation === undefined ? unprobed !== expected : separation >= 0.02;
      const matches = difference === undefined ? actual === expected : difference <= 0.002;
      readings.push({ ...consumer, input, observable, kind: probes[input].kind, unprobed, actual, expected, separation, difference, flip, matches });
      if (separation === undefined) expect.soft(unprobed, `flip ${observable}`).not.toBe(expected);
      else expect.soft(separation, `flip ${observable}`).toBeGreaterThanOrEqual(0.02);
      if (difference === undefined) expect.soft(actual, `effect ${observable}`).toBe(expected);
      else expect.soft(difference, `effect ${observable}`).toBeLessThanOrEqual(0.002);
    }
    await retain(testInfo, readings);
  });
});

async function openCardDiscussion(page: Page, request: APIRequestContext, server: SmokeServer, testInfo: TestInfo) {
  const id = "019a5034-82d1-72ae-a5ca-9cb56f1d6d23";
  const name = "card-pair";
  writeFileSync(path.join(server.contentDir, `${name}.md`), [
    "---", `id: ${id}`, "title: Card discussion", "---", "", "Discuss the card and page ink pairs.", "",
  ].join("\n"));
  const htmlURL = `/api/v1/pages/${id}/html?root=docs`;
  await expect.poll(async () => (await request.get(htmlURL)).status()).toBe(200);
  const html = await request.get(htmlURL);
  const source = await html.json();
  const created = await request.post(`/api/v1/pages/${id}/discussions?root=docs`, { data: {
    anchor: { kind: "page", content_hash: source.content_hash }, body: "Check the paired discussion ink.",
  } });
  const createdBody = await created.text();
  await testInfo.attach("discussion-seed", { body: JSON.stringify({
    pageId: id, htmlStatus: html.status(), createdStatus: created.status(), createdBody,
  }, null, 2), contentType: "application/json" });
  expect(created.status(), "page discussion seed accepted").toBe(201);
  const discussionId = (JSON.parse(createdBody) as { id: string }).id;
  await page.emulateMedia({ colorScheme: "light" });
  await page.setViewportSize({ width: 1440, height: 1050 });
  await gotoAndWaitForSearchReady(page, `/docs/${name}`);
  await page.mouse.move(0, 0);
  const card = page.locator(".pb-discussion-list-card .pb-discussion-card-link");
  try {
    await expect(card, "seeded margin card renders under open access").toBeVisible({ timeout: 25_000 });
    await expect(card).toHaveCount(1);
    await expect(card).toHaveAttribute("data-pb-discussion-id", discussionId);
    await expect(page.locator("html")).not.toHaveAttribute("data-theme");
  } finally {
    await testInfo.attach("discussion-margin", { body: JSON.stringify({
      cardCount: await card.count(), cardRendered: await card.first().isVisible(),
      theme: await page.locator("html").getAttribute("data-theme"), viewport: page.viewportSize(), discussionId,
    }, null, 2), contentType: "application/json" });
  }
}

function cardInkProbes(readings: Readings) {
  const pageInk = parse(probes["--pb-theme-foreground"].value);
  const cardInk = parse(probes["--pb-theme-card-foreground"].value);
  const builtin = tokenValue(assemble("light", builtinSlots()), "--pb-text") as Color;
  for (const [observable, left, right] of [
    ["page ink differs from built-in text", pageInk, builtin],
    ["card ink differs from built-in text", cardInk, builtin],
    ["page and card ink differ", pageInk, cardInk],
  ] as Array<[string, Color, Color]>) {
    const separation = distance(left, right);
    readings.push({ kind: "probe-flip", observable, left, right, separation });
    expect.soft(separation, `flip ${observable}`).toBeGreaterThanOrEqual(0.02);
  }
  return { pageInk, cardInk };
}

async function pairedInk(page: Page, selector: string, expected: Color, state: string, readings: Readings) {
  const actual = await readColor(page, selector, "color");
  const difference = distance(actual, expected);
  const observable = `${state} ${selector} color`;
  readings.push({ kind: "effect", observable, state, selector, actual, expected, difference, matches: difference <= 0.002 });
  expect.soft(difference, `effect ${observable}`).toBeLessThanOrEqual(0.002);
}

test.describe("card and page ink across states", () => {
  test.use({ smokeData: { files: { "custom.css": `:root {\n${[
    "--pb-theme-foreground", "--pb-theme-card-foreground",
  ].map((name) => `  ${name}: ${probes[name].value};`).join("\n")}\n}\n` } } });

  test.beforeEach(async ({ page, request, smokeServer }, testInfo) => {
    await openCardDiscussion(page, request, smokeServer, testInfo);
  });

  test("card-derived fills use their paired ink at rest", async ({ page }, testInfo) => {
    const readings: Readings = [];
    const { pageInk, cardInk } = cardInkProbes(readings);
    await pairedInk(page, "body", pageInk, "rest", readings);
    for (const selector of [".pb-discussion-list-card .pb-discussion-card-quote", ".pb-discussion-list-card .pb-discussion-author"]) {
      await pairedInk(page, selector, cardInk, "rest", readings);
    }
    await gotoExpectStatus(page, "/new");
    for (const selector of [".pb-new-dialog", "#pb-new-heading", "[data-pb-new-folder]"]) {
      await pairedInk(page, selector, cardInk, "rest", readings);
    }
    await retain(testInfo, readings);
  });

  test("page-derived states keep page ink", async ({ page }, testInfo) => {
    const readings: Readings = [];
    const { pageInk } = cardInkProbes(readings);
    await pairedInk(page, "body", pageInk, "rest", readings);
    const listFill = await readColor(page, ".pb-discussion-list-card", "background-color");
    await page.locator(".pb-discussion-list-card .pb-discussion-card-link").hover();
    const hoverFill = await readColor(page, ".pb-discussion-list-card .pb-discussion-card-link", "background-color");
    const hoverSeparation = distance(hoverFill, listFill);
    readings.push({ kind: "fill-separation", observable: "hover fill differs from the list card", left: hoverFill, right: listFill, separation: hoverSeparation });
    expect.soft(hoverSeparation, "hover fill differs from the list card").toBeGreaterThanOrEqual(0.02);
    for (const selector of [".pb-discussion-list-card .pb-discussion-card-quote", ".pb-discussion-list-card .pb-discussion-author"]) {
      await pairedInk(page, selector, pageInk, "hovered", readings);
    }
    await gotoExpectStatus(page, "/new");
    await expect(page.locator(".pb-template-card input:checked")).toHaveCount(1);
    const dialogFill = await readColor(page, ".pb-new-dialog", "background-color");
    const checkedFill = await readColor(page, ".pb-template-card:has(:checked)", "background-color");
    const checkedSeparation = distance(checkedFill, dialogFill);
    readings.push({ kind: "fill-separation", observable: "checked fill differs from the dialog", left: checkedFill, right: dialogFill, separation: checkedSeparation });
    expect.soft(checkedSeparation, "checked fill differs from the dialog").toBeGreaterThanOrEqual(0.02);
    await pairedInk(page, ".pb-template-card:has(:checked)", pageInk, "checked", readings);
    await retain(testInfo, readings);
  });
});
