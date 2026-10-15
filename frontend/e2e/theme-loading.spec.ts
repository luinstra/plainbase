import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import type { APIRequestContext } from "@playwright/test";
import { expect, test, type Page, type Route } from "./smoke-fixtures";
import { gotoAndWaitForSearchReady } from "./helpers";
import { normalizeColors } from "./theme-colors";

type Violation = { violatedDirective: string; blockedURI: string };
type Color = [number, number, number, number];
type FirstMount = { time: number; backgroundColor: string; color: string; textRendering: string; overrideLoaded: boolean };
type FirstLogo = { light: string; dark: string };
type ThemeRecords = { __cspViolations: Violation[]; __pbFirstMount?: FirstMount; __pbFirstLogo?: FirstLogo };
type PaintState = { theme: string | null; backgroundColor: string; color: string; rootEmpty: boolean; violations: Violation[] };
type FrozenMode = {
  sections: {
    body: { body: { elements: Array<{ "background-color": Color; color: Color }> } };
    logo: Record<".pb-logo-light" | ".pb-logo-dark", { elements: Array<{ display: string }> }>;
  };
};
type ShellObservation = {
  originalHash: string;
  servedHash: string;
  originalBootstrapHash: string;
  servedBootstrapHash: string;
  addedBytes: number;
  csp: string | undefined;
  contentLengthRemoved: boolean;
};

const probe = "body { text-rendering: geometricPrecision; }";
const colorTolerance = 0.002;
const frozen = JSON.parse(readFileSync(new URL("./fixtures/theme-baseline.json", import.meta.url), "utf8")) as {
  modes: Record<"light" | "dark", FrozenMode>;
};

async function captureViolations(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const sink: Violation[] = [];
    (window as unknown as { __cspViolations: Violation[] }).__cspViolations = sink;
    window.addEventListener("securitypolicyviolation", (event) => {
      sink.push({ violatedDirective: event.violatedDirective, blockedURI: event.blockedURI });
    });
  });
}

async function readStylesheetState(page: Page) {
  return page.evaluate(() => ({
    stylesheets: Array.from(document.styleSheets).flatMap((sheet) =>
      sheet.ownerNode instanceof HTMLLinkElement && sheet.href ? [new URL(sheet.href).pathname] : []),
    textRendering: getComputedStyle(document.body).textRendering,
    violations: (window as unknown as { __cspViolations: Violation[] }).__cspViolations,
  }));
}

function expectedAppearance(mode: "light" | "dark") {
  const sections = frozen.modes[mode].sections;
  const body = sections.body.body.elements[0];
  return {
    backgroundColor: body["background-color"],
    color: body.color,
    logos: {
      light: sections.logo[".pb-logo-light"].elements[0].display,
      dark: sections.logo[".pb-logo-dark"].elements[0].display,
    },
  };
}

function colorDifference(actual: Color, expected: Color): number {
  return Math.max(...expected.map((value, index) => Math.abs(actual[index] - value)));
}

async function recordFirstMount(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const records = window as unknown as ThemeRecords;
    const observer = new MutationObserver(() => {
      if (!records.__pbFirstMount && document.getElementById("root")?.firstElementChild) {
        const style = getComputedStyle(document.body);
        records.__pbFirstMount = {
          time: performance.now(),
          backgroundColor: style.backgroundColor,
          color: style.color,
          textRendering: style.textRendering,
          overrideLoaded: Array.from(document.styleSheets).some((sheet) => sheet.href?.endsWith("/_plainbase/custom.css")),
        };
      }
      const light = document.querySelector(".pb-logo-light");
      const dark = document.querySelector(".pb-logo-dark");
      if (!records.__pbFirstLogo && light && dark) {
        records.__pbFirstLogo = { light: getComputedStyle(light).display, dark: getComputedStyle(dark).display };
      }
      if (records.__pbFirstMount && records.__pbFirstLogo) observer.disconnect();
    });
    observer.observe(document, { childList: true, subtree: true });
  });
}

async function readPaintState(page: Page): Promise<PaintState | null> {
  return page.evaluate(() => {
    if (!document.body) return null;
    const style = getComputedStyle(document.body);
    return {
      theme: document.documentElement.getAttribute("data-theme"),
      backgroundColor: style.backgroundColor,
      color: style.color,
      rootEmpty: document.getElementById("root")?.childElementCount === 0,
      violations: (window as unknown as ThemeRecords).__cspViolations,
    };
  });
}

