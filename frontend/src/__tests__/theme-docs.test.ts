// @vitest-environment node
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { declarations, topLevelBlocks } from "./contrastResolver";
import { genericCss, readFrozen, roster, selectors, type Freeze, type Input } from "./themeCandidates";

type Table = { columns: string[]; rows: string[][] };
type Difference = { name: string; expected: unknown; actual: unknown };
type Mapping = { variables: Record<string, string>; shadow: string };
type Inputs = Record<string, Input>;
type RejectedForm = { id: string; docs: string; witnesses: Record<string, string> };

const modes = ["light", "dark"] as const;
const displaySpaces: Record<string, string> = {
  srgb: "sRGB", hsl: "HSL", hwb: "HWB", lab: "Lab", lch: "LCH", oklab: "OKLab", oklch: "OKLCH", p3: "Display P3",
};

function optionalRead(file: string): string { return existsSync(file) ? readFileSync(file, "utf8") : ""; }
function guide(): string { return optionalRead("../docs/theming.md"); }
function code(value: string): string { return "`" + value + "`"; }
function escapePattern(value: string): string { return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }

function section(markdown: string, heading: string): string {
  const lines = markdown.split(/\r?\n/);
  const start = lines.indexOf(heading);
  if (start < 0) return "";
  const level = heading.match(/^#+/)![0].length;
  const end = lines.findIndex((line, index) => index > start && new RegExp(`^#{1,${level}} `).test(line));
  return lines.slice(start + 1, end < 0 ? undefined : end).join("\n");
}

function renderTable(table: Table): string {
  const row = (cells: string[]) => "| " + cells.map((cell) => cell.replaceAll("|", "\\|")).join(" | ") + " |";
  return [row(table.columns), row(table.columns.map(() => "---")), ...table.rows.map(row)].join("\n");
}

function readTable(markdown: string, heading: string): Table {
  const lines = section(markdown, heading).split("\n");
  const start = lines.findIndex((line) => line.trim().startsWith("|"));
  if (start < 0) return { columns: [], rows: [] };
  const tableLines: string[] = [];
  for (const line of lines.slice(start)) {
    if (!line.trim().startsWith("|")) break;
    tableLines.push(line.trim());
  }
  const cells = (line: string) => line.slice(1, -1).split(/(?<!\\)\|/).map((cell) => cell.trim().replaceAll("\\|", "|"));
  if (tableLines.length < 2 || !cells(tableLines[1]).every((cell) => /^:?-+:?$/.test(cell))) return { columns: [], rows: [] };
  return { columns: cells(tableLines[0]), rows: tableLines.slice(2).map(cells) };
}

function tableDifferences(actual: Table, expected: Table): Difference[] {
  const differences: Difference[] = [];
  if (JSON.stringify(actual.columns) !== JSON.stringify(expected.columns)) {
    differences.push({ name: "columns", expected: expected.columns, actual: actual.columns });
  }
  const expectedNames = expected.rows.map(([name]) => name);
  const actualNames = actual.rows.map(([name]) => name);
  for (const name of new Set([...expectedNames, ...actualNames])) {
    const wanted = expected.rows.filter(([key]) => key === name);
    const found = actual.rows.filter(([key]) => key === name);
    if (JSON.stringify(found) !== JSON.stringify(wanted)) differences.push({ name, expected: wanted, actual: found });
  }
  const commonExpected = expectedNames.filter((name) => actualNames.includes(name));
  const commonActual = actualNames.filter((name) => expectedNames.includes(name));
  if (new Set(actualNames).size === actualNames.length && JSON.stringify(commonActual) !== JSON.stringify(commonExpected)) {
    differences.push({ name: "row order", expected: commonExpected, actual: commonActual });
  }
  return differences;
}

function checkTable(title: string, heading: string, expected: Table): void {
  const actual = readTable(guide(), heading);
  const differences = tableDifferences(actual, expected);
  if (differences.length > 0) console.info(`Expected ${title} table:\n${renderTable(expected)}`);
  const names = new Set(["columns", "row order", ...expected.rows.map(([name]) => name), ...differences.map(({ name }) => name)]);
  for (const name of names) expect.soft(differences.filter((difference) => difference.name === name), `${title}: ${name}`).toEqual([]);
}

function inputTable(inputs: Inputs): Table {
  return { columns: ["Input", "Kind", "Required", "Default", "Summary (generic layer)"],
    rows: Object.entries(inputs).map(([name, input]) => [code(name), input.kind === "colour" ? "color" : input.kind,
      input.required ? "yes" : "no", input.required ? "none" : code(input.default!), input.adaptation]) };
}

function tintedTable(inputs: Inputs): Table {
  return { columns: ["Input", "Base16", "Base24"], rows: Object.entries(inputs)
    .filter(([, input]) => input["tinted-base16"] != null)
    .map(([name, input]) => [code(name), code(input["tinted-base16"]!), code(input["tinted-base24"]!)]) };
}

function genericDeclarations(css: string) {
  return Object.fromEntries(modes.map((mode) => {
    const block = topLevelBlocks(css).find(({ selector }) => selector === selectors[mode]);
    if (!block) throw new Error(`Missing generic ${mode} scope`);
    return [mode, declarations(block.body)];
  }));
}

function defaultDifferences(inputs: Inputs, css: string): Difference[] {
  const maps = genericDeclarations(css);
  const differences: Difference[] = [];
  for (const [name, input] of Object.entries(inputs)) {
    if (input.required && input.default !== null) differences.push({ name: `${name}: required default`, expected: null, actual: input.default });
    for (const mode of modes) {
      const expected = input.required ? undefined : input.default;
      if (maps[mode][name] !== expected) differences.push({ name: `${name}: ${mode}`, expected, actual: maps[mode][name] });
    }
  }
  return differences;
}

function commonInputs(role: string, frozen: Freeze): string[] {
  return Object.keys(roster.inputs).filter((input) => Object.values(frozen.fixtures)
    .every((fixture) => fixture.closures[input]?.includes(role)));
}

function mermaidTable(mapping: Mapping, frozen: Freeze): Table {
  const grouped = new Map<string, string[]>();
  for (const [variable, role] of Object.entries(mapping.variables)) grouped.set(role, [...(grouped.get(role) ?? []), variable]);
  return { columns: ["Mermaid variables", "Role", "Follows (generic layer)"], rows: [...grouped].flatMap(([role, variables]) => {
    const follows = commonInputs(role, frozen);
    return follows.length === 0 ? [] : [[variables.map(code).join(", "), code(role), follows.map(code).join(", ")]];
  }) };
}

function allowedSpaces(source: string): string[] {
  const body = source.match(/ALLOWED_SPACES = new Set\(\[([^\]]*)\]\)/)?.[1];
  if (body === undefined) throw new Error("Missing computed color-space allowlist");
  return [...body.matchAll(/"([^"]+)"/g)].map((match) => match[1]);
}

