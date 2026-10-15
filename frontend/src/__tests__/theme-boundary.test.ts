// @vitest-environment node
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
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

describe("candidate theme boundaries", () => {
  it("theme candidates and fixtures are not app imports", () => {
    const applicationFiles = filesUnder(path.join(frontendRoot, "src"))
      .filter((file) => /\.(ts|tsx|html)$/.test(file))
      .filter((file) => !path.relative(frontendRoot, file).split(path.sep).includes("__tests__"));
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
    expect([...new Set(cssImports(path.join(frontendRoot, "src", "styles")))].sort()).toEqual([
      "./fonts.css",
      "./tokens.css",
      "tailwindcss",
    ]);
    expect(cssImports(path.join(frontendRoot, "src", "styles", "themes"))).toEqual([]);
    const stylesheetLinks = [...readFileSync(indexFile, "utf8").matchAll(/<link\b[^>]*>/gi)]
      .filter(([tag]) => {
        const relation = tag.match(/\brel\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i);
        return relation && (relation[1] ?? relation[2] ?? relation[3]).toLowerCase().split(/\s+/).includes("stylesheet");
      })
      .map(([tag]) => tag);
    expect(stylesheetLinks).toEqual([]);
  });

  it("theme candidates and fixtures are not public assets", () => {
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

  it("the contract is unreleased", () => {
    const tintedRoot = path.join(repositoryRoot, "contrib", "tinted");
    expect(readFileSync(path.join(tintedRoot, "README.md"), "utf8").toLowerCase()).toContain("unreleased");
    for (const file of readdirSync(path.join(tintedRoot, "generated")).sort()) {
      const output = readFileSync(path.join(tintedRoot, "generated", file), "utf8");
      const outputHeader = output.match(/^\s*\/\*([\s\S]*?)\*\//)?.[1] ?? "";
      expect(outputHeader, file).toContain("Unreleased");
    }
    expect(existsSync(path.join(repositoryRoot, "docs", "theming.md"))).toBe(false);
    const releaseNotes = readFileSync(path.join(repositoryRoot, "docs", "release-notes", "unreleased.md"), "utf8");
    expect(releaseNotes).not.toContain("--pb-theme");
    const generic = readFileSync(path.join(frontendRoot, "src", "styles", "themes", "generic.css"), "utf8");
    const header = generic.match(/^\s*\/\*([\s\S]*?)\*\//)?.[1] ?? "";
    expect(header.toLowerCase()).toContain("unreleased");
    for (const marker of ["--pb-theme-", "localStorage"]) expect(header).toContain(marker);
    expect(header).toMatch(/(?<![\w-])pb-theme(?![\w-])/);
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