async function entryModuleURL(request: APIRequestContext, shellURL: string): Promise<string> {
  const response = await request.get(shellURL);
  expect(response.status()).toBe(200);
  const html = await response.text();
  const entries = [...html.matchAll(/<script\b[^>]*>/gi)].flatMap(([tag]) => {
    const source = tag.match(/\ssrc\s*=\s*["']([^"']+)["']/i);
    return /\stype\s*=\s*["']module["']/i.test(tag) && source ? [source[1]] : [];
  });
  expect(entries).toHaveLength(1);
  return new URL(entries[0], shellURL).href;
}

function holdEntry() {
  let finish!: (abort: boolean) => void;
  let requested = false;
  const held = new Promise<boolean>((resolve) => { finish = resolve; });
  return {
    get requested() { return requested; },
    async handler(route: Route) {
      requested = true;
      if (await held) await route.abort();
      else await route.continue();
    },
    release() { finish(false); },
    abort() { finish(true); },
  };
}

function documentRoute(changeBootstrap: boolean) {
  let observation: ShellObservation | undefined;
  return {
    get observation() { return observation; },
    async handler(route: Route) {
      const response = await route.fetch();
      const original = await response.body();
      const html = original.toString("utf8");
      const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)]
        .filter((match) => !/\ssrc\s*=/i.test(match[1]));
      if (scripts.length !== 1) throw new Error("Expected one inline bootstrap in the served shell");
      const script = scripts[0];
      const start = script.index! + script[0].indexOf(">") + 1;
      const byteStart = Buffer.byteLength(html.slice(0, start));
      const byteEnd = Buffer.byteLength(html.slice(0, start + script[2].length));
      const bootstrap = original.subarray(byteStart, byteEnd);
      const served = changeBootstrap
        ? Buffer.concat([original.subarray(0, byteStart), Buffer.from(" "), original.subarray(byteStart)])
        : original;
      const servedBootstrap = changeBootstrap ? Buffer.concat([Buffer.from(" "), bootstrap]) : bootstrap;
      const headers = { ...response.headers() };
      if (changeBootstrap) delete headers["content-length"];
      observation = {
        originalHash: createHash("sha256").update(original).digest("hex"),
        servedHash: createHash("sha256").update(served).digest("hex"),
        originalBootstrapHash: `sha256-${createHash("sha256").update(bootstrap).digest("base64")}`,
        servedBootstrapHash: `sha256-${createHash("sha256").update(servedBootstrap).digest("base64")}`,
        addedBytes: served.length - original.length,
        csp: headers["content-security-policy"],
        contentLengthRemoved: changeBootstrap && !("content-length" in headers),
      };
      if (changeBootstrap) await route.fulfill({ response, headers, body: served });
      else await route.fulfill({ response });
    },
  };
}

test("an operator override beats the equal-specificity app rule after modules run", async ({ page, smokeServer }, testInfo) => {
  writeFileSync(path.join(smokeServer.dataDir, "custom.css"), probe);
  await captureViolations(page);
  await gotoAndWaitForSearchReady(page, "/docs/scratch/todo");
  const state = await readStylesheetState(page);
  const snapshotPath = testInfo.outputPath("stylesheet-loading.json");
  writeFileSync(snapshotPath, JSON.stringify(state, null, 2) + "\n");
  await testInfo.attach("stylesheet-loading", { path: snapshotPath, contentType: "application/json" });
  expect.soft(state.stylesheets, "stylesheet order").toEqual([
    expect.stringMatching(/^\/assets\/[^/]+\.css$/),
    "/_plainbase/custom.css",
  ]);
  expect.soft(state.textRendering.toLowerCase(), "operator text-rendering").toBe("geometricprecision");
  expect.soft(state.violations, "CSP violations").toEqual([]);
});

test("without an override the app rule holds", async ({ page }, testInfo) => {
  await captureViolations(page);
  await gotoAndWaitForSearchReady(page, "/docs/scratch/todo");
  const state = await readStylesheetState(page);
  const snapshotPath = testInfo.outputPath("stylesheet-loading.json");
  writeFileSync(snapshotPath, JSON.stringify(state, null, 2) + "\n");
  await testInfo.attach("stylesheet-loading", { path: snapshotPath, contentType: "application/json" });
  expect.soft(state.textRendering.toLowerCase(), "app text-rendering").toBe("optimizelegibility");
  expect.soft(state.violations, "CSP violations").toEqual([]);
});