function spaceDifferences(spaces: string[], labels: Record<string, string>): Difference[] {
  return [...new Set([...spaces, ...Object.keys(labels)])].flatMap((name) =>
    spaces.includes(name) === Object.hasOwn(labels, name) ? [] : [{ name: `color space ${name}`, expected: spaces.includes(name), actual: Object.hasOwn(labels, name) }]);
}

function documentedSpaces(markdown: string): string[] {
  const list = section(markdown, "## Diagrams").match(/\baccepts these computed color spaces: ([^.\n]+)\./)?.[1];
  return list === undefined ? [] : list.split(/,\s*|\s+and\s+/).map((label) => label.trim());
}

function byteCap(source: string): number {
  const value = source.match(/CAP = ([\d_]+)/)?.[1];
  if (value === undefined) throw new Error("Missing stylesheet byte cap");
  return Number(value.replaceAll("_", ""));
}

function radiusDefault(base: number, multiplier: number): string {
  return `${Number((base * multiplier).toFixed(4))}rem`;
}

function radiusTable(css: string): Table {
  const maps = genericDeclarations(css);
  const declaredBase = maps.light["--pb-theme-radius"];
  const match = declaredBase.match(/^([\d.]+)rem$/);
  if (!match || maps.dark["--pb-theme-radius"] !== declaredBase) throw new Error("Generic radius bases must agree in rem");
  const base = Number(match[1]);
  const reference = ["var(", "--pb-radius", ")"].join("");
  const pattern = new RegExp(`^calc\\(${escapePattern(reference)} \\* ([\\d.]+)\\)$`);
  const rows = [[code("--pb-radius"), "1", code(radiusDefault(base, 1))]];
  for (const [name, value] of Object.entries(maps.light).filter(([name]) => name.startsWith("--pb-radius-"))) {
    const multiplier = value.match(pattern)?.[1];
    if (multiplier === undefined || maps.dark[name] !== value) throw new Error(`Generic radius formula differs: ${name}`);
    rows.push([code(name), multiplier, code(radiusDefault(base, Number(multiplier)))]);
  }
  return { columns: ["Step", "Multiplier", "Default"], rows };
}

