import { readFileSync } from "node:fs";
import { expect, test, type Page } from "@playwright/test";
import { normalizeColors } from "./theme-colors";
import { parse, type Color } from "../src/__tests__/contrastResolver";
import {
  builtinCss, builtinSlots, COLOUR_ROLES, customSlots, fixtureDefinitions, fixtureModes,
  fourInputDefinition, genericCss, primitiveNames, readFrozen, SEMANTIC, type Mode, type Slots,
} from "../src/__tests__/themeCandidates";

function readBaseline(): { modes: Record<Mode, { tokens: Record<string, Color> }> } {
  return JSON.parse(readFileSync("e2e/fixtures/theme-baseline.json", "utf8"));
}
const drillAudit = process.env.PB_THEME_DRILL_AUDIT === "1";
function readProbes() { return fixtureDefinitions().probes; }
const modes: Mode[] = ["light", "dark"];
const consumerCss = `
body { background: var(--pb-surface); color: var(--pb-text) }
.pb-discussion-card { background: var(--pb-surface-raised); color: var(--pb-card-foreground) }
.pb-field { background: var(--pb-surface-field) }
pre { background: var(--pb-code-bg) }
.pb-selected { background: var(--pb-selection-bg); color: var(--pb-selection-text) }
.hljs-title { color: var(--pb-syntax-title) }
.hljs-keyword { color: var(--pb-syntax-keyword) }
.pb-font-sans-probe { font-family: var(--pb-font-sans) }
.pb-radius-probe { border-radius: var(--pb-radius-md) }
`;

// The real-server baseline confirms Mermaid 12 SVG selectors below .pb-prose [data-pb-mermaid] svg:
// .node rect, .node text, .cluster rect, .edgePaths path, marker path, .edgeLabel rect.
// Baseline capture asserts every selector has nonzero matches and saves the actual SVG.
// These documents exercise candidate tokens only; @playwright/test supplies no smoke server fixture.
async function documentFor(page: Page, mode: Mode, slots: Slots) {
  await page.setContent(`<!doctype html><html><head>
    ${slots.map((css, index) => `<style data-pb-slot="${index + 1}">${css}</style>`).join("\n")}
    <style>${consumerCss}</style></head><body>
    <div class="pb-discussion-card">Discussion</div><input class="pb-field"><pre>Code</pre>
    <span class="pb-selected">Selected</span><span class="hljs-title">Title</span>
    <span class="hljs-keyword">Keyword</span><span class="pb-font-sans-probe">Typeface</span>
    <span class="pb-radius-probe">Radius</span></body></html>`);
  await page.evaluate((mode) => {
    if (mode === "dark") document.documentElement.dataset.theme = "dark";
    else delete document.documentElement.dataset.theme;
  }, mode);
  if (mode === "dark") await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  else await expect(page.locator("html")).not.toHaveAttribute("data-theme");
}

async function roleColors(page: Page, names: string[]): Promise<Color[]> {
  const values = await page.evaluate((names) => {
    const probe = document.createElement("span");
    document.body.appendChild(probe);
    try {
      return names.map((name) => {
        probe.style.backgroundColor = `var(${name})`;
        return getComputedStyle(probe).backgroundColor;
      });
    } finally { probe.remove(); }
  }, names);
  return normalizeColors(page, values);
}

function colourDelta(actual: Color, expected: Color): number {
  return Math.max(...actual.map((channel, index) => Math.abs(channel - expected[index])));
}

function sameColour(actual: Color, expected: Color, label: string) {
  const delta = colourDelta(actual, expected);
  expect.soft(delta, `${label}: max-channel delta ${delta}`).toBeLessThanOrEqual(0.002);
}

async function styleValue(page: Page, selector: string, property: string): Promise<string> {
  await expect(page.locator(selector)).toHaveCount(1);
  return page.locator(selector).evaluate((element, property) => getComputedStyle(element).getPropertyValue(property), property);
}

