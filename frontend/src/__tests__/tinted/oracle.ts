import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { performance } from "node:perf_hooks";
import { isMap, parseDocument } from "yaml";
import { stripComments } from "../contrastResolver";
import { selectors } from "../themeCandidates";
import { FRONTEND_DIR, PRODUCTION_TEMPLATE_DIR, requiredEnv, runBuilder, validateOutput } from "./pipeline";

export type HandCase = {
  id: string; base: string | null; replace?: [string, string][]; text?: string; expect: string;
};
export type FuzzConfig = { seed: number; count: number; editsPerCase: [number, number]; tokens: string[]; ops: string[] };
export type OracleCases = {
  rules: {
    builderRejects: string[];
    classes: { id: string; docs: string; rules: string[]; witnesses: Record<string, string> }[];
    mode: string[];
  };
  hand: HandCase[];
  fuzz: FuzzConfig;
};
export type Variant = { present: boolean; value: string | null };
export type BuilderOutcome =
  | { accepted: true; selector: "light" | "dark"; values: string[] }
  | { accepted: false; filesystem?: true };
export type Edit = [offset: number, deleteCount: number, insert: string];
export type CorpusRecord = { path: string; sha256: string; variant: Variant; builder: BuilderOutcome };
export type HandRecord = HandCase & { inputSha256: string; variant: Variant; builder: BuilderOutcome };
export type FuzzCase = { id: string; base: string; edits: Edit[]; inputSha256: string };
export type FuzzRecord = FuzzCase & { variant: Variant; builder: BuilderOutcome };
export type OracleRecord = {
  builder: string; corpusCommit: string; fuzzSeed: number; fuzzCount: number; fuzzConfig: FuzzConfig; inputs: string[];
  corpus: CorpusRecord[]; hand: HandRecord[]; fuzz: FuzzRecord[];
};
type BuilderResult = ReturnType<typeof runBuilder>;
type Span = { start: number; end: number; next: number };

export const DATA_DIR = path.resolve(FRONTEND_DIR, "../server/src/test/resources/tinted");
export const CASES_FILE = path.join(DATA_DIR, "scheme-oracle-cases.json");
export const FROZEN_FILE = path.join(DATA_DIR, "scheme-oracle.json");
export const OBSERVED_FILE = path.join(requiredEnv("PB_TINTED_RESULTS"), "scheme-oracle-observed.json");

export function sha256(text: string): string {
  return createHash("sha256").update(text, "utf8").digest("hex");
}

function wellFormed(text: string): boolean {
  return !Array.from(text).some((char) => char.length === 1 && char.charCodeAt(0) >= 0xd800 && char.charCodeAt(0) <= 0xdfff);
}

export function loadCases(): OracleCases {
  return JSON.parse(fs.readFileSync(CASES_FILE, "utf8")) as OracleCases;
}

export function corpusTexts(): Map<string, string> {
  const root = requiredEnv("PB_TINTED_CORPUS");
  const entries = ["base16", "base24"].flatMap((system) => fs.readdirSync(path.join(root, system))
    .filter((name) => /\.(yaml|yml)$/.test(name)).map((name) => `${system}/${name}`)).sort();
  return new Map(entries.map((name) => [name, fs.readFileSync(path.join(root, name), "utf8")]));
}

export function applyEdit(text: string, [offset, deleteCount, insert]: Edit): string {
  if (!Number.isInteger(offset) || !Number.isInteger(deleteCount) || offset < 0 || deleteCount < 0 ||
      offset + deleteCount > text.length) throw new Error("Oracle edit is outside the input");
  const result = text.slice(0, offset) + insert + text.slice(offset + deleteCount);
  if (!wellFormed(result)) throw new Error("Oracle edit leaves a lone surrogate");
  return result;
}

export function handText(entry: HandCase, corpus: Map<string, string>): string {
  if (entry.text !== undefined) {
    if (entry.replace !== undefined || !wellFormed(entry.text)) throw new Error(`Invalid embedded case ${entry.id}`);
    return entry.text;
  }
  const original = entry.base === null ? undefined : corpus.get(entry.base);
  if (original === undefined || entry.replace === undefined) throw new Error(`Missing case source ${entry.id}`);
  return entry.replace.reduce((text, [old, insert]) => {
    const offset = text.indexOf(old);
    if (offset < 0 || old.length === 0) throw new Error(`Replacement target missing for ${entry.id}`);
    return applyEdit(text, [offset, old.length, insert]);
  }, original);
}

