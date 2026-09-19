import { describe, expect, it } from "vitest";
import { rootLabel, rootLabelFor, rootOptionLabel } from "../lib/tree";

describe("root display labels", () => {
  it("falls back to the stable root name when displayName is absent", () => {
    const root = { root: "docs", available: true };

    expect(rootLabel(root)).toBe("docs");
    expect(rootOptionLabel(root)).toBe("docs");
  });

  it("renders the configured label while preserving the stable root field", () => {
    const root = { root: "project", displayName: "Plainbase", available: false };

    expect(rootLabel(root)).toBe("Plainbase");
    expect(rootLabelFor([root], "project")).toBe("Plainbase");
    expect(rootLabelFor([root], "missing")).toBe("missing");
    expect(rootOptionLabel(root)).toBe("Plainbase (unavailable)");
    expect(root.root).toBe("project");
  });
});
