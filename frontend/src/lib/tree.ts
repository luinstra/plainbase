import type { TreeFolder, TreeNode, TreePage } from "../api/types";

/** A page's href: the canonical URL from the tree, or the `/p/{id}` permalink for a collision loser. */
export function pageHref(page: TreePage): string {
  return page.url ?? `/p/${page.id}`;
}

/** The first page in tree (depth-first) order — the `/` redirect target. */
export function firstPage(root: TreeFolder): TreePage | null {
  for (const node of walk(root.children)) {
    if (node.type === "page") return node;
  }
  return null;
}

function* walk(nodes: TreeNode[]): Generator<TreeNode> {
  for (const node of nodes) {
    yield node;
    if (node.type === "folder") yield* walk(node.children);
  }
}

/**
 * Folder display titles keyed by content-relative folder path ("guides/advanced"), for
 * breadcrumbs. A folder with no `_folder.yaml` title maps to null (callers fall back to
 * the directory name).
 */
export function folderTitles(root: TreeFolder): Map<string, string | null> {
  const titles = new Map<string, string | null>();
  for (const node of walk(root.children)) {
    if (node.type === "folder") titles.set(node.path, node.title);
  }
  return titles;
}