function mulberry32(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = Math.imul(state ^ (state >>> 15), 1 | state);
    value ^= value + Math.imul(value ^ (value >>> 7), 61 | value);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function spans(text: string): Span[] {
  let offset = 0;
  return text.split("\n").flatMap((line) => {
    const start = offset;
    offset += line.length + 1;
    return start < text.length ? [{ start, end: start + line.length, next: Math.min(offset, text.length) }] : [];
  });
}

function offsets(text: string): number[] {
  return Array.from({ length: text.length + 1 }, (_, index) => index)
    .filter((index) => index === 0 || index === text.length ||
      !(text.charCodeAt(index - 1) >= 0xd800 && text.charCodeAt(index - 1) <= 0xdbff &&
        text.charCodeAt(index) >= 0xdc00 && text.charCodeAt(index) <= 0xdfff));
}

function editFor(text: string, op: string, token: string, random: () => number): Edit {
  const lines = spans(text);
  const line = lines[Math.floor(random() * lines.length)] ?? { start: 0, end: 0, next: 0 };
  const content = text.slice(line.start, line.end);
  const width = 1 + Math.floor(random() * 2);
  switch (op) {
    case "insert-line-start": return [line.start, 0, token];
    case "insert-after-colon": {
      const found = text.indexOf(": ");
      return [found < 0 ? line.end : found + 2, 0, token];
    }
    case "insert-before-quote": {
      const found = text.indexOf('"');
      return [found < 0 ? line.end : found, 0, token];
    }
    case "insert-after-quote": {
      const found = text.lastIndexOf('"');
      return [found < 0 ? line.end : found + 1, 0, token];
    }
    case "insert-line-end": return [line.end, 0, token];
    case "append-unterminated": return [text.endsWith("\n") ? text.length - 1 : text.length, text.endsWith("\n") ? 1 : 0, token];
    case "insert-offset": {
      const candidates = offsets(text);
      return [candidates[Math.floor(random() * candidates.length)], 0, token];
    }
    case "delete-line": return [line.start, line.next - line.start, ""];
    case "duplicate-line": return [line.start, 0, text.slice(line.start, line.next)];
    case "swap-lines": {
      const other = lines[Math.floor(random() * lines.length)] ?? line;
      const [first, last] = line.start < other.start ? [line, other] : [other, line];
      if (first.start === last.start) return [first.start, 0, ""];
      return [first.start, last.next - first.start,
        text.slice(last.start, last.next) + text.slice(first.next, last.start) + text.slice(first.start, first.next)];
    }
    case "indent-line": return [line.start, 0, " ".repeat(width)];
    case "dedent-line": return [line.start, Math.min(width, content.match(/^ +/)?.[0].length ?? 0), ""];
    case "change-case": return [line.start, content.length, random() < 0.5 ? content.toLowerCase() : content.toUpperCase()];
    case "strip-quotes":
    case "single-quotes": {
      const first = content.indexOf('"'), last = content.lastIndexOf('"');
      if (first < 0 || first === last) return [line.start, 0, ""];
      const quote = op === "single-quotes" ? "'" : "";
      return [line.start, content.length,
        content.slice(0, first) + quote + content.slice(first + 1, last) + quote + content.slice(last + 1)];
    }
    default: throw new Error(`Unknown oracle operation ${op}`);
  }
}

export function generateFuzz(config: FuzzConfig, corpus: Map<string, string>): FuzzCase[] {
  if (!config.tokens.length || !config.ops.length || !corpus.size ||
      config.tokens.some((token) => !wellFormed(token))) throw new Error("Invalid fuzz vocabulary");
  const random = mulberry32(config.seed);
  const paths = [...corpus.keys()].sort();
  return Array.from({ length: config.count }, (_, index) => {
    const base = paths[Math.floor(random() * paths.length)];
    let text = corpus.get(base)!;
    const count = config.editsPerCase[0] + Math.floor(random() * (config.editsPerCase[1] - config.editsPerCase[0] + 1));
    const edits = Array.from({ length: count }, () => {
      const op = config.ops[Math.floor(random() * config.ops.length)];
      const token = config.tokens[Math.floor(random() * config.tokens.length)];
      const edit = editFor(text, op, token, random);
      text = applyEdit(text, edit);
      return edit;
    });
    return { id: `generated-${index}`, base, edits, inputSha256: sha256(text) };
  });
}

export function variantOf(text: string): Variant {
  const document = parseDocument(text, { uniqueKeys: false, prettyErrors: false, version: "1.2" });
  if (!isMap(document.contents)) return { present: false, value: null };
  const present = document.has("variant");
  const value: unknown = present ? document.get("variant") : null;
  return { present, value: typeof value === "string" ? value : null };
}

function templateInputs(): string[] {
  const text = fs.readFileSync(path.join(PRODUCTION_TEMPLATE_DIR, "templates/default.mustache"), "utf8");
  const inputs = [...text.matchAll(/^\s+(--pb-theme-[a-z-]+):/gm)].map((match) => match[1]);
  if (inputs.length !== 18 || new Set(inputs).size !== 18) throw new Error("Template must contain 18 unique inputs");
  return inputs;
}

export function classifyOracle(result: BuilderResult, inputs: string[]): BuilderOutcome {
  if (result.exit === 0) {
    const files = result.files.filter((file) => file.startsWith("generated/"));
    if (files.length === 0) return { accepted: false };
    if (files.length !== 1) throw new Error("Unexpected Tinted output count");
    const css = result.rendered[files[0]];
    const selector = stripComments(css).trim().match(/^([^{};]*)\{/)?.[1].trim();
    const mode = (Object.keys(selectors) as ("light" | "dark")[]).find((candidate) => selectors[candidate] === selector);
    if (!mode) throw new Error("Unknown Tinted output selector");
    const validated = validateOutput(css, mode);
    if (!validated.ok) throw new Error(`Invalid Tinted output: ${validated.reason}`);
    const names = [...stripComments(css).matchAll(/(--pb-theme-[a-z-]+)\s*:/g)].map((match) => match[1]);
    if (JSON.stringify(names) !== JSON.stringify(inputs)) throw new Error("Tinted declaration order differs from the template");
    return { accepted: true, selector: mode, values: inputs.map((name) => validated.declarations[name]) };
  }
  const firstLine = result.stderr.split(/\r?\n/, 1)[0];
  if (firstLine.startsWith(`Error: Unable to deserialize scheme "${result.schemesDir}/`)) return { accepted: false };
  if (firstLine.startsWith(`Error: Unable to create file: ${result.stagedTemplateDir}/generated/`)) {
    return { accepted: false, filesystem: true };
  }
  throw new Error(`Unknown Tinted builder outcome (exit ${result.exit}); stderr first line: [redacted]`);
}

function asciiJson(value: unknown): string {
  const serialized = JSON.stringify(value);
  if (serialized === undefined) throw new Error("Oracle value is not JSON");
  return serialized.split("").map((char) => char.charCodeAt(0) > 0x7e
    ? "\\u" + char.charCodeAt(0).toString(16).padStart(4, "0") : char).join("");
}

export function serializeRecord(record: OracleRecord): string {
  const { corpus, hand, fuzz, ...header } = record;
  const parts = Object.entries(header).map(([key, value]) => `  ${asciiJson(key)}: ${asciiJson(value)}`);
  for (const [key, entries] of [["corpus", corpus], ["hand", hand], ["fuzz", fuzz]] as const) {
    parts.push(`  ${asciiJson(key)}: [\n${entries.map((entry) => "    " + asciiJson(entry)).join(",\n")}\n  ]`);
  }
  return "{\n" + parts.join(",\n") + "\n}\n";
}

let cached: { record: OracleRecord; sample: BuilderResult; wallSeconds: number } | undefined;

export function observations(): { record: OracleRecord; sample: BuilderResult; wallSeconds: number } {
  if (cached) return cached;
  const started = performance.now();
  const cases = loadCases();
  const corpus = corpusTexts();
  const fuzz = generateFuzz(cases.fuzz, corpus);
  const inputs = templateInputs();
  const sourceDir = path.join(requiredEnv("PB_TINTED_WORK"), "oracle-inputs");
  fs.mkdirSync(sourceDir, { recursive: true });
  let sample: BuilderResult | undefined;
  let ordinal = 0;
  const measure = (text: string): { variant: Variant; builder: BuilderOutcome } => {
    const file = path.join(sourceDir, `case-${ordinal++}.yaml`);
    fs.writeFileSync(file, text, "utf8");
    const result = runBuilder(PRODUCTION_TEMPLATE_DIR, file);
    sample ??= result;
    return { variant: variantOf(text), builder: classifyOracle(result, inputs) };
  };
  const record: OracleRecord = {
    builder: `tinted-builder-rust ${requiredEnv("PB_TINTED_BUILDER_VERSION")}`,
    corpusCommit: path.basename(requiredEnv("PB_TINTED_CORPUS")),
    fuzzSeed: cases.fuzz.seed, fuzzCount: cases.fuzz.count, fuzzConfig: cases.fuzz, inputs,
    corpus: [...corpus].map(([file, text]) => ({ path: file, sha256: sha256(text), ...measure(text) })),
    hand: cases.hand.map((entry) => {
      const text = handText(entry, corpus);
      return { ...entry, inputSha256: sha256(text), ...measure(text) };
    }),
    fuzz: fuzz.map((entry) => {
      const base = corpus.get(entry.base);
      if (base === undefined) throw new Error("Generated case base is missing");
      const text = entry.edits.reduce(applyEdit, base);
      return { ...entry, ...measure(text) };
    }),
  };
  const wallSeconds = (performance.now() - started) / 1000;
  fs.mkdirSync(path.dirname(OBSERVED_FILE), { recursive: true });
  fs.writeFileSync(OBSERVED_FILE, serializeRecord(record));
  fs.writeFileSync(path.join(path.dirname(OBSERVED_FILE), "scheme-oracle-metrics.json"),
    JSON.stringify({ cases: ordinal, corpus: record.corpus.length, hand: record.hand.length, fuzz: record.fuzz.length,
      wallSeconds }, null, 2) + "\n");
  console.info(`Scheme oracle: ${ordinal} cases measured in ${wallSeconds.toFixed(3)} seconds`);
  if (!sample) throw new Error("Oracle corpus is empty");
  cached = { record, sample, wallSeconds };
  return cached;
}