async function consumerColour(page: Page, selector: string, property = "background-color"): Promise<Color> {
  return (await normalizeColors(page, [await styleValue(page, selector, property)]))[0];
}

async function lateOverride(page: Page, input: string) {
  await page.evaluate(({ input, value }) => {
    const override = document.createElement("style");
    override.textContent = `:root { ${input}: ${value}; }`;
    document.head.appendChild(override);
  }, { input, value: readProbes()[input].value });
}

async function stableConsumers(page: Page) {
  return {
    surface: await consumerColour(page, "body"),
    ink: await consumerColour(page, "body", "color"),
    keyword: await consumerColour(page, ".hljs-keyword", "color"),
  };
}

async function assertStable(page: Page, before: Awaited<ReturnType<typeof stableConsumers>>) {
  const after = await stableConsumers(page);
  for (const key of ["surface", "ink", "keyword"] as const) sameColour(after[key], before[key], `unchanged ${key}`);
}

const selections: Array<{ name: string; slots: () => Slots; custom: Partial<Record<Mode, "opaque-light" | "opaque-dark" | "translucent-dark">> }> = [
  { name: "builtin/custom", slots: () => [genericCss(), builtinCss("light"), fourInputDefinition("opaque-dark")], custom: { dark: "opaque-dark" } },
  { name: "custom/builtin", slots: () => [genericCss(), fourInputDefinition("opaque-light"), builtinCss("dark")], custom: { light: "opaque-light" } },
  { name: "custom/custom", slots: () => customSlots(), custom: { light: "opaque-light", dark: "opaque-dark" } },
  { name: "custom/translucent", slots: () => customSlots("translucent-dark"), custom: { light: "opaque-light", dark: "translucent-dark" } },
];
const genericConsumers: Array<{ input: string; targets: Array<[string, string]> }> = [
  { input: "--pb-theme-card", targets: [[".pb-discussion-card", "background-color"], [".pb-field", "background-color"], ["pre", "background-color"]] },
  { input: "--pb-theme-selection", targets: [[".pb-selected", "background-color"]] },
  { input: "--pb-theme-syntax-title", targets: [[".hljs-title", "color"]] },
  { input: "--pb-theme-font-sans", targets: [[".pb-font-sans-probe", "font-family"]] },
  { input: "--pb-theme-radius", targets: [[".pb-radius-probe", "border-radius"]] },
];

