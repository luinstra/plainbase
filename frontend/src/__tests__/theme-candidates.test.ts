// @vitest-environment node
import { readFileSync, readdirSync } from "node:fs";
import { isDeepStrictEqual } from "node:util";
import { describe, expect, it } from "vitest";
import { background, color, declarations, expand, parse, rows, stripComments, topLevelBlocks, type Color } from "./contrastResolver";
import {
  aliasInput, assemble, builtinCss, builtinSlots, CANDIDATE_ROLES, closure, COLOUR_ROLES, customSlots, fixtureAssembly,
  fixtureDefinitions, fixtureModes, fourInputDefinition, genericCss, legacy, legacyTokens, NON_COLOUR_ROLES, primitiveNames, PUBLIC,
  readFrozen, recomputeFreeze, roster, rowName, rowRatio, selectors, SEMANTIC, tokenValue, type Mode, type Slots, type Value,
} from "./themeCandidates";

const source = stripComments(builtinCss("light") + builtinCss("dark"));
const hexes = [...source.matchAll(/#[\da-f]{3}(?:[\da-f]{3})?\b/gi)].map((match) => match[0]);
const opaqueHue = hexes.find((hex) => hex.length === 7 && parse(hex)[0] === 13 / 255 && parse(hex)[1] === 148 / 255)!;
function tuple(actual: Color, expected: number[]) {
  actual.forEach((value, i) => expect(Math.abs(value - expected[i])).toBeLessThan(0.0001));
}
function same(actual: Value, expected: Value) {
  if (typeof expected === "string") expect(actual).toBe(expected);
  else { expect(Array.isArray(actual)).toBe(true); tuple(actual as Color, expected); }
}
function sourceFiles(directory: string, suffix: RegExp): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const file = `${directory}/${entry.name}`;
    return entry.isDirectory() ? sourceFiles(file, suffix) : suffix.test(entry.name) ? [file] : [];
  });
}
const optional = Object.entries(roster.inputs).filter(([, input]) => !input.required);
const modes: Mode[] = ["light", "dark"];

function probeFloor(lens: "generic" | "builtin", mode: Mode, name: string) {
  const input = roster.inputs[name];
  const probe = fixtureDefinitions().probes[name];
  expect(probe.kind).toBe(input.kind);
  const slots = lens === "generic" ? customSlots() : builtinSlots();
  const before = assemble(mode, slots);
  const after = assemble(mode, slots, `:root { ${name}: ${probe.value}; }`);
  const affected = new Set(closure(name, before));
  const floorKey: "generic" | "builtin-light" | "builtin-dark" = lens === "generic" ? "generic" : `builtin-${mode}`;
  const floor = input[floorKey];
  const target: Value = input.kind === "colour" ? parse(probe.value) : probe.value;
  const deltas = floor.map((role) => {
    const original = tokenValue(before, role);
    if (typeof target === "string") { expect(original, `probe too close ${role}`).not.toBe(target); return null; }
    expect(Array.isArray(original)).toBe(true);
    const delta = Math.max(...target.map((value, i) => Math.abs(value - (original as Color)[i])));
    expect(delta, `probe too close ${role}`).toBeGreaterThanOrEqual(0.02);
    return delta;
  });
  if (process.env.PB_THEME_DRILL_AUDIT === "1") {
    console.info("PROBE_DELTA", JSON.stringify({ lens, mode, input: name, minimum: deltas[0] === null ? "exact flip" : Math.min(...deltas as number[]) }));
  }
  for (const role of floor) {
    expect(affected.has(role), `${name} must reach ${role}`).toBe(true);
    same(tokenValue(after, role), target);
  }
  for (const role of [...CANDIDATE_ROLES, ...PUBLIC]) if (!affected.has(role)) {
    const expected = lens === "builtin" && SEMANTIC.includes(role) ? tokenValue(legacy(mode), role) : tokenValue(before, role);
    same(tokenValue(after, role), expected);
  }
  if (["--pb-theme-card", "--pb-theme-card-foreground", "--pb-theme-selection", "--pb-theme-selection-foreground"].includes(name)) {
    for (const role of roster.independentInk) expect(affected.has(role), `${name} independent ink ${role}`).toBe(false);
  }
}

