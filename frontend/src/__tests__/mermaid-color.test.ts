// @vitest-environment node
import { readFileSync } from "node:fs";
import { ColorSpace, parse, XYZ_D65 } from "colorjs.io/fn";
import { describe, expect, it } from "vitest";
import { toMermaidColor } from "../lib/mermaidColor";

type ColorCase = {
  name: string;
  source: string;
  expected: string | null;
  reject?: boolean;
};

const fixture = JSON.parse(readFileSync("src/__tests__/fixtures/theme/mermaid-colors.json", "utf8")) as {
  cases: ColorCase[];
};

describe("toMermaidColor", () => {
  it.each(fixture.cases)("$name", ({ source, expected, reject }) => {
    if (reject) expect(() => toMermaidColor(source)).toThrow();
    else expect(toMermaidColor(source)).toBe(expected);
  });

  it("rejects a parseable space outside the allowlist", () => {
    // Registering XYZ D65 lets parse succeed, so only the allowlist can refuse it.
    ColorSpace.register(XYZ_D65);
    expect(parse("color(xyz-d65 0.2 0.3 0.4)").spaceId).toBe("xyz-d65");
    expect(() => toMermaidColor("color(xyz-d65 0.2 0.3 0.4)")).toThrow("Unsupported computed color space");
  });
});