function configurationDifferences(configuration: string, markdown: string): Difference[] {
  const count = configuration.split(/\r?\n/).filter((line) => line === "## Themes").length;
  const differences: Difference[] = [];
  if (count !== 1) differences.push({ name: "configuration heading", expected: 1, actual: count });
  if (!markdown.includes("](configuration.md#themes)")) differences.push({ name: "configuration link", expected: true, actual: false });
  return differences;
}

function schemeReasons(source: string): string[] {
  const body = source.match(/\benum class TintedFailureKind\s*\{([^}]*)\}/)?.[1];
  if (body === undefined) throw new Error("Missing scheme failure enum");
  return [...body.matchAll(/\b[A-Z][A-Z_]+\b/g)].map((match) => match[0]);
}

function documentedSchemeReasons(markdown: string): string[] {
  return readTable(markdown, "### Scheme failure reasons").rows.map(([name]) => name).sort();
}

function rejectedFormDifferences(markdown: string, classes: RejectedForm[]): Difference[] {
  const rows = readTable(markdown, "### Rejected forms").rows;
  const expected = classes.map((entry) => entry.docs);
  const actual = rows.map(([form]) => form);
  const differences: Difference[] = JSON.stringify(actual) === JSON.stringify(expected) ? [] :
    [{ name: "rejected forms", expected, actual }];
  classes.forEach((entry, index) => {
    const row = (rows[index] ?? []).join(" ");
    for (const [witness, phrase] of Object.entries(entry.witnesses)) {
      if (!row.includes(phrase)) differences.push({ name: entry.id + " witness " + witness, expected: phrase, actual: row });
    }
  });
  return differences;
}

function leafPattern(source: string): string {
  const directory = source.match(/\bconst val THEMES_DIRECTORY = "([^"]+)"/)?.[1];
  const expression = source.match(/\bLEAF = Regex\("([^"]+)"\)/)?.[1];
  if (directory === undefined || expression === undefined) throw new Error("Missing theme leaf grammar");
  return expression.replaceAll("$THEMES_DIRECTORY", directory).replaceAll("\\\\", "\\");
}

function configurationLeafDifferences(configuration: string, source: string): Difference[] {
  const expected = code(leafPattern(source));
  return configuration.includes(expected) ? [] : [{ name: "theme leaf pattern", expected, actual: false }];
}

function corpusCommit(source: string): string {
  const commit = source.match(/\bval tintedSchemesCommit = "([a-f0-9]{40})"/)?.[1];
  if (commit === undefined) throw new Error("Missing scheme corpus commit");
  return commit;
}

function documentedCorpusCommits(markdown: string): string[] {
  return [...new Set(markdown.match(/\b[a-f0-9]{40}\b/g) ?? [])].sort();
}

