import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { parse, rows, topLevelBlocks } from "../contrastResolver";
import {
  assemble, builtinCss, CANDIDATE_ROLES, genericCss, primitiveNames, roster, rowName, rowRatio, selectors, tokenValue, type Mode,
} from "../themeCandidates";
import { adapt, hexOf, hostFacts, isCanonicalSlug, MAPPED, PRODUCTION_TEMPLATE_DIR, requiredEnv, stringValues } from "./pipeline";
import { isMapping, readSchemeMetadata } from "./schemeMetadata";

type Example = {
  id: string; system: "base16" | "base24"; mode: Mode;
  upstream: { repository: string; commit: string; path: string; lastFileCommit: string; sha256: string; author: string; license: string; copyright: string };
  pinned: string; adapted: string; generated: string;
  adjustments: Array<{ slot: string; from: string; to: string; rows: string[] }>;
  notes?: string[];
};
const sources = JSON.parse(fs.readFileSync(path.join(PRODUCTION_TEMPLATE_DIR, "sources/sources.json"), "utf8")) as { examples: Example[] };
const examples = [...sources.examples].sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

function exactPath(relative: string): string {
  let directory = PRODUCTION_TEMPLATE_DIR;
  for (const segment of relative.split("/")) {
    if (!fs.readdirSync(directory).sort().includes(segment)) throw new Error(`Missing exact-case example path: ${relative}`);
    directory = path.join(directory, segment);
  }
  return directory;
}

function metadata(relative: string) {
  const result = readSchemeMetadata(exactPath(relative));
  if ("reason" in result || !isMapping(result.root) || !isMapping(result.palette)) throw new Error(`Invalid example metadata: ${relative}`);
  expect(Object.values(result.palette).every((value) => typeof value === "string")).toBe(true);
  return { ...result, root: result.root, palette: result.palette as Record<string, string> };
}

function accepted(relative: string) {
  const result = adapt(exactPath(relative));
  if (!result.ok) throw new Error(JSON.stringify(result));
  return result;
}

describe("pinned raw scheme mapping", () => {
  it.each(examples)("E1: $id pinned upstream copy matches its recorded hash", (example) => {
    const file = exactPath(example.pinned);
    expect(createHash("sha256").update(fs.readFileSync(file)).digest("hex")).toBe(example.upstream.sha256);
    const source = metadata(example.pinned);
    expect(source.system).toBe(example.system);
    expect(source.variant.value).toBe(example.mode);
    expect(source.root.author).toBe(example.upstream.author);
    const result = accepted(example.pinned);
    const column = example.system === "base16" ? "tinted-base16" : "tinted-base24";
    for (const name of MAPPED) expect.soft(result.declarations[name], name).toBe(hexOf(source.palette[roster.inputs[name][column]!]));
  });
});

