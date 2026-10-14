import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { parseDocument } from "yaml";
import { topLevelBlocks } from "../contrastResolver";
import { assemble, builtinCss, CANDIDATE_ROLES, genericCss, roster, selectors, tokenValue } from "../themeCandidates";
import {
  adapt, checkOutputFiles, FIXTURE_EXPECTATIONS, hexOf, isCanonicalSlug, MAPPED, PRODUCTION_TEMPLATE_DIR,
  REASONS, requiredEnv, runBuilder, SCHEMES_DIR, validateOutput,
} from "./pipeline";
import { isMapping, readSchemeMetadata } from "./schemeMetadata";

const listing = fs.readdirSync(SCHEMES_DIR).sort();
const fallbacks = (roster as typeof roster & { tintedBase24Fallbacks: Record<string, string> }).tintedBase24Fallbacks;
const refined = [
  "--pb-theme-ring", "--pb-theme-destructive", "--pb-theme-warning", "--pb-theme-success",
  "--pb-theme-syntax-keyword", "--pb-theme-syntax-string", "--pb-theme-syntax-title", "--pb-theme-syntax-attribute",
];
const extrasUsed = ["base12", "base13", "base14", "base16", "base17"];
const extrasUnused = ["base10", "base11", "base15"];

function fixture(name: string): string {
  const file = listing.find((entry) => entry === name);
  if (!file) throw new Error(`Missing exact-case fixture ${name}`);
  return path.join(SCHEMES_DIR, file);
}

function palette(name: string): Record<string, string> {
  const metadata = readSchemeMetadata(fixture(name));
  if ("reason" in metadata || !isMapping(metadata.palette)) throw new Error(`Invalid palette oracle for ${name}`);
  expect(Object.values(metadata.palette).every((value) => typeof value === "string")).toBe(true);
  return metadata.palette as Record<string, string>;
}

function accepted(name: string) {
  const result = adapt(fixture(name));
  if (!result.ok) throw new Error(JSON.stringify(result));
  return result;
}