for (const mode of ["light", "dark"] as const) {
  test(`first mount is styled while the entry module is held in ${mode} mode`, async ({ page, request, smokeServer }, testInfo) => {
    await page.emulateMedia({ colorScheme: mode });
    await captureViolations(page);
    await recordFirstMount(page);
    const shellURL = new URL("/docs/scratch/todo", smokeServer.baseURL).href;
    const entryURL = await entryModuleURL(request, shellURL);
    const entry = holdEntry();
    const shellRoute = documentRoute(false);
    const expected = expectedAppearance(mode);
    let heldState: PaintState | null = null;
    let heldColors: Color[] = [];
    try {
      await page.route(shellURL, shellRoute.handler);
      await page.route(entryURL, entry.handler);
      await page.goto(shellURL, { waitUntil: "commit" });
      await expect.poll(() => entry.requested).toBe(true);
      await expect.poll(async () => {
        heldState = await readPaintState(page);
        if (!heldState) return null;
        heldColors = await normalizeColors(page, [heldState.backgroundColor, heldState.color]);
        return {
          theme: heldState.theme,
          rootEmpty: heldState.rootEmpty,
          backgroundMatches: colorDifference(heldColors[0], expected.backgroundColor) <= colorTolerance,
          foregroundMatches: colorDifference(heldColors[1], expected.color) <= colorTolerance,
        };
      }).toEqual({ theme: mode === "dark" ? "dark" : null, rootEmpty: true, backgroundMatches: true, foregroundMatches: true });
      entry.release();
      await expect.poll(() => page.evaluate(() => {
        const records = window as unknown as ThemeRecords;
        return Boolean(records.__pbFirstMount && records.__pbFirstLogo);
      })).toBe(true);
      const first = await page.evaluate(() => {
        const records = window as unknown as ThemeRecords;
        return { mount: records.__pbFirstMount, logos: records.__pbFirstLogo, violations: records.__cspViolations };
      });
      if (!first.mount || !first.logos) throw new Error("First-mount observations missing after readiness");
      const colors = await normalizeColors(page, [first.mount.backgroundColor, first.mount.color]);
      const snapshotPath = testInfo.outputPath("first-mount.json");
      const snapshot = { entryURL, shell: shellRoute.observation, heldState, heldColors, first, colors };
      writeFileSync(snapshotPath, JSON.stringify(snapshot, null, 2) + "\n");
      await testInfo.attach("first-mount", { path: snapshotPath, contentType: "application/json" });
      const shell = shellRoute.observation!;
      expect(shell.csp).toContain(shell.originalBootstrapHash);
      expect(colorDifference(colors[0], expected.backgroundColor), "first mount background").toBeLessThanOrEqual(colorTolerance);
      expect(colorDifference(colors[1], expected.color), "first mount foreground").toBeLessThanOrEqual(colorTolerance);
      expect(first.logos).toEqual(expected.logos);
      expect(first.violations).toEqual([]);
    } finally {
      entry.abort();
      await page.unrouteAll({ behavior: "wait" });
    }
  });
}