test.describe("candidate graph (isolated document, not shipped consumers)", () => {
  for (const fixtureMode of fixtureModes) {
    test(`P1 Chromium agrees with the textual resolver ${fixtureMode}`, async ({ page }) => {
      const frozen = readFrozen();
      const mode = fixtureMode === "opaque-light" ? "light" : "dark";
      await documentFor(page, mode, customSlots(fixtureMode === "translucent-dark" ? fixtureMode : "opaque-dark"));
      const actual = await roleColors(page, COLOUR_ROLES);
      COLOUR_ROLES.forEach((name, index) => sameColour(actual[index], frozen.fixtures[fixtureMode].roles[name] as Color, name));
    });
  }

  for (const selection of selections) for (const mode of modes) {
    test(`P2 exclusive scopes isolate every mixed selection ${selection.name} ${mode}`, async ({ page }) => {
      await documentFor(page, mode, selection.slots());
      const fixtureMode = selection.custom[mode];
      if (fixtureMode) {
        const actual = await page.evaluate((names) => {
          const style = getComputedStyle(document.documentElement);
          return names.map((name) => [name, style.getPropertyValue(name).trim()]);
        }, primitiveNames());
        for (const [name, value] of actual) expect.soft(value, `leaked ${name}`).toBe("");
      }
      const expected = fixtureMode ? readFrozen().fixtures[fixtureMode].roles["--pb-surface"] as Color : readBaseline().modes[mode].tokens["--pb-surface"];
      sameColour(await consumerColour(page, "body"), expected, `${selection.name} ${mode} surface`);
    });
  }

  for (const mode of modes) for (const { input, targets } of genericConsumers) {
    test(`P3 a late root override reaches generic consumers on custom/custom ${mode} ${input}`, async ({ page }) => {
      const probes = readProbes();
      await documentFor(page, mode, customSlots());
      const stable = await stableConsumers(page);
      const kind = probes[input].kind;
      const before = await Promise.all(targets.map(([selector, property]) => kind === "colour"
        ? consumerColour(page, selector, property) : styleValue(page, selector, property)));
      const expected = kind === "colour" ? parse(probes[input].value) : kind === "font" ? probes[input].value : "10.5px";
      const deltas = before.map((value) => kind === "colour" ? colourDelta(value as Color, expected as Color) : "exact flip");
      for (const delta of deltas) if (typeof delta === "number") expect(delta, `${input} probe separation`).toBeGreaterThanOrEqual(0.02);
      if (drillAudit) console.info("BROWSER_PROBE_DELTA " + JSON.stringify({ lens: "generic", mode, input, deltas }));
      await lateOverride(page, input);
      for (const [index, [selector, property]] of targets.entries()) {
        if (kind === "colour") sameColour(await consumerColour(page, selector, property), expected as Color, `${input} ${selector}`);
        else {
          const actual = await styleValue(page, selector, property);
          expect.soft(actual, `${input} ${selector}`).toBe(expected);
          expect.soft(actual, `${input} changes ${selector}`).not.toBe(before[index]);
        }
      }
      await assertStable(page, stable);
    });
  }

  for (const mode of modes) {
    test(`P4 a late root override reaches builtin consumers on builtin/builtin ${mode}`, async ({ page }) => {
      const probes = readProbes();
      const baseline = readBaseline();
      await documentFor(page, mode, builtinSlots());
      const stable = await stableConsumers(page);
      const selectors = [".pb-discussion-card", ".pb-field", "pre"];
      const before = await Promise.all(selectors.map((selector) => consumerColour(page, selector)));
      const probe = parse(probes["--pb-theme-card"].value);
      const delta = colourDelta(before[0], probe);
      expect(delta, `${mode} card probe separation`).toBeGreaterThanOrEqual(0.02);
      if (drillAudit) console.info("BROWSER_PROBE_DELTA " + JSON.stringify({ lens: "builtin", mode, input: "--pb-theme-card", deltas: [delta] }));
      for (const [index, name] of ["--pb-surface-raised", "--pb-surface-field", "--pb-code-bg"].entries()) {
        sameColour(before[index], baseline.modes[mode].tokens[name], `${mode} pre-probe ${name}`);
      }
      await lateOverride(page, "--pb-theme-card");
      for (const [index, selector] of selectors.entries()) {
        sameColour(await consumerColour(page, selector), mode === "light" || index === 0 ? probe : before[index], `${mode} ${selector}`);
      }
      await assertStable(page, stable);
    });
    test(`P5 built-in candidates render the baseline token values ${mode}`, async ({ page }) => {
      const baseline = readBaseline();
      await documentFor(page, mode, builtinSlots());
      const actual = await roleColors(page, SEMANTIC);
      if (drillAudit) {
        const channels = Object.fromEntries(SEMANTIC.map((name, index) => [name,
          actual[index].map((channel, i) => Math.abs(channel - baseline.modes[mode].tokens[name][i])),
        ]));
        const deltas = Object.fromEntries(Object.entries(channels).map(([name, values]) => [name, Math.max(...values)]));
        console.info("BROWSER_MATRIX_DELTA " + JSON.stringify({ mode, channels, deltas, maximum: Math.max(...Object.values(deltas)) }));
      }
      SEMANTIC.forEach((name, index) => sameColour(actual[index], baseline.modes[mode].tokens[name], `${mode} ${name}`));
    });
  }
});
