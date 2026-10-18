import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { hostFacts, requiredEnv, stringValues } from "./pipeline";
import {
  CASES_FILE, FROZEN_FILE, OBSERVED_FILE, applyEdit, classifyOracle, corpusTexts, generateFuzz, handText,
  loadCases, observations, sha256, type OracleRecord,
} from "./oracle";

const cases = loadCases();
const corpus = corpusTexts();
const maximumInvocationSeconds = 0.028027667;
const generationTimeout = Math.max(
  120000,
  Math.ceil((corpus.size + cases.hand.length + cases.fuzz.count) * maximumInvocationSeconds * 10000) + 10000,
);

function synthetic(stderr: string, exit = 1) {
  const { sample } = observations();
  return { ...sample, exit, stderr, files: [] as string[], rendered: {} as Record<string, string> };
}

describe("scheme oracle", () => {
  it("the observed record matches the frozen builder outcomes", () => {
    const { record } = observations();
    if (!fs.existsSync(FROZEN_FILE)) {
      throw new Error(`Missing scheme oracle record; review and copy by hand: ${OBSERVED_FILE}`);
    }
    expect(record, `Scheme oracle record is stale; review and copy by hand: ${OBSERVED_FILE}`)
      .toEqual(JSON.parse(fs.readFileSync(FROZEN_FILE, "utf8")));
  }, generationTimeout);

  it("the record identifies the corpus, cases and complete fuzz configuration", () => {
    const { record } = observations();
    expect(record.corpusCommit).toBe(path.basename(requiredEnv("PB_TINTED_CORPUS")));
    expect(record.fuzzConfig).toEqual(cases.fuzz);
    expect(record.hand.map(({ id, base, replace, text, expect: expected }) =>
      ({ id, base, ...(replace === undefined ? {} : { replace }), ...(text === undefined ? {} : { text }), expect: expected })))
      .toEqual(cases.hand);
    expect(record.corpus.map((entry) => entry.path)).toEqual([...corpus.keys()]);
  }, generationTimeout);

  it("literal replacements find their first target and preserve substitution markers", () => {
    for (const entry of cases.hand) {
      expect(sha256(handText(entry, corpus)), entry.id).toBe(observations().record.hand.find((item) => item.id === entry.id)?.inputSha256);
    }
    const source = new Map([["source", "token token"]]);
    const entry = { id: "literal", base: "source", replace: [["token", "$&$1"]] as [string, string][], expect: "accepted" };
    expect(handText(entry, source)).toBe("$&$1 token");
    expect(() => handText({ ...entry, replace: [["absent", "value"]] }, source)).toThrow("Replacement target missing");
  }, generationTimeout);

  it("fuzz generation is deterministic and all UTF-16 edits replay to their hashes", () => {
    const first = generateFuzz(cases.fuzz, corpus);
    expect(generateFuzz(cases.fuzz, corpus)).toEqual(first);
    for (const entry of first) {
      const base = corpus.get(entry.base);
      if (base === undefined) throw new Error("Missing generated base");
      expect(sha256(entry.edits.reduce(applyEdit, base)), entry.id).toBe(entry.inputSha256);
    }
    expect(() => applyEdit("value", [0, 0, String.fromCharCode(0xd800)])).toThrow("lone surrogate");
  });

  it("records preserve variant presence independently of the builder selector", () => {
    const hand = observations().record.hand;
    expect(hand.find((entry) => entry.id === "missing-variant")?.variant).toEqual({ present: false, value: null });
    expect(hand.find((entry) => entry.id === "baseline")?.variant).toEqual({ present: true, value: "dark" });
  }, generationTimeout);

  it("the cases file contains escaped control characters", () => {
    const raw = fs.readFileSync(CASES_FILE, "utf8");
    expect(raw).not.toMatch(/[\u0000-\u0009\u000b-\u001f\u007f-\u009f\u2028\u2029\ufeff]/);
    const strings = raw.match(/"(?:\\.|[^"\\])*"/g) ?? [];
    expect(strings.every((value) => !/[\u0000-\u001f\u007f-\u009f\u2028\u2029\ufeff]/.test(value))).toBe(true);
  });

  it("observations and the frozen record contain no host facts", () => {
    const records: OracleRecord[] = [observations().record];
    if (fs.existsSync(FROZEN_FILE)) records.push(JSON.parse(fs.readFileSync(FROZEN_FILE, "utf8")) as OracleRecord);
    for (const record of records) for (const value of stringValues(record)) {
      for (const fact of hostFacts()) expect.soft(value).not.toContain(fact);
    }
  }, generationTimeout);

  it("a measured create-file first line is a filesystem outcome", () => {
    const { sample, record } = observations();
    const stderr = `Error: Unable to create file: ${sample.stagedTemplateDir}/generated/scheme.css\n` +
      "\nCaused by:\n    File name too long (os error 63)\n";
    expect(classifyOracle(synthetic(stderr), record.inputs)).toEqual({ accepted: false, filesystem: true });
  }, generationTimeout);

  it("a deserialize first line echoing an OS error remains a rejection", () => {
    const { sample, record } = observations();
    const stderr = `Error: Unable to deserialize scheme "${sample.schemesDir}/case.yaml": unknown variant (os error 63)\n`;
    expect(classifyOracle(synthetic(stderr), record.inputs)).toEqual({ accepted: false });
  }, generationTimeout);

  it("a complete create-file message echoed after a deserialize first line remains a rejection", () => {
    const { sample, record } = observations();
    const stderr = `Error: Unable to deserialize scheme "${sample.schemesDir}/case.yaml": invalid variant\n` +
      `Error: Unable to create file: ${sample.stagedTemplateDir}/generated/scheme.css\n` +
      "\nCaused by:\n    File name too long (os error 63)\n";
    expect(classifyOracle(synthetic(stderr), record.inputs)).toEqual({ accepted: false });
  }, generationTimeout);

  it("an unknown non-zero stderr shape throws with redacted diagnostics", () => {
    const { record } = observations();
    expect(() => classifyOracle(synthetic("private input with an unknown error"), record.inputs)).toThrow("[redacted]");
  }, generationTimeout);
});
