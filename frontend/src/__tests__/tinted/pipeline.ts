import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { stripComments, type Tokens } from "../contrastResolver";
import { roster, selectors, type Mode } from "../themeCandidates";
import { isMapping, readSchemeMetadata } from "./schemeMetadata";

export const FRONTEND_DIR = fs.realpathSync.native(fileURLToPath(new URL("../../../", import.meta.url)));
export const PRODUCTION_TEMPLATE_DIR = path.join(path.dirname(FRONTEND_DIR), "contrib/tinted");
export const PROBE_TEMPLATE_DIR = path.join(PRODUCTION_TEMPLATE_DIR, "fixtures/probe");
export const SCHEMES_DIR = path.join(PRODUCTION_TEMPLATE_DIR, "fixtures/schemes");

export const REASONS = {
  preflight: {
    yamlParse: "yaml-parse", rootNotMapping: "root-not-mapping", systemUnsupported: "system-unsupported",
    variantMissing: "variant-missing", variantUnsupported: "variant-unsupported", base16ExtendedSlot: "base16-extended-slot",
    paletteNotString: "palette-not-string", hexShorthand: "hex-shorthand", slugNotCanonical: "slug-not-canonical",
  },
  builder: { builderFailed: "builder-failed", outputMissing: "output-missing" },
  output: {
    outputFileSet: "output-file-set", outputEmpty: "output-empty", templateResidue: "template-residue",
    notSingleBlock: "not-single-block", selectorMismatch: "selector-mismatch", strayContent: "stray-content",
    duplicateDeclaration: "duplicate-declaration", keySetMismatch: "key-set-mismatch", valueNotHex: "value-not-hex",
  },
} as const;

export type Stage = keyof typeof REASONS;
export type Reason = { [S in Stage]: typeof REASONS[S][keyof typeof REASONS[S]] }[Stage];
export type Rejection = { ok: false; stage: Stage; reason: Reason };
export const MAPPED = Object.entries(roster.inputs).filter(([, input]) => input["tinted-base16"] != null).map(([name]) => name);
type FixtureExpectation = { accepted: true; mode: "light" | "dark" } | { stage: Stage; reason: Reason };
export const FIXTURE_EXPECTATIONS = {
  "base16-light.yaml": { accepted: true, mode: "light" },
  "base16-dark.yaml": { accepted: true, mode: "dark" },
  "base24-fallback-equivalent.yaml": { accepted: true, mode: "dark" },
  "base24-distinct.yaml": { accepted: true, mode: "dark" },
  "root-not-mapping.yaml": { stage: "preflight", reason: REASONS.preflight.rootNotMapping },
  "system-unsupported.yaml": { stage: "preflight", reason: REASONS.preflight.systemUnsupported },
  "variant-missing.yaml": { stage: "preflight", reason: REASONS.preflight.variantMissing },
  "variant-unsupported.yaml": { stage: "preflight", reason: REASONS.preflight.variantUnsupported },
  "duplicate-variant.yaml": { stage: "preflight", reason: REASONS.preflight.yamlParse },
  "base16-extended-slot.yaml": { stage: "preflight", reason: REASONS.preflight.base16ExtendedSlot },
  "palette-not-string.yaml": { stage: "preflight", reason: REASONS.preflight.paletteNotString },
  "hex-shorthand.yaml": { stage: "preflight", reason: REASONS.preflight.hexShorthand },
  "base16-incomplete.yaml": { stage: "builder", reason: REASONS.builder.builderFailed },
  "base24-incomplete.yaml": { stage: "builder", reason: REASONS.builder.builderFailed },
  "bad-hex.yaml": { stage: "builder", reason: REASONS.builder.builderFailed },
  "malformed.yaml": { stage: "preflight", reason: REASONS.preflight.yamlParse },
  "slug-not-canonical.yaml": { stage: "preflight", reason: REASONS.preflight.slugNotCanonical },
} as const satisfies Record<string, FixtureExpectation>;

export function isCanonicalSlug(value: unknown): value is string {
  return typeof value === "string" && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value);
}

