import type { RootTree } from "../api/types";
import { entryFor, folderTitle, foldersByPath, landingPage, rootLabel } from "./tree";

/** Labels and destinations come only from the addressed space's tree. */
export function breadcrumbTrail(roots: RootTree[], root: string, path: string) {
  const entry = entryFor(roots, root);
  const folders = entry ? foldersByPath(entry.tree) : new Map();
  const rootCrumb = { key: `root:${root}`, label: entry ? rootLabel(entry) : root, url: entry?.tree.url ?? null };
  const segments = path.split("/").slice(0, -1);
  const ancestors = segments.map((name, index) => {
    const folderPath = segments.slice(0, index + 1).join("/");
    const folder = folders.get(folderPath);
    return { key: folderPath, label: folder ? folderTitle(folder) : name, url: folder?.url ?? null };
  });
  const parent = folders.get(segments.join("/"));
  const pageIsLanding = parent ? landingPage(parent)?.path === path : false;
  return path === "" ? [] : [rootCrumb, ...(pageIsLanding ? ancestors.slice(0, -1) : ancestors)];
}
