import type { RootTree, SearchHit } from "../api/types";
import { folderTitle, foldersByPath, rootLabelFor, type QuickSwitchEntry } from "./tree";

export type SearchRow = { key: string; root: string } & (
  | { kind: "jump"; entry: QuickSwitchEntry }
  | { kind: "hit"; hit: SearchHit }
);

export function searchSpaceLabel(roots: RootTree[], root: string): string {
  const label = rootLabelFor(roots, root);
  return roots.filter((entry) => rootLabelFor(roots, entry.root) === label).length > 1 ? `${label} (${root})` : label;
}

export function searchTrail(roots: RootTree[], root: string, path: string): string {
  const tree = roots.find((entry) => entry.root === root)?.tree;
  const folders = tree ? foldersByPath(tree) : new Map();
  const segments = path.split("/");
  return segments.map((segment, index) => {
    const folder = index < segments.length - 1 ? folders.get(segments.slice(0, index + 1).join("/")) : undefined;
    return folder ? folderTitle(folder) : segment;
  }).join(" / ");
}

/** Each section is grouped independently so content arrival cannot push quick matches down. */
export function searchRows(quick: QuickSwitchEntry[], hits: SearchHit[], roots: RootTree[]): SearchRow[] {
  const rank = (root: string) => {
    const index = roots.findIndex((entry) => entry.root === root);
    return index < 0 ? roots.length : index;
  };
  const grouped = (rows: SearchRow[]) => rows.sort((a, b) => rank(a.root) - rank(b.root) ||
    (rank(a.root) === roots.length ? a.root.localeCompare(b.root) : 0));
  const seen = new Set<string>();
  return [
    ...grouped(quick.flatMap((entry): SearchRow[] => {
      const identity = JSON.stringify(["page" in entry ? "page" : "diagram", entry.root, "page" in entry ? entry.page.id : entry.diagram.path]);
      if (seen.has(identity)) return [];
      seen.add(identity);
      return [{ kind: "jump", root: entry.root, entry, key: `jump:${identity}` }];
    })),
    ...grouped(hits.flatMap((hit): SearchRow[] => {
      const identity = JSON.stringify(["page", hit.root, hit.page_id]);
      if (seen.has(identity)) return [];
      seen.add(identity);
      return [{ kind: "hit", root: hit.root, hit, key: `hit:${identity}` }];
    })),
  ];
}
