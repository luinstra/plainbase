// @vitest-environment node
import { readFileSync, readdirSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { declarations, stripComments, topLevelBlocks } from "./contrastResolver";
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
function bridge(source: string, key: string, token: string) { property(source, "@theme inline", key, `var(${token})`); }
function radii(source: string) {
  const values = [...strip(source).matchAll(/\bborder-radius\s*:\s*([^;}]+)/g)].map((match) => match[1].trim());
  expect(values.length).toBeGreaterThan(0);
  const allowed = /^(?:var\(--pb-radius(?:-(?:xs|sm|md|lg|xl|2xl|3xl))?\)|50%|20px|inherit)$/;
  expect(values.filter((value) => !allowed.test(value))).toEqual([]);
}
function hoverInk(source: string) {
  expect(rule(source, ".pb-discussion-card-link:hover")).not.toMatch(/(?:^|;)\s*color\s*:/);
}
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

const cardInput = "--pb-theme-card-foreground";
const cardAliases = ["--pb-card-foreground", "--pb-code-text", "--pb-diagram-node-text"];
const cardMembers = new Set([cardInput, ...cardAliases]);
const cssVar = (name: string) => ["var(", name, ")"].join("");
const definitions = ["generic", "light", "dark"].map((name) => readFileSync(`src/styles/themes/${name}.css`, "utf8"));
function inkAliases(sources: string[]): string[] {
  const found = new Set<string>();
  for (const source of sources) for (const { body } of topLevelBlocks(source)) {
    const graph = Object.fromEntries(Object.entries(declarations(body)).map(([name, value]) =>
      [name, [...value.matchAll(/var\((--[\w-]+)/g)].map((match) => match[1])]));
    const reachesCard = (name: string, seen = new Set<string>()): boolean => {
      if (name === cardInput) return true;
      if (seen.has(name)) return false;
      return (graph[name] ?? []).some((next) => reachesCard(next, new Set([...seen, name])));
    };
    for (const name of Object.keys(graph)) {
      if (name.startsWith("--pb-") && !name.startsWith("--pb-theme-") && reachesCard(name)) found.add(name);
    }
  }
  return [...found].sort();
}
type StyleBlock = { selector: string; own: string[]; ancestors: string[] };
function styleBlocks(source: string): StyleBlock[] {
  const blocks: StyleBlock[] = [];
  const walk = (text: string, selector = "", ancestors: string[] = []) => {
    const own: string[] = [];
    if (selector) blocks.push({ selector, own, ancestors });
    let depth = 0;
    let start = 0;
    let bodyStart = 0;
    let child = "";
    let quote = "";
    for (let i = 0; i < text.length; i++) {
      const char = text[i];
      if (char === "\\") { i++; continue; }
      if (quote) { if (char === quote) quote = ""; continue; }
      if (char === '"' || char === "'") { quote = char; continue; }
      if (char === "{" && depth++ === 0) {
        child = text.slice(start, i).trim().replace(/\s+/g, " ");
        bodyStart = i + 1;
      } else if (char === "}") {
        if (--depth < 0) throw new Error("unbalanced CSS block");
        if (depth === 0) {
          walk(text.slice(bodyStart, i), child, selector ? [...ancestors, selector] : ancestors);
          start = i + 1;
        }
      } else if (char === ";" && depth === 0) { own.push(text.slice(start, i)); start = i + 1; }
    }
    if (depth || quote) throw new Error("unbalanced CSS block");
    own.push(text.slice(start));
  };
  walk(stripComments(source));
  return blocks;
}
function stylesheetConsumers(source: string, members: ReadonlySet<string> = cardMembers): string[] {
  return styleBlocks(source).flatMap(({ selector, own }) => own.flatMap((segment) => {
    const declaration = segment.match(/^\s*([\w-]+)\s*:\s*([\s\S]+)$/);
    if (!declaration) return [];
    return [...declaration[2].matchAll(/--[a-z0-9-]*[a-z0-9]/g)]
      .filter(([name]) => members.has(name)).map(([name]) => `${selector} | ${declaration[1]} | ${name}`);
  })).sort();
}
// Runtime-assembled role names evade this source heuristic. A // in JSX text or a regex literal
// can hide the rest of a line; an apostrophe in JSX text, such as Don't, can desynchronise quotes.
function stripScriptComments(source: string, html: boolean): string {
  let result = "";
  let quote = "";
  for (let i = 0; i < source.length; i++) {
    const char = source[i];
    if (quote) {
      result += char;
      if (char === "\\") result += source[++i] ?? "";
      else if (char === quote) quote = "";
      continue;
    }
    if (char === '"' || char === "'" || char === "`") { quote = char; result += char; continue; }
    let end: number | undefined;
    if (source.startsWith("/*", i)) {
      const close = source.indexOf("*/", i + 2);
      end = close < 0 ? source.length : close + 2;
    } else if (source.startsWith("//", i)) {
      const close = source.indexOf("\n", i + 2);
      end = close < 0 ? source.length : close;
    } else if (html && source.startsWith("<!--", i)) {
      const close = source.indexOf("-->", i + 4);
      end = close < 0 ? source.length : close + 3;
    }
    if (end === undefined) result += char;
    else { result += source.slice(i, end).replace(/[^\n]/g, " "); i = end - 1; }
  }
  return result;
}
function scriptConsumers(file: string, source: string, members: ReadonlySet<string> = cardMembers): string[] {
  return [...stripScriptComments(source, file.endsWith(".html")).matchAll(/--[a-z0-9-]*[a-z0-9]/g)]
    .filter(([name]) => members.has(name)).map(([name]) => `${file} | ${name}`).sort();
}
function sourceFiles(directory = "src"): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const file = `${directory}/${entry.name}`;
    return entry.isDirectory() ? sourceFiles(file) : entry.isFile() ? [file] : [];
  }).sort();
}
function consumerDiff(actual: string[], expected: string[]): string[] {
  const remaining = [...expected];
  const added: string[] = [];
  for (const entry of actual) {
    const index = remaining.indexOf(entry);
    if (index < 0) added.push(`+ ${entry}`);
    else remaining.splice(index, 1);
  }
  return [...added, ...remaining.map((entry) => `- ${entry}`)].sort();
}

