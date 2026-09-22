// @vitest-environment node
import { fileURLToPath } from "node:url";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { parseDiagramPath } from "./diagramPath";

interface DiagramPathCase {
  raw: string;
  valid: boolean;
  root?: string;
  path?: string;
}

const cases = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL(
        "../../../server/src/test/resources/diagram-paths.json",
        import.meta.url,
      ),
    ),
    "utf8",
  ),
) as DiagramPathCase[];

describe("parseDiagramPath", () => {
  it("matches the shared server path corpus", () => {
    for (const entry of cases) {
      const parsed = parseDiagramPath(`/browse/${entry.raw}`);
      if (!entry.valid) {
        expect(parsed, entry.raw).toBeNull();
      } else {
        expect(parsed, entry.raw).toEqual({ root: entry.root, path: entry.path });
      }
    }
  });

  it("decodes once and preserves the browser-only route boundary", () => {
    expect(parseDiagramPath("/browse/docs/literal%252Fslash.mmd")).toEqual({
      root: "docs",
      path: "literal%2Fslash.mmd",
    });
    expect(parseDiagramPath("/browse/unknown/flow.mmd")).toEqual({ root: "unknown", path: "flow.mmd" });
    expect(parseDiagramPath("/browse/docs/flow.mmd?raw=1")).toBeNull();
    expect(parseDiagramPath("/browse/docs/flow.MMD")).toBeNull();
  });
});