describe("the theming guide matches its sources", () => {
  it("the input table matches the roster", () => {
    checkTable("Inputs", "## Inputs", inputTable(roster.inputs));
  });

  it("defaults close the chain", () => {
    const differences = defaultDifferences(roster.inputs, genericCss());
    for (const name of Object.keys(roster.inputs)) {
      expect.soft(differences.filter((difference) => difference.name.startsWith(name + ":")), name).toEqual([]);
    }
  });

  it("the Tinted table matches the mapped inputs", () => {
    checkTable("Base16 and Base24", "### Base16 and Base24", tintedTable(roster.inputs));
  });

  it("the guide names both definition selectors", () => {
    const markdown = guide();
    for (const mode of modes) expect.soft(markdown, `${mode} selector code span`).toContain(code(selectors[mode]));
  });

  it("the Mermaid table matches its mapping and common dependencies", () => {
    const mapping = JSON.parse(readFileSync("src/__tests__/fixtures/theme/mermaid-mapping.json", "utf8")) as Mapping;
    checkTable("Diagrams", "## Diagrams", mermaidTable(mapping, readFrozen()));
    expect(section(guide(), "## Diagrams"), "shadow color conversion").toContain(code(mapping.shadow));
  });

  it("the guide names the supported computed color spaces", () => {
    const spaces = allowedSpaces(readFileSync("src/lib/mermaidColor.ts", "utf8"));
    expect.soft(spaceDifferences(spaces, displaySpaces), "allowlist display names").toEqual([]);
    const diagrams = section(guide(), "## Diagrams");
    expect.soft(new Set(documentedSpaces(guide())), "documented computed color spaces")
      .toEqual(new Set(spaces.map((name) => displaySpaces[name])));
    const cases = (JSON.parse(readFileSync("src/__tests__/fixtures/theme/mermaid-colors.json", "utf8")) as
      { cases: Array<{ source: string; reject?: boolean }> }).cases;
    for (const name of new Set([...diagrams.matchAll(/\b(?:xyz-d65|srgb-linear|rec2020)\b/g)].map(([name]) => name))) {
      expect.soft(cases.some((entry) => entry.reject === true && entry.source.startsWith(`color(${name} `)), `rejected space ${name}`).toBe(true);
    }
  });

  it("the guide states the stylesheet byte cap", () => {
    const cap = byteCap(readFileSync("../server/src/main/kotlin/com/plainbase/frameworks/filesystem/LocalThemeStylesheets.kt", "utf8"));
    expect(guide(), "stylesheet byte cap").toContain(`${code(String(cap))} bytes`);
  });

  it("the radius table matches the generic formulas", () => {
    checkTable("Radius", "### Radius", radiusTable(genericCss()));
  });

  it("the guide links the configuration heading", () => {
    const differences = configurationDifferences(readFileSync("../docs/configuration.md", "utf8"), guide());
    for (const name of ["configuration heading", "configuration link"]) expect.soft(differences.filter((difference) => difference.name === name), name).toEqual([]);
  });

  it("the theme decision is registered", () => {
    const files = readdirSync("../docs/decisions").filter((file) => /^0014-.*\.md$/.test(file)).sort();
    expect.soft(files, "one theme decision file").toHaveLength(1);
    const index = readFileSync("../docs/decisions/README.md", "utf8");
    const links = [...index.matchAll(/\]\((0014-[^)]+\.md)\)/g)].map((match) => match[1]).sort();
    expect.soft(links, "registered theme decision link").toEqual(files);
  });

  it("the scheme failure reasons match the parser", () => {
    const source = readFileSync("../server/src/main/kotlin/com/plainbase/domain/theming/TintedScheme.kt", "utf8");
    expect(documentedSchemeReasons(guide())).toEqual(schemeReasons(source).map(code).sort());
  });

  it("the rejected forms cover their classes and witness phrases", () => {
    const cases = JSON.parse(readFileSync("../server/src/test/resources/tinted/scheme-oracle-cases.json", "utf8")) as
      { rules: { classes: RejectedForm[] } };
    expect(rejectedFormDifferences(guide(), cases.rules.classes)).toEqual([]);
  });

  it("the configuration names the current theme leaf grammar", () => {
    const source = readFileSync("../server/src/main/kotlin/com/plainbase/domain/theming/ThemeSelection.kt", "utf8");
    expect(configurationLeafDifferences(readFileSync("../docs/configuration.md", "utf8"), source)).toEqual([]);
  });

  it("the guide cites the pinned scheme corpus commit", () => {
    const expected = corpusCommit(readFileSync("build.gradle.kts", "utf8"));
    for (const file of ["../docs/theming.md", "../contrib/tinted/README.md"]) {
      expect.soft(documentedCorpusCommits(readFileSync(file, "utf8")), file).toEqual([expected]);
    }
  });
});

