// @vitest-environment node
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

type Color = [number, number, number, number];
type Tokens = Record<string, string>;
const source = readFileSync("src/styles/tokens.css", "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
function declarations(css: string): Tokens {
  const entries = [...css.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]);
  if (!entries.length) throw new Error("empty declarations");
  return Object.fromEntries(entries);
}
function block(selector: string): Tokens {
  const start = source.indexOf(selector);
  if (start < 0) throw new Error(`missing ${selector}`);
  return declarations(source.slice(source.indexOf("{", start) + 1, source.indexOf("}", start)));
}
const light = block(":root");
const dark = { ...light, ...block('[data-theme="dark"]') };
function expand(value: string, tokens: Tokens, seen: string[] = []): string {
  if (seen.length > 64) throw new Error("excessive recursion");
  return value.replace(/var\((--[\w-]+)\)/g, (_, key: string) => {
    if (!(key in tokens)) throw new Error(`unknown ${key}`);
    if (seen.includes(key)) throw new Error(`cycle ${key}`);
    return expand(tokens[key], tokens, [...seen, key]);
  });
}
function split(value: string): string[] {
  let depth = 0;
  let start = 0;
  const parts: string[] = [];
  for (let i = 0; i < value.length; i++) {
    if (value[i] === "(") depth++;
    if (value[i] === ")") depth--;
    if (depth < 0) throw new Error("unbalanced expression");
    if (value[i] === "," && depth === 0) { parts.push(value.slice(start, i).trim()); start = i + 1; }
  }
  if (depth) throw new Error("unbalanced expression");
  parts.push(value.slice(start).trim());
  return parts;
}
function parse(value: string): Color {
  value = value.trim();
  if (value === "transparent") return [0, 0, 0, 0];
  if (/^#[\da-f]{3}$|^#[\da-f]{6}$/i.test(value)) {
    const digits = value.slice(1).length === 3 ? [...value.slice(1)].map((c) => c + c).join("") : value.slice(1);
    return [0, 2, 4].map((i) => parseInt(digits.slice(i, i + 2), 16) / 255).concat(1) as Color;
  }
  if (!value.startsWith("color-mix(") || !value.endsWith(")")) throw new Error(`unsupported ${value}`);
  const parts = split(value.slice(10, -1));
  if (parts.length !== 3 || parts[0] !== "in srgb") throw new Error("unsupported mix");
  const operands = parts.slice(1).map((part) => {
    const weight = part.match(/\s+(\d+(?:\.\d+)?)%$/);
    return { color: parse(weight ? part.slice(0, weight.index) : part), weight: weight ? Number(weight[1]) / 100 : undefined };
  });
  const [a, b] = operands;
  if (a.weight !== undefined && b.weight !== undefined) throw new Error("two weights unsupported");
  const weight = a.weight ?? (b.weight === undefined ? 0.5 : 1 - b.weight);
  if (weight < 0 || weight > 1) throw new Error("invalid weight");
  const alpha = a.color[3] * weight + b.color[3] * (1 - weight);
  return [0, 1, 2].map((i) => alpha ? (a.color[i] * a.color[3] * weight + b.color[i] * b.color[3] * (1 - weight)) / alpha : 0).concat(alpha) as Color;
}
function color(tokens: Tokens, name: string): Color { return parse(expand(`var(--pb-${name})`, tokens)); }
function over(top: Color, base: Color): Color {
  if (base[3] !== 1) throw new Error("base must be opaque");
  return [0, 1, 2].map((i) => top[i] * top[3] + base[i] * (1 - top[3])).concat(1) as Color;
}
function contrast(a: Color, b: Color): number {
  const luminance = (c: Color) => c.slice(0, 3).map((v) => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)
    .reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
  const values = [luminance(over(a, b)), luminance(b)].sort((x, y) => x - y);
  return (values[1] + 0.05) / (values[0] + 0.05);
}
function background(tokens: Tokens, stack: string[]): Color {
  const [base, ...layers] = stack.map((name) => color(tokens, name));
  if (!base || base[3] !== 1) throw new Error("missing opaque base");
  return layers.reduce((result, layer) => over(layer, result), base);
}
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

const rows: Array<[string, string[], number]> = [];
function add(inks: string[], stacks: string[][], floor = 4.5) { for (const ink of inks) for (const stack of stacks) rows.push([ink, stack, floor]); }
const base = ["surface", "surface-raised", "surface-chrome", "surface-hover", "surface-field"];
add(["text", "text-muted", "text-faint"], base.map((s) => [s]));
add(["text"], [["surface-chrome", "nav-active-bg"], ["surface-active"]]);
add(["text-muted", "link"], [["surface-raised", "accent-soft"]]);
add(["link", "link-hover", "accent"], [["surface", "accent-soft"], ["surface-raised", "accent-soft"]]);
for (const callout of ["note", "warning", "danger"]) {
  add(["text", "link", "link-hover", "link-broken"], [["surface", `callout-${callout}-bg`], ["surface-raised", `callout-${callout}-bg`], ["surface", `callout-${callout}-bg`, `callout-${callout}-bg`]]);
  add(["focus-ring"], [["surface", `callout-${callout}-bg`]], 3);
  add([callout === "note" ? "accent" : callout], [["surface", `callout-${callout}-bg`], ["surface"]], 3);
}
add(["link"], [["surface"], ["surface-raised"], ["surface-chrome"]]);
add(["accent"], [["surface-hover"]]);
add(["warning"], [["surface", "warning-soft"]]);
add(["danger"], [["surface", "danger-soft"]]);
add(["link-broken"], [["surface"], ["surface-raised"]]);
add(["primary-text"], [["surface", "primary-bg"], ["surface-raised", "primary-bg"], ["surface", "danger-soft", "primary-bg"]]);
add(["text", "link"], [["table-header-bg"]]);
const syntax = ["code-text", "syntax-keyword", "syntax-string", "syntax-comment", "syntax-literal", "syntax-title", "syntax-attr"];
add(syntax, [["code-bg"], ["surface"], ["surface-raised"], ["surface", "diff-add-bg"], ["surface", "diff-del-bg"]]);
add(["text-muted"], [["surface", "diff-add-bg"], ["surface", "diff-del-bg"]]);
add(["selection-text"], [["surface", "selection-bg"], ["surface-raised", "selection-bg"]]);
const statuses = ["active", "draft", "review", "archived", "deprecated"].map((s) => `status-${s}`);
add(statuses, [["surface"], ["surface-raised"]]);
add(statuses, [["surface"], ["surface-hover"]], 3);
add(["focus-ring"], [["surface"], ["surface-raised"], ["surface-chrome"], ["surface-hover"], ["surface-raised", "accent-soft"], ["surface-chrome", "nav-active-bg"]], 3);
add(["text-muted", "text-faint"], [["surface"], ["surface-chrome"], ["surface-hover"], ["surface-chrome", "nav-active-bg"]], 3);

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
