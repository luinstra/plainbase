import { describe, expect, it } from "vitest";
import type { RootTree, SearchHit } from "../api/types";
import { searchRows, searchSpaceLabel, searchTrail } from "../lib/searchResults";
import type { QuickSwitchEntry } from "../lib/tree";

const root = (name: string): RootTree => ({ root: name, available: true, editable: true, primary: name === "docs", displayName: "Guides",
  tree: { type: "folder", name: "", title: null, description: null, path: "", url: `/${name}`, page_count: 1,
    children: [{ type: "folder", name: "ops", title: `${name} operations`, description: null, path: "ops", url: `/${name}/ops`, page_count: 1, children: [] }] } });
const hit = (root: string, heading: string | null = null) => ({ root, page_id: "same", heading_id: heading } as SearchHit);

describe("search results", () => {
  it("keeps the first ranked section of each page and preserves other spaces", () => {
    const rows = searchRows([], [hit("extra"), hit("docs", "best"), hit("docs"), hit("docs", "section")], [root("docs"), root("extra")]);
    expect(rows.map((row) => row.root)).toEqual(["docs", "extra"]);
    expect(rows[0].kind === "hit" && rows[0].hit.heading_id).toBe("best");
    expect(new Set(rows.map((row) => row.key)).size).toBe(2);
  });
  it("gives quick pages precedence over content duplicates without suppressing diagrams", () => {
    const quick = { root: "docs", page: { id: "same" } } as QuickSwitchEntry;
    const diagram = { root: "docs", diagram: { path: "same" } } as QuickSwitchEntry;
    const rows = searchRows([quick, quick, diagram], [hit("docs"), hit("extra")], [root("docs"), root("extra")]);
    expect(rows.map((row) => row.kind)).toEqual(["jump", "jump", "hit"]);
    expect(rows[1].kind === "jump" && "diagram" in rows[1].entry).toBe(true);
    expect(rows[2].root).toBe("extra");
  });
  it("uses each space's own folder labels and disambiguates identical display names", () => {
    const roots = [root("docs"), root("extra")];
    expect(searchTrail(roots, "extra", "ops/deploy.md")).toBe("extra operations / deploy.md");
    expect(searchTrail(roots, "unknown", "ops/deploy.md")).toBe("ops / deploy.md");
    expect(searchSpaceLabel(roots, "extra")).toBe("Guides (extra)");
  });
});
