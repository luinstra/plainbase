export type Color = [number, number, number, number];
export type Tokens = Record<string, string>;

export function stripComments(css: string): string { return css.replace(/\/\*[\s\S]*?\*\//g, ""); }
export function topLevelBlocks(css: string): Array<{ selector: string; body: string }> {
  css = stripComments(css);
  const blocks: Array<{ selector: string; body: string }> = [];
  let depth = 0;
  let start = 0;
  let bodyStart = 0;
  let selector = "";
  let quote = "";
  for (let i = 0; i < css.length; i++) {
    const char = css[i];
    if (char === "\\") { i++; continue; }
    if (quote) { if (char === quote) quote = ""; continue; }
    if (char === '"' || char === "'") { quote = char; continue; }
    if (char === "{" && depth++ === 0) {
      selector = css.slice(start, i).trim();
      bodyStart = i + 1;
    } else if (char === "}") {
      if (--depth < 0) throw new Error("unbalanced CSS block");
      if (depth === 0) { blocks.push({ selector, body: css.slice(bodyStart, i) }); start = i + 1; }
    } else if (char === ";" && depth === 0) start = i + 1;
  }
  if (depth || quote) throw new Error("unbalanced CSS block");
  return blocks;
}
export function declarations(css: string): Tokens {
  const entries = [...css.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]);
  if (!entries.length) throw new Error("empty declarations");
  return Object.fromEntries(entries);
}
export function expand(value: string, tokens: Tokens, seen: string[] = []): string {
  if (seen.length > 64) throw new Error("excessive recursion");
  return value.replace(/var\((--[\w-]+)\)/g, (_, key: string) => {
    if (!(key in tokens)) throw new Error(`unknown ${key}`);
    if (seen.includes(key)) throw new Error(`cycle ${key}`);
    return expand(tokens[key], tokens, [...seen, key]);
  });
}
export function split(value: string): string[] {
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
export function parse(value: string): Color {
  value = value.trim();
  const opaque = value.match(/^rgb\(from (.+) r g b \/ 1\)$/);
  if (opaque) {
    const channels = parse(opaque[1]);
    return [channels[0], channels[1], channels[2], 1];
  }
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
export function color(tokens: Tokens, name: string): Color { return parse(expand(`var(--pb-${name})`, tokens)); }
export function over(top: Color, base: Color): Color {
  if (base[3] !== 1) throw new Error("base must be opaque");
  return [0, 1, 2].map((i) => top[i] * top[3] + base[i] * (1 - top[3])).concat(1) as Color;
}
export function contrast(a: Color, b: Color): number {
  const luminance = (c: Color) => c.slice(0, 3).map((v) => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)
    .reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
  const values = [luminance(over(a, b)), luminance(b)].sort((x, y) => x - y);
  return (values[1] + 0.05) / (values[0] + 0.05);
}
export function background(tokens: Tokens, stack: string[]): Color {
  const [base, ...layers] = stack.map((name) => color(tokens, name));
  if (!base || base[3] !== 1) throw new Error("missing opaque base");
  return layers.reduce((result, layer) => over(layer, result), base);
}
export const rows: Array<[string, string[], number]> = [];
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
