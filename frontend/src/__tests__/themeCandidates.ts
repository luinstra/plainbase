import { readFileSync } from "node:fs";
import { background, block, color, contrast, declarations, expand, parse, rows, stripComments, topLevelBlocks, type Color, type Tokens } from "./contrastResolver";

export type Mode = "light" | "dark";
export type FixtureMode = "opaque-light" | "opaque-dark" | "translucent-dark";
export type Kind = "colour" | "font" | "length";
export type Input = {
  required: boolean; kind: Kind; default: string | null;
  generic: string[]; "builtin-light": string[]; "builtin-dark": string[]; adaptation: string;
};
export type Slots = readonly [string, string, string];
export type Value = Color | string;
export type Freeze = {
  fixtures: Record<FixtureMode, { roles: Record<string, Value>; ratios: Record<string, number>; closures: Record<string, string[]> }>;
  floors: Record<string, Pick<Input, "generic" | "builtin-light" | "builtin-dark">>;
  notes: string[];
};

export const selectors: Record<Mode, string> = {
  light: ':where(:root:not([data-theme="dark"]))', dark: ':where(:root[data-theme="dark"])',
};
export const fixtureModes: FixtureMode[] = ["opaque-light", "opaque-dark", "translucent-dark"];
export const roster = JSON.parse(readFileSync("src/__tests__/fixtures/theme/roster.json", "utf8")) as {
  inputs: Record<string, Input>; independentInk: string[]; newPrivates: string[]; description: string;
};
export const PUBLIC = Object.keys(roster.inputs);
export const tokensSource = stripComments(readFileSync("src/styles/tokens.css", "utf8"));
const lightBody = topLevelBlocks(tokensSource).find((entry) => entry.selector === ":root")!.body;
export const SEMANTIC = Object.keys(declarations(lightBody.split("color-scheme: light;")[1]));
export const CANDIDATE_ROLES = [...SEMANTIC, ...roster.newPrivates];
export const COLOUR_ROLES = [...SEMANTIC, "--pb-card-foreground", "--pb-diagram-node-text", "--pb-primary-opaque"];
export const NON_COLOUR_ROLES = CANDIDATE_ROLES.filter((name) => !COLOUR_ROLES.includes(name));

export function genericCss(): string { return readFileSync("src/styles/themes/generic.css", "utf8"); }
export function builtinCss(mode: Mode): string { return readFileSync(`src/styles/themes/${mode}.css`, "utf8"); }
export function fixtureDefinitions() {
  return JSON.parse(readFileSync("src/__tests__/fixtures/theme/four-input-definitions.json", "utf8")) as
    Record<FixtureMode, Tokens> & { probes: Record<string, { kind: Kind; value: string }>; notes: string[] };
}
export function fourInputDefinition(fixtureMode: FixtureMode): string {
  const mode = fixtureMode === "opaque-light" ? "light" : "dark";
  const body = Object.entries(fixtureDefinitions()[fixtureMode]).map(([name, value]) => `  ${name}: ${value};`).join("\n");
  return `${selectors[mode]} {\n${body}\n}`;
}
export function builtinSlots(): Slots { return [genericCss(), builtinCss("light"), builtinCss("dark")]; }
export function customSlots(dark: FixtureMode = "opaque-dark"): Slots {
  return [genericCss(), fourInputDefinition("opaque-light"), fourInputDefinition(dark)];
}
export function fixtureAssembly(fixtureMode: FixtureMode): Tokens {
  return assemble(fixtureMode === "opaque-light" ? "light" : "dark", customSlots(fixtureMode === "translucent-dark" ? fixtureMode : "opaque-dark"));
}

// Slots are generic, light definition, dark definition. Later applicable declarations win.
// Textual order differs from Chromium: :root outranks :where regardless of order.
// T10 excludes that divergence in valid definitions; the later P2/P5 browser tests witness it.
export function assemble(mode: Mode, slots: Slots): Tokens {
  const result: Tokens = {};
  for (const css of slots) for (const entry of topLevelBlocks(css)) {
    let applies: boolean;
    if (entry.selector === ":root") applies = true;
    else if (entry.selector === selectors.light) applies = mode === "light";
    else if (entry.selector === selectors.dark) applies = mode === "dark";
    else throw new Error(`unsupported selector ${entry.selector}`);
    if (applies) Object.assign(result, declarations(entry.body));
  }
  return result;
}
export function legacy(mode: Mode): Tokens {
  const light = block(tokensSource, ":root");
  return mode === "light" ? light : { ...light, ...block(tokensSource, '[data-theme="dark"]') };
}
export function primitiveNames(): string[] {
  const names = Object.keys(declarations(stripComments(builtinCss("light") + builtinCss("dark"))));
  return names.filter((name) => !PUBLIC.includes(name) && !CANDIDATE_ROLES.includes(name)).sort();
}
export function closure(input: string, map: Tokens): string[] {
  const names = new Set([input]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const [name, value] of Object.entries(map)) {
      const references = [...value.matchAll(/var\((--[\w-]+)\)/g)].map((match) => match[1]);
      if (!names.has(name) && references.some((reference) => names.has(reference))) { names.add(name); changed = true; }
    }
  }
  return [...names].sort();
}
export function tokenValue(map: Tokens, name: string): Value {
  const isColour = COLOUR_ROLES.includes(name) || roster.inputs[name]?.kind === "colour";
  const value = expand(`var(${name})`, map);
  return isColour ? parse(value) : value;
}
export function aliasInput(name: string, map: Tokens, seen: string[] = []): string | undefined {
  if (PUBLIC.includes(name)) return name;
  if (seen.includes(name)) throw new Error(`alias cycle ${name}`);
  const value = map[name];
  const next = value?.match(/^var\((--[\w-]+)\)$/)?.[1]
    ?? value?.match(/^rgb\(from var\((--[\w-]+)\) r g b \/ 1\)$/)?.[1];
  return next ? aliasInput(next, map, [...seen, name]) : undefined;
}
export function rowRatio(map: Tokens, row: readonly [string, string[], number]): number {
  return contrast(color(map, row[0]), background(map, row[1]));
}
export function rowName([ink, stack, floor]: readonly [string, string[], number]): string {
  return `${ink} on ${stack.join(" + ")} (${floor})`;
}
export function recomputeFreeze(): Freeze {
  const fixtures = Object.fromEntries(fixtureModes.map((fixtureMode) => {
    const map = fixtureAssembly(fixtureMode);
    return [fixtureMode, {
      roles: Object.fromEntries(CANDIDATE_ROLES.map((name) => [name, tokenValue(map, name)])),
      ratios: Object.fromEntries(rows.map((row) => [rowName(row), Number(rowRatio(map, row).toFixed(4))])),
      closures: Object.fromEntries(PUBLIC.map((name) => [name, closure(name, map)])),
    }];
  })) as Freeze["fixtures"];
  const floors = Object.fromEntries(Object.entries(roster.inputs).map(([name, input]) => [name, {
    generic: input.generic, "builtin-light": input["builtin-light"], "builtin-dark": input["builtin-dark"],
  }]));
  return { fixtures, floors, notes: ["Unreleased generic contract, manually reviewed and frozen after contrast and dependency checks."] };
}

// Read inside test bodies, never at module load, so a missing freeze does not prevent collection.
export function readFrozen(): Freeze {
  return JSON.parse(readFileSync("src/__tests__/fixtures/theme/generic-frozen.json", "utf8")) as Freeze;
}
