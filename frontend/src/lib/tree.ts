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
 * A folder's children with its landing page (index/README) removed — server tree order otherwise
 * intact. A folder's landing IS the folder URL, so it's never repeated as a child row: a subfolder
 * surfaces it through its own label link, and the root through the home link rendered above the
 * tree. One path per page (the folder URL), never a second bare-page entry.
 */
export function nonLandingChildren(folder: TreeFolder): TreeNode[] {
  const landing = landingChild(folder.children);
  return landing ? folder.children.filter((child) => child !== landing) : folder.children;
}

/**
 * The folder whose landing page (index/README) is the page `pageId`, if any. A landing page has
 * one canonical home — the folder URL — so its own bare-page URL is redirected there; this is the
 * lookup that recognizes such a URL. The root is included (its landing answers `/docs`).
 */
export function folderForLanding(root: TreeFolder, pageId: string): TreeFolder | null {
  for (const node of walk([root])) {
    if (node.type === "folder" && landingChild(node.children)?.id === pageId) return node;
  }
  return null;
}