export function hexOf(scalar: string): string { return "#" + scalar.replace(/^#/, "").toLowerCase(); }

export function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required; run ./gradlew :frontend:tintedTemplateCheck`);
  return value;
}

// Populate further message tails only from the pinned builder's observed stderr.
const STDERR_TAILS = {
  "palette-incomplete": "does not contain the required palette properties",
  "hex-invalid": "hex input is not formatted correctly",
  "duplicate-key": "duplicate entry with key",
  "yaml-syntax": "did not find expected ',' or ']'",
  "palette-not-string": "invalid type: integer",
  "system-unsupported": "expected one of `base16`, `base24`, `tinted8`",
  "variant-unsupported": "expected `dark` or `light`",
} as const;
export type StderrClass = "none" | "unclassified" | keyof typeof STDERR_TAILS | `E${number}` | `W${number}`;

export function classifyStderr(exit: number, stderr: string): StderrClass {
  if (exit === 0 && !stderr.trim()) return "none";
  const message = stderr.match(/Unable to deserialize scheme "(?:\\.|[^"\\])*": ([\s\S]*)/)?.[1];
  const code = (message ?? stderr).match(/\b[EW]\d{3}:/)?.[0].slice(0, -1);
  if (code) return code as StderrClass;
  if (message !== undefined) {
    for (const [messageClass, tail] of Object.entries(STDERR_TAILS)) {
      if (message.includes(tail)) return messageClass as StderrClass;
    }
  }
  return "unclassified";
}

function outputFiles(dir: string): string[] {
  return fs.readdirSync(dir).sort().flatMap((name) => {
    const file = path.join(dir, name);
    const entry = fs.lstatSync(file);
    if (entry.isDirectory()) return outputFiles(file);
    if (!entry.isFile()) throw new Error(`Unexpected Tinted output entry: ${file}`);
    return [file];
  });
}

export function runBuilder(templateDir: string, schemeFile: string) {
  const work = requiredEnv("PB_TINTED_WORK");
  const binary = requiredEnv("PB_TINTED_BUILDER");
  if (!path.isAbsolute(work) || !path.isAbsolute(binary)) throw new Error("Tinted work and builder paths must be absolute");
  const stem = path.basename(schemeFile, ".yaml");
  const template = path.basename(templateDir);
  if (!isCanonicalSlug(stem) || !isCanonicalSlug(template)) throw new Error("Tinted case names must be canonical");
  const caseDir = path.join(work, template, stem);
  fs.rmSync(caseDir, { recursive: true, force: true });
  const stagedTemplateDir = path.join(caseDir, "template");
  fs.mkdirSync(stagedTemplateDir, { recursive: true });
  fs.cpSync(path.join(templateDir, "templates"), path.join(stagedTemplateDir, "templates"), { recursive: true });
  const schemes = path.join(caseDir, "schemes");
  fs.mkdirSync(schemes);
  fs.copyFileSync(schemeFile, path.join(schemes, path.basename(schemeFile)));
  const result = spawnSync(binary, ["build", stagedTemplateDir, "--schemes-dir", schemes, "--data-dir", path.join(caseDir, "data")],
    { encoding: "utf8", timeout: 30000 });
  if (result.error) throw result.error;
  if (result.status === null) throw new Error(`Tinted builder terminated by ${result.signal}`);
  const files = outputFiles(stagedTemplateDir)
    .map((file) => path.relative(stagedTemplateDir, file).split(path.sep).join("/"))
    .filter((file) => !file.startsWith("templates/"))
    .sort();
  const rendered = Object.fromEntries(files.map((file) => [file, fs.readFileSync(path.join(stagedTemplateDir, file), "utf8")]));
  return { exit: result.status, stderr: result.stderr, stderrClass: classifyStderr(result.status, result.stderr), files, rendered,
    stagedTemplateDir, schemesDir: schemes };
}

export function stringValues(value: unknown): string[] {
  if (typeof value === "string") return [value];
  if (Array.isArray(value)) return value.flatMap(stringValues);
  if (value !== null && typeof value === "object") return Object.values(value).flatMap(stringValues);
  return [];
}

export function hostFacts(): string[] {
  return [
    requiredEnv("PB_TINTED_WORK"), requiredEnv("PB_TINTED_RESULTS"), FRONTEND_DIR, path.dirname(FRONTEND_DIR),
    "aarch64-apple-darwin", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-gnu",
    "macos-arm64", "linux-x64", "linux-arm64",
  ];
}

function reject(stage: Stage, reason: Reason): Rejection { return { ok: false, stage, reason }; }

export function checkOutputFiles(files: string[], system: string, slug: unknown = undefined):
  { ok: true; file: string; slug: string } | Rejection {
  if (files.length !== 1) return reject("output", REASONS.output.outputFileSet);
  const file = files[0];
  if (slug !== undefined && file !== `generated/${system}-${slug}.css`) return reject("output", REASONS.output.outputFileSet);
  const prefix = `generated/${system}-`;
  if (!file.startsWith(prefix) || !file.endsWith(".css")) return reject("output", REASONS.output.outputFileSet);
  const observedSlug = file.slice(prefix.length, -4);
  if (!isCanonicalSlug(observedSlug)) return reject("output", REASONS.output.outputFileSet);
  return { ok: true, file, slug: observedSlug };
}

export function validateOutput(css: string, variant: unknown): { ok: true; declarations: Tokens } | Rejection {
  if (!css.trim()) return reject("output", REASONS.output.outputEmpty);
  if (css.includes("{{") || css.includes("}}")) return reject("output", REASONS.output.templateResidue);
  const match = stripComments(css).trim().match(/^([^{};]*)\{([^{}]*)\}$/);
  if (!match) return reject("output", REASONS.output.notSingleBlock);
  if (match[1].trim() !== selectors[variant as Mode]) return reject("output", REASONS.output.selectorMismatch);
  const body = match[2];
  const pattern = /(--[\w-]+)\s*:\s*([^;]*);/g;
  const entries = [...body.matchAll(pattern)].map((entry) => [entry[1], entry[2].trim()]);
  if (body.replace(pattern, "").trim()) return reject("output", REASONS.output.strayContent);
  const keys = entries.map(([name]) => name);
  if (new Set(keys).size !== keys.length) return reject("output", REASONS.output.duplicateDeclaration);
  const declarations = Object.fromEntries(entries);
  if (JSON.stringify(Object.keys(declarations).sort()) !== JSON.stringify([...MAPPED].sort())) {
    return reject("output", REASONS.output.keySetMismatch);
  }
  if (entries.some(([, value]) => !/^#[0-9a-f]{6}$/.test(value))) return reject("output", REASONS.output.valueNotHex);
  return { ok: true, declarations };
}

export function adapt(file: string): { ok: true; mode: Mode; slug: string; css: string; declarations: Tokens } | Rejection {
  const metadata = readSchemeMetadata(file);
  if ("reason" in metadata) return { ok: false, ...metadata };
  if (!isMapping(metadata.root)) return reject("preflight", REASONS.preflight.rootNotMapping);
  const system = metadata.system;
  if (system !== "base16" && system !== "base24") return reject("preflight", REASONS.preflight.systemUnsupported);
  if (!metadata.variant.declared) return reject("preflight", REASONS.preflight.variantMissing);
  if (metadata.variant.value !== "light" && metadata.variant.value !== "dark") {
    return reject("preflight", REASONS.preflight.variantUnsupported);
  }
  const palette = metadata.palette !== null && typeof metadata.palette === "object" ? metadata.palette as Record<string, unknown> : {};
  if (system === "base16" && Object.keys(palette).some((key) => /^base1[0-7]$/.test(key))) {
    return reject("preflight", REASONS.preflight.base16ExtendedSlot);
  }
  if (Object.values(palette).some((value) => typeof value !== "string")) return reject("preflight", REASONS.preflight.paletteNotString);
  if (Object.values(palette).some((value) => typeof value === "string" && /^#?[0-9a-fA-F]{3}$/.test(value))) {
    return reject("preflight", REASONS.preflight.hexShorthand);
  }
  if (metadata.slug.declared && !isCanonicalSlug(metadata.slug.value)) return reject("preflight", REASONS.preflight.slugNotCanonical);
  const result = runBuilder(PRODUCTION_TEMPLATE_DIR, file);
  if (result.exit !== 0) return reject("builder", REASONS.builder.builderFailed);
  if (!result.files.length) return reject("builder", REASONS.builder.outputMissing);
  const output = checkOutputFiles(result.files, system, metadata.slug.declared ? metadata.slug.value : undefined);
  if (!output.ok) return output;
  const css = result.rendered[output.file];
  const checked = validateOutput(css, metadata.variant.value);
  if (!checked.ok) return checked;
  return { ok: true, mode: metadata.variant.value, slug: output.slug, css, declarations: checked.declarations };
}
