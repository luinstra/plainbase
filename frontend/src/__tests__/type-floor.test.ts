// @vitest-environment node
import { readFileSync, readdirSync } from "node:fs";
import { describe, expect, it } from "vitest";
const strip = (s: string) => s.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
const css = strip(readFileSync("src/styles/app.css", "utf8"));
const read = (name: string) => readFileSync(`src/components/${name}.tsx`, "utf8");
function rule(source: string, selector: string): string {
  const body = [...strip(source).matchAll(/([^{}]+)\{([^{}]*)\}/g)].find((m) => m[1].trim() === selector)?.[2];
  if (!body) throw new Error(`missing ${selector}`);
  return body;
}
function property(source: string, selector: string, name: string, value: string) {
  const props = Object.fromEntries([...rule(source, selector).matchAll(/([\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]));
  expect(props[name], `${selector} ${name}`).toBe(value);
}
function sizes(source: string, kind: "css" | "tsx") {
  const pattern = kind === "css" ? /font-size:\s*((?:\d*\.)?\d+)(px|rem)\b/g : /\btext-\[((?:\d*\.)?\d+)(px|rem)\]/g;
  return [...strip(source).matchAll(pattern)].map((m) => Number(m[1]) * (m[2] === "rem" ? 16 : 1));
}
function floor(source: string, kind: "css" | "tsx") { expect(sizes(source, kind).filter((n) => n < 12)).toEqual([]); }
function controls(source: string, hook: string): string[][] {
  const found: string[][] = [];
  for (const opening of source.matchAll(/<[A-Za-z][\w.]*(?=[\s/>])/g)) {
    let quote = "";
    let braces = 0;
    let end = opening.index + opening[0].length;
    for (; end < source.length; end++) {
      const char = source[end];
      if (quote) { if (char === quote && source[end - 1] !== "\\") quote = ""; }
      else if (char === '"' || char === "'" || char === "`") quote = char;
      else if (char === "{") braces++;
      else if (char === "}") braces--;
      else if (char === ">" && braces === 0) break;
    }
    const tag = source.slice(opening.index, end);
    if (!new RegExp(`\\s${hook}(?=[\\s=/>])`).test(tag)) continue;
    const match = tag.match(/\bclassName\s*=\s*"([^"]*)"/);
    if (!match) throw new Error(`nonliteral classes on ${hook}`);
    found.push(match[1].split(/\s+/));
  }
  if (!found.length) throw new Error(`missing ${hook}`);
  return found;
}
function classes(source: string, hook: string, required: string[], obsolete: string[] = []) {
  for (const values of controls(source, hook)) {
    for (const value of required) expect(values, `${hook}: ${value}`).toContain(value);
    for (const value of obsolete) expect(values, `${hook}: obsolete ${value}`).not.toContain(value);
  }
}
const aliases = { chrome: "surface-chrome", field: "surface-field", primary: "primary-bg", "primary-edge": "primary-border", "primary-ink": "primary-text" };
function alias(source: string, name: string, token: string) { property(source, "@theme inline", `--color-${name}`, `var(--pb-${token})`); }
function label(source: string, selector: string, size: number, weight?: number) {
  property(source, selector, "font-family", "var(--font-sans)");
  property(source, selector, "font-size", `${size}px`);
  if (weight) property(source, selector, "font-weight", String(weight));
  expect(rule(source, selector)).not.toMatch(/text-transform:\s*uppercase|letter-spacing:/);
}
function nativeSelection(source: string) {
  const matches = [...source.matchAll(/"\.cm-content ::selection"\s*:\s*\{([^{}]*)\}/g)];
  expect(matches).toHaveLength(1);
  const body = matches[0][1];
  expect(body).toMatch(/color:\s*"var\(--pb-selection-text\)"/);
  expect(body).toMatch(/backgroundColor:\s*"var\(--pb-selection-bg\)"/);
}

describe("type floor", () => {
  it("checks a nonempty CSS and TSX inventory", () => {
    expect(sizes(css, "css").length).toBeGreaterThan(30);
    const files = readdirSync("src/components").filter((f) => f.endsWith(".tsx"));
    expect(files.length).toBeGreaterThan(10);
    const source = files.map((f) => readFileSync(`src/components/${f}`, "utf8")).join("\n");
    floor(css, "css");
    floor(source, "tsx");
  });
  it("rejects decimal px/rem and variant-prefixed small type, ignoring comments", () => {
    floor(".a { font-size: 12px; } /* font-size: 9px; */", "css");
    floor('<p className="text-[.75rem] text-[12px]" />', "tsx");
    for (const source of ["font-size: 11.5px;", "font-size: 0.7rem;", "font-size: .7rem;"]) expect(() => floor(source, "css")).toThrow();
    for (const source of ['hover:text-[10.5px]', 'md:text-[0.7rem]', 'hover:text-[.7rem]']) expect(() => floor(source, "tsx")).toThrow();
  });
  it.each([[".pb-search-grouplabel", 12, 600], [".pb-listing-label", 13, 600], [".pb-rail-head", 13, 600], [".pb-meta-key", 12, undefined], [".pb-avatar", 12, 600]] as const)("%s uses readable sans type", (selector, size, weight) => label(css, selector, size, weight));
  it("label checker detects wrong family, size, weight, casing and missing rules", () => {
    const valid = ".label { font-family: var(--font-sans); font-size: 13px; font-weight: 600; }";
    label(valid, ".label", 13, 600);
    for (const broken of [valid.replace("sans", "mono"), valid.replace("13px", "11px"), valid.replace("600", "400"), valid.replace("}", "text-transform: uppercase; }"), ""]) expect(() => label(broken, ".label", 13, 600)).toThrow();
  });
});
describe("semantic alias and consumer wiring", () => {
  it.each(Object.entries(aliases))("registers %s inside @theme inline", (name, token) => alias(css, name, token));
  it("rejects missing, external and misbound aliases", () => {
    const valid = "@theme inline { --color-chrome: var(--pb-surface-chrome); }";
    alias(valid, "chrome", "surface-chrome");
    for (const broken of ["@theme inline { --color-other: var(--pb-surface); }", valid.replace("@theme inline", ":root"), valid.replace("surface-chrome", "surface-raised")]) expect(() => alias(broken, "chrome", "surface-chrome")).toThrow();
  });
  const primary = ["border-primary-edge", "bg-primary", "text-primary-ink"];
  it.each(["data-pb-save", "data-pb-save-as-new", "data-pb-new-create"])("wires %s primary styles", (hook) => classes(read("EditorPage"), hook, primary, ["border-edge", "border-accent", "bg-accent", "text-accent-contrast"]));
  it("detects every wrong primary class and missing controls", () => {
    const valid = '<button data-pb-save className="border-primary-edge bg-primary text-primary-ink" />';
    classes(valid, "data-pb-save", primary);
    classes(valid.replace("data-pb-save", 'onClick={() => 1 > 0}\n data-pb-save'), "data-pb-save", primary);
    for (const value of primary) expect(() => classes(valid.replace(value, "obsolete"), "data-pb-save", primary)).toThrow();
    expect(() => classes("<div />", "data-pb-save", primary)).toThrow();
  });
  it("wires chrome, field, panel and footer", () => {
    classes(read("Shell"), "data-pb-header", ["bg-chrome"], ["bg-raised"]);
    classes(read("Shell"), "data-pb-search-trigger", ["bg-field"], ["bg-surface"]);
    classes(read("Sidebar"), "data-pb-sidebar", ["bg-chrome", "h-[calc(100vh-3.5rem)]", "w-[clamp(16rem,20vw,22rem)]"], ["bg-raised"]);
    classes(read("SearchPalette"), "data-pb-search-panel", ["bg-raised"]);
    classes(read("SearchPalette"), "data-pb-search-foot", ["font-sans", "text-xs"], ["font-mono", "text-[10.5px]"]);
    property(css, ".pb-search-foot b", "font-family", "var(--font-mono)");
  });
  const bindings = [
    ['.pb-sidebar [aria-current="page"]', "background", "var(--pb-nav-active-bg)"],
    ['.pb-sidebar [aria-current="page"]', "color", "var(--pb-text)"],
    ['.pb-sidebar [aria-current="page"]', "font-weight", "600"],
    [".pb-search [data-pb-search-active]", "--pb-text-faint", "var(--pb-text-muted)"],
    [".pb-diff-gutter", "color", "var(--pb-text-muted)"],
    ["::selection", "color", "var(--pb-selection-text)"],
    ["::placeholder", "color", "var(--pb-text-muted)"],
    ["::placeholder", "opacity", "1"],
  ];
  it.each(bindings)("binds %s %s", (selector, key, value) => property(css, selector, key, value));
  it("property checker rejects every omitted and misbound declaration", () => {
    for (const [selector, key, value] of bindings) {
      const valid = `${selector} { ${key}: ${value}; }`;
      property(valid, selector, key, value);
      expect(() => property(`${selector} { unrelated: value; }`, selector, key, value)).toThrow();
      expect(() => property(valid.replace(value, "wrong"), selector, key, value)).toThrow();
    }
  });
  it.each([["note", "note"], ["tip", "note"], ["important", "warning"], ["warning", "warning"], ["caution", "danger"]])("%s callout uses opaque %s background", (kind, token) => {
    const body = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].find((m) => (kind === "note" || kind === "tip" ? m[1].trim() === ".pb-prose .pb-callout" : m[1].includes(`[data-pb-callout="${kind}"]`)) && /background:/.test(m[2]));
    expect(body).toBeDefined();
    expect(body![2]).toContain(`background: var(--pb-callout-${token}-bg)`);
  });
  it("native editor selection explicitly replaces inherited ink", () => nativeSelection(read("EditorPage")));
  it("native selection checker rejects omitted foreground", () => {
    const valid = 'const x = { ".cm-content ::selection": { backgroundColor: "var(--pb-selection-bg)", color: "var(--pb-selection-text)" } };';
    nativeSelection(valid);
    expect(() => nativeSelection(valid.replace(', color: "var(--pb-selection-text)"', ""))).toThrow();
  });
});