describe("card ink consumer checks", () => {
  it("detects an additional transitive alias", () => {
    const extra = ["--pb-extra-ink: ", cssVar("--pb-code-text"), ";"].join("");
    const changed = definitions.map((source, index) => index ? source : source.replace("{", `{ ${extra}`));
    expect(consumerDiff(inkAliases(changed), cardAliases)).toEqual(["+ --pb-extra-ink"]);
  });
  const inkDeclaration = ["color: ", cssVar("--pb-code-text"), ";"].join("");
  it.each([
    ["an extra rule", `.extra { ${inkDeclaration} }`, ".extra | color | --pb-code-text"],
    ["a rule inside media", `@media (min-width: 1px) { .inside { ${inkDeclaration} } }`, ".inside | color | --pb-code-text"],
    ["a declaration before a child rule", `.outer { ${inkDeclaration} &:hover { opacity: 1; } }`, ".outer | color | --pb-code-text"],
    ["a declaration after a child rule", `.outer { &:hover { opacity: 1; } ${inkDeclaration} }`, ".outer | color | --pb-code-text"],
    ["a declaration inside a child rule", `.outer { &:hover { ${inkDeclaration} } }`, "&:hover | color | --pb-code-text"],
    ["a theme key", ["@theme inline { --color-extra: ", cssVar("--pb-diagram-node-text"), "; }"].join(""),
      "@theme inline | --color-extra | --pb-diagram-node-text"],
    ["a declaration after quoted braces", `.quoted { content: "\\\"{;}"; ${inkDeclaration} }`, ".quoted | color | --pb-code-text"],
  ])("finds %s", (_name, source, entry) => {
    expect(consumerDiff(stylesheetConsumers(source), [])).toEqual([`+ ${entry}`]);
  });
  it("excludes child preludes and comments from own declarations", () => {
    const source = `.outer { &[data-token="--pb-code-text"] { opacity: 1; } /* ${inkDeclaration} */ }`;
    expect(consumerDiff(stylesheetConsumers(source), [])).toEqual([]);
  });
  it("detects a removed expected consumer", () => {
    const expected = [".removed | color | --pb-code-text"];
    expect(consumerDiff(stylesheetConsumers(".removed { padding: 0; }"), expected)).toEqual([`- ${expected[0]}`]);
  });
  it.each([
    ["an inline style", ['<p style={{ color: "', cssVar("--pb-card-foreground"), '" }} />'].join(""), "--pb-card-foreground"],
    ["an arbitrary utility", ['<p className="text-[', cssVar("--pb-code-text"), ']" />'].join(""), "--pb-code-text"],
    ["a member after a quoted URL", ['const url = "https://example.invalid"; const ink = "',
      cssVar("--pb-code-text"), '";'].join(""), "--pb-code-text"],
    ["a member after a template URL", ['const url = `https://example.invalid`; const ink = "',
      cssVar("--pb-code-text"), '";'].join(""), "--pb-code-text"],
    ["a member after an escaped quote", ['const text = "escaped \\" https://example.invalid"; const ink = "',
      cssVar("--pb-code-text"), '";'].join(""), "--pb-code-text"],
  ])("finds %s in script text", (_name, source, member) => {
    expect(consumerDiff(scriptConsumers("extra.tsx", source), [])).toEqual([`+ extra.tsx | ${member}`]);
  });
  it("ignores script and HTML comments and longer token names", () => {
    const source = ["/* ", cssVar("--pb-code-text"), " */ // ", cssVar("--pb-card-foreground"),
      "\n<!-- ", cssVar("--pb-diagram-node-text"), ' -->\nconst other = "--pb-code-text-extra";'].join("");
    expect(consumerDiff(scriptConsumers("index.html", source), [])).toEqual([]);
  });
});

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
  it("card ink reaches only its known consumers", () => {
    const files = sourceFiles();
    const stylesheetFiles = files.filter((file) => file.endsWith(".css") && !file.startsWith("src/styles/themes/"));
    const scriptFiles = [...files.filter((file) => /\.tsx?$/.test(file)
      && !file.split("/").includes("__tests__") && !/\.test\.tsx?$/.test(file) && file !== "src/test-setup.ts"), "index.html"].sort();
    const aliases = inkAliases(definitions);
    const members = new Set([cardInput, ...aliases]);
    const stylesheets = stylesheetFiles.flatMap((file) => stylesheetConsumers(readFileSync(file, "utf8"), members)).sort();
    const scripts = scriptFiles.flatMap((file) => scriptConsumers(file, readFileSync(file, "utf8"), members)).sort();
    const blocks = stylesheetFiles.flatMap((file) => styleBlocks(readFileSync(file, "utf8")).map((block) => ({ file, ...block })));
    process.stdout.write("Card ink source inventory\n" + JSON.stringify({
      aliases, stylesheets, scripts, stylesheetFiles, scriptPopulation: scriptFiles.length,
      scriptKnownMember: scriptFiles.includes("src/components/SearchPalette.tsx"),
      nestedStyleRules: blocks.filter((block) => block.ancestors.some((selector) => !selector.startsWith("@"))),
      atRuleParents: [...new Set(blocks.flatMap((block) => block.ancestors))].sort(),
      nestingAtRules: blocks.filter((block) => /^@(media|keyframes)\b/.test(block.selector))
        .map((block) => ({ file: block.file, selector: block.selector })),
      wideMediaChildren: blocks.filter((block) => block.ancestors.includes("@media (min-width: 1280px)"))
        .map((block) => block.selector).sort(),
    }, null, 2) + "\n");
    expect.soft(aliases, `aliases: ${JSON.stringify(aliases)}`).toEqual(cardAliases);
    expect.soft(stylesheetFiles, "application stylesheet population").toContain("src/styles/app.css");
    const cardSelectors = [
      ".pb-discussion-card, .pb-discussion-evidence, .pb-discussion-notice",
      ":is(.pb-discussion-card, .pb-discussion-evidence, .pb-discussion-notice, .pb-discussion-list-card, .pb-new-dialog) .text-ink",
      ".pb-discussion-list-card", ".pb-discussion-card-quote",
      ":is(.pb-discussion-card, .pb-discussion-list-card) .pb-discussion-author",
      ".pb-new-dialog", ".pb-new-location", ".pb-new-title", ".pb-new-field",
    ];
    const expectedStylesheets = [...cardSelectors.map((selector) => `${selector} | color | --pb-card-foreground`),
      ".pb-prose pre code | color | --pb-code-text", ".pb-diff-code | color | --pb-code-text"].sort();
    expect.soft(stylesheets, `stylesheets: ${JSON.stringify(stylesheets)}`).toEqual(expectedStylesheets);
    expect.soft(scriptFiles.length, "production script and markup population").toBeGreaterThan(50);
    expect.soft(scriptFiles, "known production script").toContain("src/components/SearchPalette.tsx");
    expect.soft(scripts, `scripts: ${JSON.stringify(scripts)}`).toEqual([
      "src/components/EditorPage.tsx | --pb-code-text", "src/lib/mermaid.ts | --pb-diagram-node-text",
    ]);
  });
  it.each(Object.entries(aliases))("registers %s inside @theme inline", (name, token) => bridge(css, `--color-${name}`, `--pb-${token}`));
  it("rejects missing, external and misbound aliases", () => {
    const valid = "@theme inline { --color-chrome: var(--pb-surface-chrome); }";
    bridge(valid, "--color-chrome", "--pb-surface-chrome");
    for (const broken of ["@theme inline { --color-other: var(--pb-surface); }", valid.replace("@theme inline", ":root"), valid.replace("surface-chrome", "surface-raised")]) expect(() => bridge(broken, "--color-chrome", "--pb-surface-chrome")).toThrow();
  });
  const fontBridges = [["--font-sans", "--pb-font-sans"], ["--font-mono", "--pb-font-mono"]];
  it.each(fontBridges)("registers %s inside @theme inline", (key, token) => bridge(css, key, token));
  it("bridge checker rejects literal stacks, wrong tokens and keys outside @theme inline", () => {
    for (const [key, token] of fontBridges) {
      const valid = `@theme inline { ${key}: var(${token}); }`;
      bridge(valid, key, token);
      const other = token === "--pb-font-sans" ? "--pb-font-mono" : "--pb-font-sans";
      for (const broken of [valid.replace(`var(${token})`, "serif"), valid.replace(token, other), valid.replace("@theme inline", ":root")]) {
        expect(() => bridge(broken, key, token)).toThrow();
      }
    }
  });
  it("the editor uses the mono font", () => {
    const source = strip(read("EditorPage"));
    for (const selector of ["content", "scroller"]) {
      const matches = [...source.matchAll(new RegExp(`"\\.cm-${selector}"\\s*:\\s*\\{([^{}]*)\\}`, "g"))];
      expect.soft(matches, `.cm-${selector}`).toHaveLength(1);
      expect.soft(matches[0]?.[1], `.cm-${selector}`).toMatch(/(?:^|,)\s*fontFamily:\s*"var\(--font-mono\)"/);
    }
  });
  const radiusBridges = [["--radius", "--pb-radius"], ["--radius-md", "--pb-radius-md"], ["--radius-xl", "--pb-radius-2xl"]];
  it.each(radiusBridges)("registers %s inside @theme inline", (key, token) => bridge(css, key, token));
  it("decorative radii follow the radius scale", () => radii(css));
  it("radius checker rejects literals and calculations", () => {
    const values = ["", "-xs", "-sm", "-md", "-lg", "-xl", "-2xl", "-3xl"].map((step) => `var(--pb-radius${step})`);
    values.push("50%", "20px", "inherit");
    const valid = values.map((value) => `.a { border-radius: ${value}; }`).join("\n");
    radii(valid + " /* border-radius: 4px; */");
    radii(".a { border-radius: var(--pb-radius) }");
    for (const value of ["4px", ".375rem", "calc(4px)"]) {
      expect(() => radii(`${valid}\n.a { border-radius: ${value}; }`)).toThrow();
    }
    expect(() => radii(".a { padding: 1rem; }")).toThrow();
  });
  it("card link hover leaves ink to its descendants", () => {
    hoverInk(css);
    expect(() => hoverInk(".pb-discussion-card-link:hover { background: var(--pb-surface-hover); color: var(--pb-text); }")).toThrow();
  });
  const primary = ["border-primary-edge", "bg-primary", "text-primary-ink"];
  it.each([["EditorPage", "data-pb-save"], ["EditorPage", "data-pb-save-as-new"], ["NewPageDialog", "data-pb-new-create"]])("wires %s %s primary styles", (owner, hook) => classes(read(owner), hook, primary, ["border-edge", "border-accent", "bg-accent", "text-accent-contrast"]));
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
    [".pb-discussion-card, .pb-discussion-evidence, .pb-discussion-notice", "color", "var(--pb-card-foreground)"],
    [".pb-discussion-list-card", "color", "var(--pb-card-foreground)"],
    [".pb-discussion-card-quote", "color", "var(--pb-card-foreground)"],
    [":is(.pb-discussion-card, .pb-discussion-list-card) .pb-discussion-author", "color", "var(--pb-card-foreground)"],
    [".pb-discussion-card-link:hover :is(.pb-discussion-card-quote, .pb-discussion-author)", "color", "var(--pb-text)"],
    [".pb-discussion-history .pb-discussion-evidence", "color", "inherit"],
    [".pb-new-dialog", "color", "var(--pb-card-foreground)"],
    [".pb-new-title", "color", "var(--pb-card-foreground)"],
    [".pb-new-location", "color", "var(--pb-card-foreground)"],
    [".pb-new-field", "color", "var(--pb-card-foreground)"],
    [".pb-template-card:has(:checked)", "color", "var(--pb-text)"],
    [":is(.pb-discussion-card, .pb-discussion-evidence, .pb-discussion-notice, .pb-discussion-list-card, .pb-new-dialog) .text-ink", "color", "var(--pb-card-foreground)"],
    [".pb-discussion-history .pb-discussion-evidence .text-ink", "color", "var(--pb-text)"],
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