describe("adapted scheme examples", () => {
  it.each(examples)("E2: $id adapted example is reproducible and its adjustments are recorded", (example) => {
    const upstream = metadata(example.pinned);
    const adapted = metadata(example.adapted);
    const result = accepted(example.adapted);
    const original = accepted(example.pinned);
    const upstreamSlug = upstream.slug.declared ? upstream.slug.value : original.slug;
    expect(isCanonicalSlug(upstreamSlug)).toBe(true);
    expect(isCanonicalSlug(adapted.slug.declared ? adapted.slug.value : result.slug)).toBe(true);
    const changes = Object.keys(upstream.palette).sort().filter((slot) => hexOf(upstream.palette[slot]) !== hexOf(adapted.palette[slot]));
    expect(Object.keys(adapted.palette).sort()).toEqual(Object.keys(upstream.palette).sort());
    expect(changes).toEqual(example.adjustments.map((adjustment) => adjustment.slot).sort());
    for (const adjustment of example.adjustments) {
      expect(hexOf(adjustment.from), adjustment.slot).toBe(hexOf(upstream.palette[adjustment.slot]));
      expect(hexOf(adjustment.to), adjustment.slot).toBe(hexOf(adapted.palette[adjustment.slot]));
    }
    if (changes.length) {
      expect(adapted.slug.value).toBe(`${upstreamSlug}-adapted`);
      expect(adapted.root.name).toBe(`${upstream.root.name} (adapted for Plainbase)`);
    }
    const { palette: _upstreamPalette, name: _upstreamName, slug: _upstreamSlug, ...upstreamRest } = upstream.root;
    const { palette: _adaptedPalette, name: _adaptedName, slug: _adaptedSlug, ...adaptedRest } = adapted.root;
    expect(adaptedRest).toEqual(upstreamRest);
    expect(example.generated).toBe(`generated/${example.system}-${result.slug}.css`);
    const results = path.join(requiredEnv("PB_TINTED_RESULTS"), "examples");
    fs.mkdirSync(results, { recursive: true });
    fs.writeFileSync(path.join(results, path.basename(example.generated)), result.css);
    expect(result.css).toBe(fs.readFileSync(exactPath(example.generated), "utf8"));
  });

  it.each(examples)("E3: $id adapted example passes existing contrast rows", (example) => {
    const result = accepted(example.adapted);
    expect(result.mode).toBe(example.mode);
    const slots = example.mode === "light" ? [genericCss(), result.css, builtinCss("dark")] as const
      : [genericCss(), builtinCss("light"), result.css] as const;
    const tokens = assemble(example.mode, slots);
    const readings = rows.map((row) => ({ name: rowName(row), ratio: rowRatio(tokens, row), floor: row[2] }));
    fs.mkdirSync(requiredEnv("PB_TINTED_RESULTS"), { recursive: true });
    fs.writeFileSync(path.join(requiredEnv("PB_TINTED_RESULTS"), `contrast-${example.id}.json`), JSON.stringify(readings, null, 2) + "\n");
    for (const reading of readings) expect.soft(reading.ratio, reading.name).toBeGreaterThanOrEqual(reading.floor);
    for (const role of CANDIDATE_ROLES) expect.soft(() => tokenValue(tokens, role), role).not.toThrow();
    const blocks = topLevelBlocks(result.css);
    expect(blocks).toHaveLength(1);
    expect(blocks[0].selector).toBe(selectors[example.mode]);
    for (const primitive of primitiveNames()) expect.soft(tokens[primitive], primitive).toBeUndefined();
    for (const value of Object.values(result.declarations)) expect(parse(value)[3]).toBe(1);
  });

  it("each palette adjustment lists exactly the rows that fail without it", () => {
    const computed = Object.fromEntries(examples.map((example) => {
      const palette = metadata(example.adapted).palette;
      const column = example.system === "base16" ? "tinted-base16" : "tinted-base24";
      return [example.id, Object.fromEntries(example.adjustments.map((adjustment) => {
        const reverted = { ...palette, [adjustment.slot]: adjustment.from };
        const body = MAPPED.map((name) => `${name}: ${hexOf(reverted[roster.inputs[name][column]!])};`).join("\n");
        const definition = `${selectors[example.mode]} { ${body} }`;
        const slots = example.mode === "light" ? [genericCss(), definition, builtinCss("dark")] as const
          : [genericCss(), builtinCss("light"), definition] as const;
        const tokens = assemble(example.mode, slots);
        const failing = rows.filter((row) => rowRatio(tokens, row) < row[2]).map(rowName);
        return [adjustment.slot, [...new Set(failing)].sort()];
      }))];
    }));
    process.stdout.write("Computed adjustment rows\n" + JSON.stringify(computed, null, 2) + "\n");
    for (const example of examples) for (const adjustment of example.adjustments) {
      const actual = computed[example.id][adjustment.slot];
      expect.soft(actual, `${example.id} ${adjustment.slot} rows`).toEqual([...adjustment.rows].sort());
      expect.soft(actual.length, `${example.id} ${adjustment.slot} necessary`).toBeGreaterThan(0);
    }
  });

  it("E4: example records are host-independent", () => {
    const facts = [...hostFacts(), ...Object.keys(process.env).filter((key) => key.startsWith("PB_TINTED_")).sort()
      .map((key) => process.env[key]).filter((value): value is string => Boolean(value))];
    for (const value of stringValues(sources)) for (const fact of facts) expect.soft(value).not.toContain(fact);
    for (const field of ["pinned", "adapted", "generated"] as const) {
      for (const example of examples) {
        const relative = example[field];
        expect.soft(relative).not.toContain("\\");
        expect.soft(relative.startsWith("/")).toBe(false);
        expect.soft(() => exactPath(relative)).not.toThrow();
      }
      const directory = field === "pinned" ? "sources/upstream" : field === "adapted" ? "sources/adapted" : "generated";
      expect.soft(fs.readdirSync(path.join(PRODUCTION_TEMPLATE_DIR, directory)).sort())
        .toEqual(examples.map((example) => path.basename(example[field])).sort());
    }
  });
});