test("a bootstrap whose bytes miss the pinned hash is refused", async ({ page, request, smokeServer }, testInfo) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await captureViolations(page);
  await recordFirstMount(page);
  const shellURL = new URL("/docs/scratch/todo", smokeServer.baseURL).href;
  const entryURL = await entryModuleURL(request, shellURL);
  const entry = holdEntry();
  const shellRoute = documentRoute(true);
  try {
    await page.route(shellURL, shellRoute.handler);
    await page.route(entryURL, entry.handler);
    await page.goto(shellURL, { waitUntil: "commit" });
    await expect.poll(() => entry.requested).toBe(true);
    await page.waitForFunction(() => (window as unknown as ThemeRecords).__cspViolations.length > 0, undefined, { timeout: 10_000 })
      .then((handle) => handle.dispose())
      .catch((error: unknown) => {
        if (!(error instanceof Error) || error.name !== "TimeoutError") throw error;
      });
    await expect.poll(() => page.evaluate(() => Boolean(document.body) && Array.from(document.styleSheets).some((sheet) =>
      sheet.href && /^\/assets\/[^/]+\.css$/.test(new URL(sheet.href).pathname) && sheet.cssRules.length > 0))).toBe(true);
    const state = await readPaintState(page);
    if (!state) throw new Error("Missing body after app stylesheet readiness");
    const [background] = await normalizeColors(page, [state.backgroundColor]);
    const snapshotPath = testInfo.outputPath("refused-bootstrap.json");
    writeFileSync(snapshotPath, JSON.stringify({ entryURL, shell: shellRoute.observation, state, background }, null, 2) + "\n");
    await testInfo.attach("refused-bootstrap", { path: snapshotPath, contentType: "application/json" });
    expect.soft(state.violations.some((violation) => violation.violatedDirective.startsWith("script-src")), "script refusal").toBe(true);
    expect.soft(state.theme, "bootstrap theme absent").toBeNull();
    expect.soft(colorDifference(background, expectedAppearance("light").backgroundColor), "light background")
      .toBeLessThanOrEqual(colorTolerance);
  } finally {
    entry.abort();
    await page.unrouteAll({ behavior: "wait" });
  }
});

test("first mount waits for a delayed override sheet", async ({ page, request, smokeServer }, testInfo) => {
  writeFileSync(path.join(smokeServer.dataDir, "custom.css"), probe);
  await page.emulateMedia({ colorScheme: "dark" });
  await captureViolations(page);
  await recordFirstMount(page);
  const shellURL = new URL("/docs/scratch/todo", smokeServer.baseURL).href;
  const entryURL = await entryModuleURL(request, shellURL);
  const override = holdEntry();
  const settleMs = 1500;
  const expected = expectedAppearance("dark");
  try {
    await page.route("**/_plainbase/custom.css", override.handler);
    const entryResponse = page.waitForResponse(entryURL);
    await page.goto(shellURL, { waitUntil: "commit" });
    await (await entryResponse).finished();
    await expect.poll(() => override.requested).toBe(true);
    await page.waitForTimeout(settleMs);
    const settled = await readPaintState(page);
    expect.soft(settled?.theme, "theme while override is held").toBe("dark");
    expect.soft(settled?.rootEmpty, "root empty while override is held").toBe(true);
    override.release();
    await expect.poll(() => page.evaluate(() => {
      const records = window as unknown as ThemeRecords;
      return Boolean(records.__pbFirstMount && records.__pbFirstLogo);
    })).toBe(true);
    const first = await page.evaluate((url) => {
      const records = window as unknown as ThemeRecords;
      const entry = performance.getEntriesByName(url, "resource")[0] as PerformanceResourceTiming | undefined;
      return {
        mount: records.__pbFirstMount,
        logos: records.__pbFirstLogo,
        violations: records.__cspViolations,
        entryResponseEnd: entry?.responseEnd,
      };
    }, entryURL);
    if (!first.mount || !first.logos || first.entryResponseEnd === undefined) {
      throw new Error("First-mount observations or entry timing missing after readiness");
    }
    const colors = await normalizeColors(page, [first.mount.backgroundColor, first.mount.color]);
    // Attached as evidence that the settle stays far above the observed mount latency; not asserted.
    const mountLatencyMs = first.mount.time - first.entryResponseEnd;
    const snapshotPath = testInfo.outputPath("delayed-override.json");
    const snapshot = { entryURL, settleMs, settled, first, colors, mountLatencyMs };
    writeFileSync(snapshotPath, JSON.stringify(snapshot, null, 2) + "\n");
    await testInfo.attach("delayed-override", { path: snapshotPath, contentType: "application/json" });
    expect.soft(first.mount.textRendering.toLowerCase(), "first mount operator text-rendering").toBe("geometricprecision");
    expect.soft(first.mount.overrideLoaded, "override loaded at first mount").toBe(true);
    expect.soft(colorDifference(colors[0], expected.backgroundColor), "first mount background").toBeLessThanOrEqual(colorTolerance);
    expect.soft(colorDifference(colors[1], expected.color), "first mount foreground").toBeLessThanOrEqual(colorTolerance);
    expect.soft(first.logos, "first logo visibility").toEqual(expected.logos);
  } finally {
    override.abort();
    await page.unrouteAll({ behavior: "wait" });
  }
});
