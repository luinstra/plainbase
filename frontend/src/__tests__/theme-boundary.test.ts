// @vitest-environment node
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseSync, Visitor } from "vite";
import { describe, expect, it } from "vitest";

const ownFile = fileURLToPath(import.meta.url);
const frontendRoot = path.resolve(path.dirname(ownFile), "../..");
const repositoryRoot = path.resolve(frontendRoot, "..");

function filesUnder(directory: string): string[] {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) return filesUnder(full);
    return entry.isFile() ? [full] : [];
  });
}

function cssImports(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".css"))
    .flatMap((entry) => {
      const source = readFileSync(path.join(directory, entry.name), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
      return [...source.matchAll(/@import\s+([^;]+);/g)].map((match) => {
        const target = match[1].match(/^(?:url\(\s*)?["']([^"']+)["']/);
        return target ? target[1] : match[1].trim();
      });
    });
}

function quotedEnd(source: string, start: number): number {
  const quote = source[start];
  if (quote === "`") return templateEnd(source, start);
  for (let index = start + 1; index < source.length; index++) {
    if (source[index] === "\\") index++;
    else if (source[index] === quote) return index + 1;
  }
  return source.length;
}

function interpolationEnd(source: string, start: number): number {
  let depth = 1;
  for (let index = start; index < source.length; index++) {
    const character = source[index];
    if (character === '"' || character === "'" || character === "`") {
      index = quotedEnd(source, index) - 1;
      continue;
    }
    const end = commentEnd(source, index);
    if (end !== undefined) {
      index = end - 1;
      continue;
    }
    if (character === "{") depth++;
    else if (character === "}" && --depth === 0) return index;
  }
  return source.length;
}

function templateEnd(source: string, start: number, onExpression?: (expression: string) => void): number {
  for (let index = start + 1; index < source.length; index++) {
    if (source[index] === "\\") index++;
    else if (source[index] === "`") return index + 1;
    else if (source.startsWith("${", index)) {
      const expressionStart = index + 2;
      const end = interpolationEnd(source, expressionStart);
      onExpression?.(source.slice(expressionStart, end));
      index = end;
    }
  }
  return source.length;
}

function commentEnd(source: string, start: number): number | undefined {
  if (source.startsWith("//", start)) {
    const end = source.indexOf("\n", start + 2);
    return end < 0 ? source.length : end;
  }
  if (source.startsWith("/*", start)) {
    const end = source.indexOf("*/", start + 2);
    return end < 0 ? source.length : end + 2;
  }
  return undefined;
}

function firstArgument(source: string, start: number): string {
  let depth = 0;
  for (let index = start; index < source.length; index++) {
    const character = source[index];
    if (character === '"' || character === "'" || character === "`") {
      index = quotedEnd(source, index) - 1;
      continue;
    }
    const end = commentEnd(source, index);
    if (end !== undefined) {
      index = end - 1;
      continue;
    }
    if (character === "(" || character === "[" || character === "{") depth++;
    else if (character === ")" || character === "]" || character === "}") {
      if (depth === 0) return source.slice(start, index).trim();
      depth--;
    } else if (character === "," && depth === 0) return source.slice(start, index).trim();
  }
  return source.slice(start).trim();
}

function importSpecifiers(source: string, filename = "module.tsx"): string[] {
  const parsed = parseSync(filename, source, { preserveParens: false });
  if (parsed.errors.length > 0) throw new Error(`Cannot scan module imports in ${filename}: ${parsed.errors[0].message}`);
  const specifiers: string[] = [];
  new Visitor({
    ImportDeclaration(node) { specifiers.push(node.source.value); },
    ExportAllDeclaration(node) { specifiers.push(node.source.value); },
    ExportNamedDeclaration(node) {
      if (node.source) specifiers.push(node.source.value);
    },
    ImportExpression(node) {
      const target = node.source;
      if (target.type === "Literal" && typeof target.value === "string") specifiers.push(target.value);
      else if (target.type === "TemplateLiteral" && target.expressions.length === 0) {
        const value = target.quasis[0].value.cooked;
        if (value !== null) specifiers.push(value);
      }
    },
  }).visit(parsed.program);
  return specifiers;
}

function writeArguments(source: string): string[] {
  const argumentsFound: string[] = [];
  for (let index = 0; index < source.length; index++) {
    const character = source[index];
    if (character === "`") {
      index = templateEnd(source, index, (expression) => argumentsFound.push(...writeArguments(expression))) - 1;
      continue;
    }
    if (character === '"' || character === "'") {
      index = quotedEnd(source, index) - 1;
      continue;
    }
    const end = commentEnd(source, index);
    if (end !== undefined) {
      index = end - 1;
      continue;
    }
    if (character !== "w" || (index > 0 && /[\w$]/.test(source[index - 1]))) continue;
    const call = source.slice(index).match(/^writeFile(?:Sync)?\s*\(/);
    if (call) argumentsFound.push(firstArgument(source, index + call[0].length));
  }
  return argumentsFound;
}

function targetsFrozenFixtures(argument: string): boolean {
  if (/(?:^|[\\/"'\x60])contrib[\\/]/.test(argument) || /["'\x60]contrib["'\x60]/.test(argument)) return true;
  const segmentPairs = ["e2e", "__tests__"];
  return segmentPairs.some((segment) => {
    const directPath = new RegExp(`\\b${segment}[\\\\/]+fixtures\\b`);
    const joinedPath = new RegExp(`["'\x60]${segment}["'\x60]\\s*,\\s*["'\x60]fixtures["'\x60]`);
    return directPath.test(argument) || joinedPath.test(argument);
  });
}

describe("theme source boundaries", () => {
  it("theme source and fixtures are not app imports", () => {
    const applicationFiles = filesUnder(path.join(frontendRoot, "src"))
      .filter((file) => /\.(ts|tsx|html)$/.test(file))
      .filter((file) => !/\.test\.tsx?$/.test(file))
      .filter((file) => !path.relative(frontendRoot, file).split(path.sep).includes("__tests__"));
    expect.soft(applicationFiles).toContain(path.join(frontendRoot, "src", "lib", "mermaid.ts"));
    expect.soft(applicationFiles).not.toContain(path.join(frontendRoot, "src", "lib", "mermaid.test.ts"));
    const indexFile = path.join(frontendRoot, "index.html");
    const forbidden = ["styles/themes/", "__tests__/fixtures", "e2e/", "contrib/", "__tests__/tinted"];
    const imported = [...applicationFiles, indexFile].flatMap((file) => {
      const source = readFileSync(file, "utf8");
      const fragments = forbidden.filter((fragment) => source.includes(fragment));
      if (/\b(?:from\s*|import\s*(?:\(\s*)?)["']yaml["']/.test(source)) fragments.push("yaml-import");
      return fragments.map((fragment) => ({
        file: path.relative(frontendRoot, file),
        fragment,
      }));
    });
    expect(imported).toEqual([]);
    expect.soft([...new Set(cssImports(path.join(frontendRoot, "src", "styles")))].sort()).toEqual([
      "./fonts.css",
      "tailwindcss",
    ]);
    expect.soft(existsSync(path.join(frontendRoot, "src", "styles", "tokens.css"))).toBe(false);
    expect(cssImports(path.join(frontendRoot, "src", "styles", "themes"))).toEqual([]);
    const html = readFileSync(indexFile, "utf8");
    const stylesheetLinks = [...html.matchAll(/<link\b[^>]*>/gi)]
      .filter(([tag]) => {
        const relation = tag.match(/\brel\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i);
        return relation && (relation[1] ?? relation[2] ?? relation[3]).toLowerCase().split(/\s+/).includes("stylesheet");
      });
    expect(stylesheetLinks).toHaveLength(1);
    const [appLink] = stylesheetLinks;
    expect(appLink[0]).toMatch(/\bhref\s*=\s*["']\/src\/styles\/app\.css["']/);
    expect(appLink[0]).not.toMatch(/\s(?:media|disabled)(?:\s|=|\/?>)/i);
    const bootstrap = html.match(/<script\b(?![^>]*\bsrc\s*=)[^>]*>[\s\S]*?<\/script>/i);
    expect(bootstrap).not.toBeNull();
    expect(appLink.index).toBeGreaterThanOrEqual(bootstrap!.index! + bootstrap![0].length);
    expect(appLink.index! + appLink[0].length).toBeLessThanOrEqual(html.indexOf("</head>"));
  });

  it("application modules import no stylesheets", () => {
    const offending = filesUnder(path.join(frontendRoot, "src"))
      .filter((file) => /\.(ts|tsx)$/.test(file))
      .filter((file) => !path.relative(frontendRoot, file).split(path.sep).includes("__tests__"))
      .filter((file) => importSpecifiers(readFileSync(file, "utf8"), file).some((specifier) => /\.css(?:\?|$)/.test(specifier)))
      .map((file) => path.relative(frontendRoot, file));
    expect(offending, `stylesheet imports in application modules: ${offending.join(", ")}`).toEqual([]);
  });

  it("tripwire: the import scan recognizes stylesheet specifiers and skips comments and strings", () => {
    const imports = [
      ['import sheet from "./a.css";', "./a.css"],
      ['export { sheet } from "./a.css";', "./a.css"],
      ['export * from "./a.css";', "./a.css"],
      ['export * as sheet from "./a.css";', "./a.css"],
      ['export type { Sheet } from "./a.css";', "./a.css"],
      ['import "./a.css";', "./a.css"],
      ['import("./a.css");', "./a.css"],
      ['import("./a.css?inline");', "./a.css?inline"],
      ['import { sheet } /* note */ from "./a.css";', "./a.css"],
      ['import(/* note */ "./a.css", { with: { type: "css" } });', "./a.css"],
      ['import(`./a.css`);', "./a.css"],
      ['`${import("./a.css")}`', "./a.css"],
    ];
    for (const [source, specifier] of imports) {
      expect(importSpecifiers(source), source).toEqual([specifier]);
      expect(importSpecifiers(source).filter((value) => /\.css(?:\?|$)/.test(value)), source).toEqual([specifier]);
    }
    for (const source of [
      '// import "./a.css";',
      '// export * from "./a.css";',
      '/* import("./a.css"); */',
      'const example = \'import "./a.css";\';',
      'const example = `import "./a.css";`;',
      'loader.import("./a.css");',
      'import("./a.css" + suffix);',
      'import(`./${name}.css`);',
    ]) expect(importSpecifiers(source), source).toEqual([]);
  });

  it("tripwire: a quote in a regular expression does not hide a later stylesheet import", () => {
    expect(importSpecifiers(`const pattern = /'/; import("./a.css");`)).toEqual(["./a.css"]);
    expect(importSpecifiers(`const pattern = /["']/; import("./a.css");`)).toEqual(["./a.css"]);
    expect(importSpecifiers(`const ratio = total / (await import("./a.css")).value / count;`)).toEqual(["./a.css"]);
  });

  it("theme source and fixtures are not public assets", () => {
    const assets = filesUnder(path.join(frontendRoot, "public"))
      .filter((file) => /\.(css|json)$/i.test(file))
      .map((file) => path.relative(frontendRoot, file));
    expect(assets).toEqual([]);
  });

  it("tripwire: no test writes into the frozen fixture directories", () => {
    for (const directory of ["e2e", "__tests__"]) {
      const argument = `path.join("${directory}", "fixtures", "x.json")`;
      const interpolatedCall = 'console.info(`${writeFileSync(' + argument + ', "")}`)';
      expect(writeArguments(interpolatedCall).filter(targetsFrozenFixtures)).toEqual([argument]);
    }
    const contribArgument = 'path.join("contrib", "tinted", "x")';
    expect(writeArguments('writeFileSync(' + contribArgument + ', "")').filter(targetsFrozenFixtures)).toEqual([contribArgument]);
    const testFiles = [
      ...filesUnder(path.join(frontendRoot, "src", "__tests__")),
      ...filesUnder(path.join(frontendRoot, "e2e")),
    ].filter((file) => file !== ownFile);
    const fixtureWrites = testFiles.flatMap((file) => writeArguments(readFileSync(file, "utf8"))
      .filter(targetsFrozenFixtures)
      .map((argument) => ({ file: path.relative(frontendRoot, file), argument })));
    // This string heuristic catches literal paths and joined segments. Dynamic paths can evade it.
    expect(fixtureWrites).toEqual([]);
  });

  it("the contract ships with its documentation", () => {
    const tintedRoot = path.join(repositoryRoot, "contrib", "tinted");
    expect.soft(existsSync(path.join(repositoryRoot, "docs", "theming.md")), "theming guide exists").toBe(true);
    const releaseNotes = readFileSync(path.join(repositoryRoot, "docs", "release-notes", "0.6.0.md"), "utf8");
    expect.soft(releaseNotes, "release notes name theme inputs").toContain("--pb-theme-");
    expect.soft(releaseNotes, "release notes link the guide").toContain("](https://github.com/luinstra/plainbase/blob/v0.6.0/docs/theming.md)");
    const readme = readFileSync(path.join(tintedRoot, "README.md"), "utf8").toLowerCase();
    expect.soft(readme, "Tinted README is released").not.toContain("unreleased");
    expect.soft(readme, "Tinted README names the contract").not.toContain("candidate");
    for (const file of readdirSync(path.join(tintedRoot, "generated")).sort()) {
      const output = readFileSync(path.join(tintedRoot, "generated", file), "utf8");
      const outputHeader = output.match(/^\s*\/\*([\s\S]*?)\*\//)?.[1] ?? "";
      expect.soft(outputHeader.toLowerCase(), `generated header ${file}`).not.toContain("unreleased");
    }
    const template = readFileSync(path.join(tintedRoot, "templates", "default.mustache"), "utf8");
    const templateHeader = template.match(/^\s*\/\*([\s\S]*?)\*\//)?.[1] ?? "";
    expect.soft(templateHeader.toLowerCase(), "Tinted template header").not.toContain("unreleased");
    for (const file of ["generic.css", "light.css", "dark.css"]) {
      const css = readFileSync(path.join(frontendRoot, "src", "styles", "themes", file), "utf8");
      const cssHeader = css.match(/^\s*\/\*([\s\S]*?)\*\//)?.[1] ?? "";
      expect.soft(cssHeader.toLowerCase(), `theme header ${file}`).not.toContain("unreleased");
    }
    const roster = JSON.parse(readFileSync(path.join(frontendRoot, "src", "__tests__", "fixtures", "theme", "roster.json"), "utf8")) as { description: string };
    expect.soft(roster.description.toLowerCase(), "roster description").not.toContain("unreleased");
    const frozen = JSON.parse(readFileSync(path.join(frontendRoot, "src", "__tests__", "fixtures", "theme", "generic-frozen.json"), "utf8")) as { notes: string[] };
    expect.soft(frozen.notes.join("\n").toLowerCase(), "generic frozen notes").not.toContain("unreleased");
    const helper = readFileSync(path.join(frontendRoot, "src", "__tests__", "themeCandidates.ts"), "utf8");
    const notes = helper.match(/notes:\s*\[([^\]]*)\]/)?.[1] ?? "unreleased";
    expect.soft(notes.toLowerCase(), "theme helper notes").not.toContain("unreleased");
    const generic = readFileSync(path.join(frontendRoot, "src", "styles", "themes", "generic.css"), "utf8");
    const header = generic.match(/^\s*\/\*([\s\S]*?)\*\//)?.[1] ?? "";
    for (const marker of ["--pb-theme-", "localStorage"]) expect.soft(header, `generic marker ${marker}`).toContain(marker);
    expect.soft(header, "generic display-mode marker").toMatch(/(?<![\w-])pb-theme(?![\w-])/);
  });

  it("contrib tooling is neither an app input nor an embedded resource", () => {
    const generated = readdirSync(path.join(repositoryRoot, "contrib", "tinted", "generated")).sort();
    const exposed = filesUnder(path.join(frontendRoot, "public"))
      .filter((file) => /\.(yaml|yml|mustache)$/i.test(file) || generated.includes(path.basename(file)))
      .map((file) => path.relative(frontendRoot, file)).sort();
    expect(exposed).toEqual([]);
    for (const file of [
      "server/build.gradle.kts", "Dockerfile", "frontend/vite.config.ts", "frontend/plugins/themeStylesheets.ts",
    ]) {
      expect(readFileSync(path.join(repositoryRoot, file), "utf8"), file).not.toMatch(/\bcontrib\b/);
    }
  });
});
