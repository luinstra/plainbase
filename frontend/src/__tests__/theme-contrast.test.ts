// @vitest-environment node
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { background, block, color, contrast, declarations, expand, over, parse, rows, stripComments, type Color } from "./contrastResolver";

const source = stripComments(readFileSync("src/styles/tokens.css", "utf8"));
const light = block(source, ":root");
const dark = { ...light, ...block(source, '[data-theme="dark"]') };
function tuple(actual: Color, expected: number[]) {
  actual.forEach((v, i) => expect(Math.abs(v - expected[i])).toBeLessThan(0.0001));
}
const hexes = [...source.matchAll(/#[\da-f]{3}(?:[\da-f]{3})?\b/gi)].map((m) => m[0]);
const white = hexes.find((h) => h.length === 4 && parse(h)[0] === 1)!;
const black = hexes.find((h) => h.length === 4 && parse(h)[0] === 0)!;
const focus = hexes.find((h) => h.length === 7 && parse(h)[0] === 13 / 255 && parse(h)[1] === 148 / 255)!;
const controls = { "--pb-a": white, "--pb-b": black, "--pb-weight": "25%" };
const resolve = (expression: string, tokens = controls) => parse(expand(expression, tokens));

describe("contrast resolver independent controls", () => {
  it("parses real short and long hex", () => { tuple(parse(white), [1, 1, 1, 1]); tuple(parse(focus), [13 / 255, 148 / 255, 136 / 255, 1]); });
  it("expands percentage variables and either operand weight", () => {
    tuple(resolve("color-mix(in srgb, var(--pb-a) var(--pb-weight), var(--pb-b))"), [0.25, 0.25, 0.25, 1]);
    tuple(resolve("color-mix(in srgb, var(--pb-a), var(--pb-b) 25%)"), [0.75, 0.75, 0.75, 1]);
    tuple(resolve("color-mix(in srgb, var(--pb-a), var(--pb-b))"), [0.5, 0.5, 0.5, 1]);
  });
  it("handles nested mixtures and premultiplied transparency", () => {
    tuple(resolve("color-mix(in srgb, color-mix(in srgb, var(--pb-a), var(--pb-b)), var(--pb-b) 50%)"), [0.25, 0.25, 0.25, 1]);
    tuple(resolve("color-mix(in srgb, var(--pb-a) 25%, transparent)"), [1, 1, 1, 0.25]);
    tuple(over([1, 0, 0, 0.25], [0, 0, 1, 1]), [0.25, 0, 0.75, 1]);
    expect(contrast([0, 0, 0, 1], [1, 1, 1, 1])).toBe(21);
  });
  it.each(["var(--pb-missing)", "color-mix(in oklab, var(--pb-a), var(--pb-b))", "color-mix(in srgb, var(--pb-a) 25%, var(--pb-b) 75%)", "color-mix(in srgb, var(--pb-a) 101%, var(--pb-b))", "color-mix(in srgb, var(--pb-a) -1%, var(--pb-b))", "color-mix(in srgb, var(--pb-a) many%, var(--pb-b))", "unrecognized"])("rejects %s", (s) => expect(() => resolve(s)).toThrow());
  it("rejects cycles and excessive recursion", () => {
    expect(() => expand("var(--pb-a)", { "--pb-a": "var(--pb-b)", "--pb-b": "var(--pb-a)" })).toThrow("cycle");
    const chain = Object.fromEntries(Array.from({ length: 70 }, (_, i) => [`--pb-step-${i}`, `var(--pb-step-${i + 1})`]));
    expect(() => expand("var(--pb-step-0)", chain)).toThrow("recursion");
  });
  it("rejects empty inventories", () => expect(() => declarations("/* empty */")).toThrow());
});

for (const [theme, tokens] of Object.entries({ light, dark })) {
  describe(`${theme} rendered-state contrast matrix`, () => {
    it("selected foreground follows text ink", () => {
      expect(tokens["--pb-selection-text"]).toBe("var(--pb-text)");
    });
    it.each(rows.map(([ink, stack, floor]) => [`${ink} on ${stack.join(" + ")} (${floor})`, ink, stack, floor] as const))("%s", (_, ink, stack, floor) => {
      const ratio = contrast(color(tokens, ink), background(tokens, [...stack]));
      expect(ratio).toBeGreaterThanOrEqual(theme === "dark" && ink === "primary-text" ? 6.5 : floor);
    });
    it.each(["note", "warning", "danger"])("%s callouts remain opaque through nesting", (kind) => {
      const name = `callout-${kind}-bg`;
      expect(color(tokens, name)[3]).toBe(1);
      tuple(background(tokens, ["surface", name, name]), color(tokens, name));
      const broken = { ...tokens, [`--pb-${name}`]: "color-mix(in srgb, var(--pb-accent) 18%, transparent)" };
      expect(color(broken, name)[3]).not.toBe(1);
      expect(background(broken, ["surface", name, name])).not.toEqual(background(broken, ["surface", name]));
    });
  });
}

describe("independent palette anchors and regressions", () => {
  it.each([
    ["surface", [0.121961, 0.121176, 0.135098, 1]], ["surface-raised", [0.153333, 0.152549, 0.162745, 1]],
    ["surface-hover", [0.188627, 0.187843, 0.209216, 1]],
  ] as const)("preserves dark %s", (name, expected) => tuple(color(dark, name), [...expected]));
  it("dark chrome matches raised", () => tuple(color(dark, "surface-chrome"), color(dark, "surface-raised")));
  it.each([
    ["surface", [0.984314, 0.980392, 0.968627, 1]], ["surface-chrome", [0.956863, 0.949020, 0.925490, 1]],
    ["surface-raised", [1, 1, 1, 1]], ["accent-soft", [0.924706, 0.957020, 0.954510, 1]],
    ["warning-soft", [0.988078, 0.957333, 0.921882, 1]], ["danger-soft", [0.989020, 0.931922, 0.931922, 1]],
  ] as const)("light %s matches approved palette", (name, expected) => tuple(color(light, name), [...expected]));
  it.each(["accent-soft", "warning-soft", "danger-soft"])("light text on opaque %s", (bg) => {
    for (const ink of ["link", "accent", "text", "text-muted", "text-faint"]) expect(contrast(color(light, ink), background(light, ["surface", bg]))).toBeGreaterThanOrEqual(4.5);
  });
  it("old accent tint fails on approved paper", () => {
    const paper: Color = [251 / 255, 250 / 255, 247 / 255, 1];
    const old: Color = [15 / 255, 118 / 255, 110 / 255, 0.13];
    expect(contrast([15 / 255, 118 / 255, 110 / 255, 1], over(old, paper))).toBeCloseTo(4.38489, 4);
    expect(contrast([15 / 255, 118 / 255, 110 / 255, 1], over(old, paper))).toBeLessThan(4.5);
  });
  it("inherited dark selected faint and comments fail independently", () => {
    const selected: Color = [19 / 255, 78 / 255, 74 / 255, 1];
    expect(contrast([0.6039, 0.6, 0.631, 1], selected)).toBeLessThan(4.5);
    expect(contrast([0.69294, 0.68627, 0.7198, 1], selected)).toBeLessThan(4.5);
  });
  it("light logo slash matches the independently anchored focus mark", () => {
    const svg = readFileSync("public/plainbase-logo.svg", "utf8");
    const fills = [...svg.matchAll(/fill="([^"]+)"/g)].map((m) => m[1]);
    const check = (values: string[]) => {
      const slash = values[0];
      tuple(parse(slash), [13 / 255, 148 / 255, 136 / 255, 1]);
      tuple(parse(slash), color(light, "focus-ring"));
    };
    check(fills);
    const old = hexes.find((h) => parse(h)[0] === 15 / 255 && parse(h)[1] === 118 / 255)!;
    expect(() => check([old])).toThrow();
  });
});
