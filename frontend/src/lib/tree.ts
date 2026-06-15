import type { TreeFolder, TreeNode, TreePage } from "../api/types";

/** A page's href: the canonical URL from the tree, or the `/p/{id}` permalink for a collision loser. */
export function pageHref(page: TreePage): string {
  return page.url ?? `/p/${page.id}`;
}

function* walk(nodes: TreeNode[]): Generator<TreeNode> {
  for (const node of nodes) {
    yield node;
    if (node.type === "folder") yield* walk(node.children);
  }
}

/** Every page node under `root`, depth-first in tree order — the quick-switcher's candidate set. */
export function pages(root: TreeFolder): TreePage[] {
  const result: TreePage[] = [];
  for (const node of walk(root.children)) {
    if (node.type === "page") result.push(node);
  }
  return result;
}

/**
 * Folder nodes keyed by content-relative folder path ("guides/advanced") — breadcrumb
 * titles (null title → callers fall back to the directory name) and landing urls.
 */
export function foldersByPath(root: TreeFolder): Map<string, TreeFolder> {
  const folders = new Map<string, TreeFolder>();
  for (const node of walk(root.children)) {
    if (node.type === "folder") folders.set(node.path, node);
  }
  return folders;
}

/**
 * The folder node owning a `/docs` location, if any — the folder-landing resolution
 * (ADR-0003). The root itself is included (its `url` is bare `/docs`). Matched verbatim
 * against the server-issued `url`: the server is the single URL authority, so nothing is
 * slugified or re-encoded client-side.
 */
export function folderByUrl(root: TreeFolder, pathname: string): TreeFolder | null {
  for (const node of walk([root])) {
    if (node.type === "folder" && node.url !== null && node.url === pathname) return node;
  }
  return null;
}

/**
 * README-preference (ADR-0003): the direct child page whose filename stem is `index` or
 * `readme`, case-insensitive — `index` wins when both exist (web-native beats repo-native).
 * Stems come from the tree node's `path`, never from re-slugification.
 */
function landingChild(children: TreeNode[]): TreePage | null {
  let readme: TreePage | null = null;
  for (const child of children) {
    if (child.type !== "page") continue;
    const name = child.path.slice(child.path.lastIndexOf("/") + 1);
    const stem = name.replace(/\.md$/, "").toLowerCase();
    if (stem === "index") return child;
    if (stem === "readme") readme ??= child;
  }
  return readme;
}

/** The folder's landing page (index/README), if any — the child rendered at the folder URL. */
export function landingPage(folder: TreeFolder): TreePage | null {
  return landingChild(folder.children);
}

/**
 * Children reordered for the SIDEBAR: the landing page (index/README) floats to the TOP, the
 * rest keep server tree order. The folder's view IS that page, so listing it first makes the
 * overview the obvious entry — most visibly the root, whose index would otherwise sort to the
 * bottom among its siblings. Presentation only; the server tree order is untouched.
 */
export function sidebarOrder(children: TreeNode[]): TreeNode[] {
  const landing = landingChild(children);
  return landing ? [landing, ...children.filter((child) => child !== landing)] : children;
}