describe("theme contract", () => {
  it("roster has exactly 24 inputs: 21 colours, 2 fonts, 1 radius, 4 required", () => {
    expect(PUBLIC).toHaveLength(24);
    expect(Object.values(roster.inputs).filter((input) => input.kind === "colour")).toHaveLength(21);
    expect(Object.values(roster.inputs).filter((input) => input.kind === "font")).toHaveLength(2);
    expect(Object.values(roster.inputs).filter((input) => input.kind === "length")).toHaveLength(1);
    expect(Object.values(roster.inputs).filter((input) => input.required)).toHaveLength(4);
    for (const mode of modes) {
      const map = declarations(topLevelBlocks(genericCss()).find((entry) => entry.selector === selectors[mode])!.body);
      const defaults = Object.keys(map).filter((name) => name.startsWith("--pb-theme-"));
      expect(defaults.sort(), mode).toEqual(optional.map(([name]) => name).sort());
    }
  });
  it("generic declares every candidate role", () => {
    const files = ["src/styles/app.css", "src/lib/mermaid.ts", ...sourceFiles("src/components", /\.tsx$/)];
    const consumed = new Set(files.flatMap((file) => [...stripComments(readFileSync(file, "utf8"))
      .matchAll(/--pb-[a-z0-9-]*[a-z0-9]/g)].map((match) => match[0])).filter((name) => !PUBLIC.includes(name)));
    expect(consumed.size).toBeGreaterThanOrEqual(57);
    expect([...consumed].filter((name) => !CANDIDATE_ROLES.includes(name))).toEqual([]);
    expect(SEMANTIC).toHaveLength(57);
    expect(roster.newPrivates).toHaveLength(13);
    expect(new Set(CANDIDATE_ROLES).size).toBe(70);
    for (const mode of modes) {
      const map = declarations(topLevelBlocks(genericCss()).find((entry) => entry.selector === selectors[mode])!.body);
      for (const name of CANDIDATE_ROLES) expect(map, `${mode} ${name}`).toHaveProperty(name);
    }
  });
  it.each(fixtureModes)("four-input fixtures resolve every candidate role %s", (fixtureMode) => {
    const map = fixtureAssembly(fixtureMode);
    expect(COLOUR_ROLES).toHaveLength(60);
    expect(NON_COLOUR_ROLES).toHaveLength(10);
    for (const name of COLOUR_ROLES) expect(tokenValue(map, name)).toHaveLength(4);
    for (const name of NON_COLOUR_ROLES) expect(expand(`var(${name})`, map)).not.toBe("");
    const names = Object.keys(map).concat(Object.values(map).flatMap((value) => [...value.matchAll(/--pb-theme-[\w-]+/g)].map((m) => m[0])));
    expect(names.filter((name) => name.startsWith("--pb-theme-") && !PUBLIC.includes(name))).toEqual([]);
  });
  it.each(fixtureModes)("existing contrast rows pass on four-input fixtures %s", (fixtureMode) => {
    const map = fixtureAssembly(fixtureMode);
    for (const row of rows) expect.soft(rowRatio(map, row), `${fixtureMode} ${rowName(row)}`).toBeGreaterThanOrEqual(row[2]);
  });
  it.each(fixtureModes)("nested callouts stay opaque %s", (fixtureMode) => {
    const map = fixtureAssembly(fixtureMode);
    for (const kind of ["note", "warning", "danger"]) {
      const name = `callout-${kind}-bg`;
      expect(color(map, name)[3], `${fixtureMode} ${name}`).toBe(1);
      tuple(background(map, ["surface", name, name]), color(map, name));
      const broken = { ...map, [`--pb-${name}`]: "color-mix(in srgb, var(--pb-accent) 18%, transparent)" };
      expect(color(broken, name)[3]).not.toBe(1);
      expect(background(broken, ["surface", name, name])).not.toEqual(background(broken, ["surface", name]));
    }
  });
  it.each(modes)("generic faint ink aliases muted foreground %s", (mode) => {
    const body = topLevelBlocks(genericCss()).find((entry) => entry.selector === selectors[mode])!.body;
    expect(declarations(body)["--pb-text-faint"]).toBe("var(--pb-theme-muted-foreground)");
  });
  it.each(modes)("built-in definitions reproduce the frozen legacy palette numerically %s", (mode) => {
    const map = assemble(mode, builtinSlots());
    for (const name of SEMANTIC) same(tokenValue(map, name), tokenValue(legacy(mode), name));
  });
  it.each(modes)("built-in definitions reproduce the frozen legacy palette textually %s", (mode) => {
    const css = stripComments(builtinCss(mode));
    const declared = declarations(css);
    const map = assemble(mode, builtinSlots());
    const floors = new Map(Object.entries(roster.inputs).flatMap(([input, entry]) =>
      entry[`builtin-${mode}`].filter((name) => SEMANTIC.includes(name)).map((name) => [name, input] as const)));
    for (const name of SEMANTIC) {
      expect(declared, name).toHaveProperty(name);
      if (floors.has(name)) expect(aliasInput(name, map), name).toBe(floors.get(name));
      else expect(css, name).toContain(`${name}: ${legacy(mode)[name]};`);
    }
  });
  for (const mode of modes) for (const [name] of optional) {
    it(`every optional input reaches its generic floor ${mode} ${name}`, () => probeFloor("generic", mode, name));
    it(`every optional input reaches its builtin floor ${mode} ${name}`, () => probeFloor("builtin", mode, name));
  }
  it("required inputs have no generic fallback", () => {
    expect(stripComments(genericCss())).not.toMatch(/--pb-theme-(background|foreground|primary|primary-foreground)\s*:/);
  });
  for (const [label, mode, read] of [
    ["builtin-light", "light", () => builtinCss("light")], ["builtin-dark", "dark", () => builtinCss("dark")],
    ...fixtureModes.map((fixtureMode) => [fixtureMode, fixtureMode === "opaque-light" ? "light" : "dark", () => fourInputDefinition(fixtureMode)] as const),
  ] as const) it(`definitions use exactly one exclusive scope ${label}`, () => {
    const blocks = topLevelBlocks(read());
    expect(blocks).toHaveLength(1);
    expect(blocks[0].selector).toBe(selectors[mode]);
  });
  const selections: Array<[string, () => Slots, Mode[]]> = [
    ["builtin/custom", () => [genericCss(), builtinCss("light"), fourInputDefinition("opaque-dark")], ["dark"]],
    ["custom/builtin", () => [genericCss(), fourInputDefinition("opaque-light"), builtinCss("dark")], ["light"]],
    ["custom/custom", () => customSlots(), ["light", "dark"]],
    ["custom/translucent", () => customSlots("translucent-dark"), ["light", "dark"]],
  ];
  for (const [selection, slots, custom] of selections) for (const mode of modes) {
    it(`mixed selections leak no refinement ${selection} ${mode}`, () => {
      const map = assemble(mode, slots());
      if (custom.includes(mode)) {
        expect(Object.keys(map).filter((name) => primitiveNames().includes(name))).toEqual([]);
        const fixtureMode = mode === "light" ? "opaque-light" : selection === "custom/translucent" ? "translucent-dark" : "opaque-dark";
        for (const name of CANDIDATE_ROLES) same(tokenValue(map, name), tokenValue(fixtureAssembly(fixtureMode), name));
      } else for (const name of SEMANTIC) same(tokenValue(map, name), tokenValue(legacy(mode), name));
    });
  }
  it("generic values, ratios, closures and floors are frozen", () => {
    const actual = recomputeFreeze();
    const print = () => console.log("RECOMPUTED_CONTRACT_BEGIN\n" + JSON.stringify(actual) + "\nRECOMPUTED_CONTRACT_END");
    let frozen;
    try { frozen = readFrozen(); } catch (error) { print(); throw error; }
    const equals = isDeepStrictEqual({ fixtures: actual.fixtures, floors: actual.floors }, { fixtures: frozen.fixtures, floors: frozen.floors });
    if (!equals) print();
    expect(equals, "frozen contract differs; recomputed JSON printed above").toBe(true);
  });
  it("primitives are exactly the frozen legacy primitives, identical in both built-ins", () => {
    const names = primitiveNames();
    expect(names).toHaveLength(72);
    expect(names).toEqual([...legacyTokens.primitives].sort());
    const expected = Object.fromEntries(legacyTokens.primitives.map((name) => [name, legacyTokens.light[name]]));
    for (const mode of modes) {
      const actual = Object.fromEntries(Object.entries(declarations(builtinCss(mode))).filter(([name]) => names.includes(name)));
      expect(actual, mode).toEqual(expected);
    }
  });
  it("resolver reads the opaque-hue form", () => {
    tuple(parse(`rgb(from color-mix(in srgb, ${opaqueHue} 15%, transparent) r g b / 1)`), parse(opaqueHue));
    for (const expression of [
      `rgb(from ${opaqueHue} r g b / 0.5)`, `rgb(from ${opaqueHue} g r b / 1)`, `hsl(from ${opaqueHue} h s l / 1)`,
    ]) expect(() => parse(expression)).toThrow();
  });
});