function synthetic(name: string, slug: unknown, remove = false): string {
  const document = parseDocument(fs.readFileSync(fixture("base16-dark.yaml"), "utf8"));
  if (remove) document.delete("slug"); else document.set("slug", slug);
  const dir = path.join(requiredEnv("PB_TINTED_WORK"), "metadata");
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${name}.yaml`);
  fs.writeFileSync(file, document.toString());
  return file;
}

describe("Tinted mapping and acceptance", () => {
  it("X0: fixture listing equals the expectation table", () => {
    expect.soft(listing).toEqual(Object.keys(FIXTURE_EXPECTATIONS).sort());
    for (const file of listing) expect.soft(isCanonicalSlug(path.basename(file, ".yaml")), file).toBe(true);
  });

  it.each([
    ["M1", "base16-light.yaml", "light"], ["M2", "base16-dark.yaml", "dark"],
  ] as const)("%s: Base16 %s without extended keys maps the roster slots", (_id, file, mode) => {
    const result = accepted(file);
    expect(result.mode).toBe(mode);
    const source = palette(file);
    for (const name of MAPPED) {
      expect.soft(result.declarations[name], name).toBe(hexOf(source[roster.inputs[name]["tinted-base16"]!]));
    }
  });

  it("M3: fallback-equivalent Base24 yields the Base16 projection", () => {
    const baseline = accepted("base16-dark.yaml");
    const enriched = accepted("base24-fallback-equivalent.yaml");
    expect(topLevelBlocks(enriched.css).map((entry) => entry.selector)).toEqual(topLevelBlocks(baseline.css).map((entry) => entry.selector));
    expect(enriched.declarations).toEqual(baseline.declarations);
    const before = assemble("dark", [genericCss(), builtinCss("light"), baseline.css]);
    const after = assemble("dark", [genericCss(), builtinCss("light"), enriched.css]);
    for (const role of CANDIDATE_ROLES) expect.soft(tokenValue(after, role), role).toEqual(tokenValue(before, role));
  });

  it("M4: distinct Base24 extras change exactly the refined roles", () => {
    const baseline = accepted("base16-dark.yaml");
    const enriched = accepted("base24-distinct.yaml");
    const source = palette("base24-distinct.yaml");
    expect(Object.keys(enriched.declarations).sort()).toEqual([...MAPPED].sort());
    for (const name of MAPPED) {
      const value = enriched.declarations[name];
      expect.soft(value, name).toBe(hexOf(source[roster.inputs[name]["tinted-base24"]!]));
      if (refined.includes(name)) expect.soft(value, name).not.toBe(baseline.declarations[name]);
      else expect.soft(value, name).toBe(baseline.declarations[name]);
      for (const extra of extrasUnused) expect.soft(value, `${name} / ${extra}`).not.toBe(hexOf(source[extra]));
    }
    expect(enriched.declarations["--pb-theme-destructive"]).toBe(hexOf(source.base12));
  });

  it("M5: roster mapping is complete and fallback-consistent", () => {
    expect(MAPPED).toHaveLength(18);
    const omitted = Object.entries(roster.inputs).filter(([, input]) => input["tinted-base16"] === null);
    expect(omitted).toHaveLength(6);
    for (const [, input] of omitted) expect(input["tinted-base24"]).toBeNull();
    expect(fallbacks).toEqual({
      base10: "base00", base11: "base00", base12: "base08", base13: "base0A",
      base14: "base0B", base15: "base0C", base16: "base0D", base17: "base0E",
    });
    for (const input of Object.values(roster.inputs)) {
      const slot = input["tinted-base24"];
      expect.soft(slot == null ? slot : fallbacks[slot] ?? slot).toBe(input["tinted-base16"]);
    }
    const actualRefined = Object.entries(roster.inputs)
      .filter(([, input]) => input["tinted-base16"] !== input["tinted-base24"]).map(([name]) => name).sort();
    expect(actualRefined).toEqual([...refined].sort());
    const used = [...new Set(actualRefined.map((name) => roster.inputs[name]["tinted-base24"]))].sort();
    expect(used).toEqual(extrasUsed);
  });

  for (const file of Object.keys(FIXTURE_EXPECTATIONS).sort() as Array<keyof typeof FIXTURE_EXPECTATIONS>) {
    const expected = FIXTURE_EXPECTATIONS[file];
    if (!("stage" in expected)) continue;
    it(`X: rejects ${file} at ${expected.stage}`, () => {
      expect(adapt(fixture(file))).toEqual({ ok: false, ...expected });
      if (file === "variant-missing.yaml") {
        const direct = runBuilder(PRODUCTION_TEMPLATE_DIR, fixture(file));
        expect(direct.exit).toBe(0);
        expect(direct.files).toHaveLength(1);
        expect(topLevelBlocks(direct.rendered[direct.files[0]]).map((entry) => entry.selector)).toEqual([selectors.dark]);
      }
    });
  }

  it.each([["null", null], ["integer", 42]] as const)("X: rejects a declared %s slug", (label, slug) => {
    expect(adapt(synthetic(`slug-${label}`, slug))).toEqual({ ok: false, stage: "preflight", reason: REASONS.preflight.slugNotCanonical });
  });

  it("X: an absent slug returns the builder-observed slug", () => {
    const result = adapt(synthetic("slug-missing", undefined, true));
    if (!result.ok) throw new Error(JSON.stringify(result));
    expect(result.slug).toBe("base16-dark");
    expect(result.mode).toBe("dark");
  });

  const outputMutations = [
    ["empty", REASONS.output.outputEmpty], ["template residue", REASONS.output.templateResidue],
    ["invalid hex", REASONS.output.valueNotHex], ["missing input", REASONS.output.keySetMismatch],
    ["second scope", REASONS.output.notSingleBlock], ["top-level import", REASONS.output.notSingleBlock],
    ["trailing content", REASONS.output.notSingleBlock], ["duplicate declaration", REASONS.output.duplicateDeclaration],
    ["wrong selector", REASONS.output.selectorMismatch], ["stray declaration", REASONS.output.strayContent],
    ["other variant", REASONS.output.selectorMismatch],
  ] as const;

  it.each(outputMutations)("O: output check rejects %s", (mutation, reason) => {
    const result = accepted("base16-dark.yaml");
    expect(validateOutput(result.css, "dark").ok).toBe(true);
    const declaration = `  --pb-theme-primary: ${result.declarations["--pb-theme-primary"]};`;
    let css = result.css;
    switch (mutation) {
      case "empty": css = ""; break;
      case "template residue": css = css.replace(declaration, "  --pb-theme-primary: #{{base12-hex}};"); break;
      case "invalid hex": css = css.replace(declaration, "  --pb-theme-primary: #;"); break;
      case "missing input": css = css.replace(declaration, ""); break;
      case "second scope": css += `\n${selectors.light} { ${declaration} }`; break;
      case "top-level import": css = '@import "x.css";\n' + css; break;
      case "trailing content": css += "x"; break;
      case "duplicate declaration": css = css.replace(declaration, `${declaration}\n${declaration}`); break;
      case "wrong selector": css = css.replace(selectors.dark, ":root"); break;
      case "stray declaration": css = css.replace(declaration, `${declaration}\n  color-scheme: dark;`); break;
      case "other variant": css = accepted("base16-light.yaml").css; break;
    }
    expect(validateOutput(css, "dark")).toEqual({ ok: false, stage: "output", reason });
  });

  it("O-files: output file-set check rejects an extra file", () => {
    const result = runBuilder(PRODUCTION_TEMPLATE_DIR, fixture("base16-dark.yaml"));
    expect(result.exit).toBe(0);
    expect(checkOutputFiles(result.files, "base16", "base16-dark").ok).toBe(true);
    expect(checkOutputFiles(result.files, "base16")).toEqual({ ok: true, file: result.files[0], slug: "base16-dark" });
    expect(checkOutputFiles([...result.files, "generated/base16-extra.css"], "base16", "base16-dark"))
      .toEqual({ ok: false, stage: "output", reason: REASONS.output.outputFileSet });
  });
});