describe("documentation comparison controls", () => {
  it("a rendered roster table has no differences", () => {
    const expected = inputTable(roster.inputs);
    expect(tableDifferences(readTable("## Inputs\n" + renderTable(expected), "## Inputs"), expected)).toEqual([]);
  });

  it.each([
    ["dropping an input", "drop"], ["adding an input", "add"], ["changing an input default", "default"], ["changing a Summary word", "summary"],
  ])("%s produces one named difference", (_, change) => {
    const expected = inputTable(roster.inputs);
    const actual = structuredClone(expected);
    let name: string;
    if (change === "drop") name = actual.rows.shift()![0];
    else if (change === "add") {
      name = code("--pb-theme-extra");
      actual.rows.push([name, "font", "no", code("inherit"), "Additional input."]);
    } else {
      const row = change === "default" ? actual.rows.find((row) => row[2] === "no")! : actual.rows[0];
      name = row[0];
      const column = change === "default" ? 3 : 4;
      row[column] += " changed";
    }
    const parsed = readTable("## Inputs\n" + renderTable(actual), "## Inputs");
    expect(tableDifferences(parsed, expected).map((difference) => difference.name)).toEqual([name]);
  });

  it("changing a Tinted slot produces one named difference", () => {
    const expected = tintedTable(roster.inputs);
    const actual = structuredClone(expected);
    actual.rows[0][1] = code("changed-slot");
    expect(tableDifferences(readTable("### Base16 and Base24\n" + renderTable(actual), "### Base16 and Base24"), expected)
      .map((difference) => difference.name)).toEqual([expected.rows[0][0]]);
  });

  it("adding a computed space produces one named difference", () => {
    const spaces = allowedSpaces(readFileSync("src/lib/mermaidColor.ts", "utf8"));
    expect(spaceDifferences([...spaces, "rec2020"], displaySpaces).map((difference) => difference.name)).toEqual(["color space rec2020"]);
  });

  it("color-space labels outside the supported list do not fill omissions", () => {
    const markdown = "## Diagrams\nMermaid receives colors converted to sRGB; it accepts these computed color spaces: HSL.\n";
    expect(documentedSpaces(markdown)).toEqual(["HSL"]);
  });

  it("the supported list retains extra spaces for comparison", () => {
    const markdown = "## Diagrams\nConversion accepts these computed color spaces: sRGB, HSL and Rec2020.\n";
    expect(documentedSpaces(markdown)).toEqual(["sRGB", "HSL", "Rec2020"]);
  });

  it("renaming the configuration heading produces one named difference", () => {
    expect(configurationDifferences("## Theme settings\n", "[Theme settings](configuration.md#themes)")
      .map((difference) => difference.name)).toEqual(["configuration heading"]);
  });

  it("derived diagram dependencies omit roles without a common input", () => {
    const mapping = JSON.parse(readFileSync("src/__tests__/fixtures/theme/mermaid-mapping.json", "utf8")) as Mapping;
    const frozen = readFrozen();
    expect(commonInputs(mapping.shadow, frozen)).toEqual([]);
    const table = mermaidTable({ ...mapping, variables: { ...mapping.variables, dropShadow: mapping.shadow } }, frozen);
    expect(table.rows.some((row) => row[1] === code(mapping.shadow))).toBe(false);
    expect(table.rows.flatMap(([variables]) => [...variables.matchAll(/`([^`]+)`/g)].map((match) => match[1])).sort())
      .toEqual(Object.keys(mapping.variables).sort());
  });

  it("numeric source formats preserve the byte cap and rounded rem defaults", () => {
    expect(byteCap("CAP = 262_144")).toBe(262144);
    expect(radiusDefault(0.25, 1.2)).toBe("0.3rem");
    const table = radiusTable(genericCss());
    expect(tableDifferences(readTable("### Radius\n" + renderTable(table), "### Radius"), table)).toEqual([]);
  });

  it("the scheme reason comparison detects omissions and additions", () => {
    const reasons = [["SCHEME", "PROBE"].join("_"), ["VARIANT", "PROBE"].join("_")];
    const source = "enum class TintedFailureKind { " + reasons.join(", ") + " }";
    const expected = schemeReasons(source).map(code).sort();
    const markdown = (names: string[]) => "### Scheme failure reasons\n" +
      renderTable({ columns: ["Reason"], rows: names.map((name) => [code(name)]) });
    expect(documentedSchemeReasons(markdown(reasons))).toEqual(expected);
    expect(documentedSchemeReasons(markdown(reasons.slice(1)))).not.toEqual(expected);
    expect(documentedSchemeReasons(markdown([...reasons, ["SCHEME", "EXTRA"].join("_")]))).not.toEqual(expected);
  });

  it("rejected form comparisons unescape pipes and detect missing witnesses", () => {
    const phrase = code("|");
    const classes: RejectedForm[] = [{ id: "probe", docs: "Literal " + phrase + " content", witnesses: { pipe: phrase } }];
    const markdown = "### Rejected forms\n" + renderTable({ columns: ["Form"], rows: classes.map((entry) => [entry.docs]) });
    expect(markdown).toContain("\\|");
    expect(rejectedFormDifferences(markdown, classes)).toEqual([]);
    expect(rejectedFormDifferences(markdown.replace("content", "changed"), classes).map((difference) => difference.name))
      .toEqual(["rejected forms"]);
    const changed = [{ ...classes[0], witnesses: { pipe: ["absent", "phrase"].join(" ") } }];
    expect(rejectedFormDifferences(markdown, changed).map((difference) => difference.name)).toEqual(["probe witness pipe"]);
  });

  it("the leaf comparison substitutes its directory and decodes the Kotlin regex", () => {
    const directory = ["sample", "themes"].join("-");
    const expression = "($THEMES_DIRECTORY/)?[A-Z]" + "\\\\." + "(css|yaml)";
    const source = 'const val THEMES_DIRECTORY = "' + directory + '"\nprivate val LEAF = Regex("' + expression + '")';
    const expected = "(" + directory + "/)?[A-Z]" + "\\." + "(css|yaml)";
    expect(leafPattern(source)).toBe(expected);
    expect(configurationLeafDifferences(code(expected), source)).toEqual([]);
    expect(configurationLeafDifferences(code(expected.replace(directory, ["other", "themes"].join("-"))), source)
      .map((difference) => difference.name)).toEqual(["theme leaf pattern"]);
  });

  it("the corpus comparison detects a different cited commit", () => {
    const expected = "1".repeat(40);
    const changed = "2".repeat(40);
    const source = 'val tintedSchemesCommit = "' + expected + '"';
    const mentions = (commit: string) => [
      "[Corpus](https://github.com/tinted-theming/schemes/tree/" + commit + ")",
      "[Scheme](https://github.com/tinted-theming/schemes/blob/" + commit + "/base16/example.yaml)",
      "At commit " + code(commit),
    ];
    expect(corpusCommit(source)).toBe(expected);
    expect(documentedCorpusCommits(mentions(expected).join("\n"))).toEqual([expected]);
    mentions(changed).forEach((mention, index) => {
      const markdown = mentions(expected);
      markdown[index] = mention;
      expect.soft(documentedCorpusCommits(markdown.join("\n")), `mention ${index}`).toEqual([expected, changed]);
    });
  });
});
