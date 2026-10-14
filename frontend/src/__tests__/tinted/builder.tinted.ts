import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { FRONTEND_DIR, PROBE_TEMPLATE_DIR, PRODUCTION_TEMPLATE_DIR, SCHEMES_DIR, classifyStderr, hostFacts, requiredEnv, runBuilder, stringValues } from "./pipeline";

type Observation = Pick<ReturnType<typeof runBuilder>, "exit" | "stderrClass" | "files" | "rendered">;
const frozenFile = path.join(PRODUCTION_TEMPLATE_DIR, "fixtures/normalization.json");
let observed: Record<string, Observation>;

function observations(): Record<string, Observation> {
  if (!observed) {
    observed = Object.fromEntries(fs.readdirSync(SCHEMES_DIR).sort().map((file) => {
      const { exit, stderrClass, files, rendered } = runBuilder(PROBE_TEMPLATE_DIR, path.join(SCHEMES_DIR, file));
      return [file, { exit, files, rendered, stderrClass }];
    }));
    fs.mkdirSync(requiredEnv("PB_TINTED_RESULTS"), { recursive: true });
    fs.writeFileSync(path.join(requiredEnv("PB_TINTED_RESULTS"), "normalization-observed.json"), JSON.stringify(observed, null, 2) + "\n");
  }
  return observed;
}

describe("pinned Tinted builder", () => {
  it("K1: the gate runs the pinned builder from the Gradle cache", () => {
    const binary = requiredEnv("PB_TINTED_BUILDER");
    expect(path.isAbsolute(binary)).toBe(true);
    expect(binary.startsWith(path.join(FRONTEND_DIR, "build/tinted/builder") + path.sep)).toBe(true);
    expect(path.basename(binary)).toBe("tinted-builder-rust");
    fs.accessSync(binary, fs.constants.X_OK);
  });

  it("K2: builder version equals the pin", () => {
    const result = spawnSync(requiredEnv("PB_TINTED_BUILDER"), ["--version"], { encoding: "utf8", timeout: 30000 });
    if (result.error) throw result.error;
    expect(result.status).toBe(0);
    expect(result.stdout.trim()).toBe(`tinted-builder-rust ${requiredEnv("PB_TINTED_BUILDER_VERSION")}`);
  });

  it("N: pinned builder normalization matches the frozen record", () => {
    const actual = observations();
    if (!fs.existsSync(frozenFile)) {
      console.log(JSON.stringify(actual, null, 2));
      throw new Error("Missing normalization.json; review normalization-observed.json and freeze it by hand");
    }
    expect(actual).toEqual(JSON.parse(fs.readFileSync(frozenFile, "utf8")));
  });

  it("P: normalization records are host-independent", () => {
    const misleadingPath = path.join(requiredEnv("PB_TINTED_WORK"), "does not contain the required palette properties", "scheme.yaml");
    expect(classifyStderr(1, `Error: Unable to deserialize scheme ${JSON.stringify(misleadingPath)}: hex input is not formatted correctly\n`))
      .toBe("hex-invalid");
    const records = [observations(), ...(fs.existsSync(frozenFile) ? [JSON.parse(fs.readFileSync(frozenFile, "utf8"))] : [])];
    for (const record of records) {
      for (const value of stringValues(record)) for (const fact of hostFacts()) expect.soft(value).not.toContain(fact);
      for (const entry of Object.values(record) as Observation[]) {
        expect.soft(entry.stderrClass).not.toBe("unclassified");
        for (const file of entry.files) {
          expect.soft(file).not.toContain("\\");
          expect.soft(file.startsWith("/")).toBe(false);
        }
      }
    }
  });
});
