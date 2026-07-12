import type { RootTree, TreeFolder, TreeNode, TreePage } from "../api/types";

/**
 * A cross-root lookup's answer: the matched folder WITH its entry's root and whether that root is
 * SERVING. The root is carried, never re-derived from a url (two roots can hold the same
 * root-relative folder path, so a bare folder answer would be ambiguous).
 *
 * `available` is carried for the same reason and it is not cosmetic: the server empties an
 * unavailable root's subtree (it must never ship the stale carried listing), so a caller that reads
 * only `folder` sees a folder with no children and renders "this directory is empty" over what is
 * actually an outage. The server spent the whole of D5 learning to tell a down root from a deleted
 * one; dropping the flag here throws that distinction away at the last step and tells the operator
 * their docs are gone.
 */
export interface FolderEntry {
  root: string;
  available: boolean;
  folder: TreeFolder;
}

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

/** The named entry's tree, if served. `"main"` is the one legal client-side root literal (D1). */
export function treeFor(roots: RootTree[], root: string): TreeFolder | null {
  return roots.find((entry) => entry.root === root)?.tree ?? null;
}

/**
 * The reserved main root's ENTRY - the `/docs` home view's resolution. The whole entry, not its bare
 * tree: `/docs` lands on main's root folder, and main can be down like any other root (a vanished
 * CONTENT_DIR), so the home view needs its `available` for exactly the reason [FolderEntry] does.
 */
export function mainEntry(roots: RootTree[]): RootTree | null {
  return roots.find((entry) => entry.root === "main") ?? null;
}

/** Every page node across all entries, in wire (D7) order - the quick-switcher's candidate set. */
export function pages(roots: RootTree[]): TreePage[] {
  const result: TreePage[] = [];
  for (const entry of roots) {
    for (const node of walk(entry.tree.children)) {
      if (node.type === "page") result.push(node);
    }
  }
  return result;
}

/**
 * ONE tree's folder nodes keyed by content-relative folder path ("guides/advanced") - breadcrumb
 * titles (null title → callers fall back to the directory name) and landing urls. Root-relative
 * paths repeat across roots, so callers scope this BY the entry they already hold ([treeFor]).
 */
export function foldersByPath(root: TreeFolder): Map<string, TreeFolder> {
  const folders = new Map<string, TreeFolder>();
  for (const node of walk(root.children)) {
    if (node.type === "folder") folders.set(node.path, node);
  }
  return folders;
}

/**
 * The entry whose folder owns a `/docs/{root}` location, if any - the folder-landing resolution
 * (ADR-0003). Each entry's synthetic root folder is included (its `url` is the bare
 * `/docs/{root}`). Matched verbatim against the server-issued `url`: the server is the single URL
 * authority, so nothing is slugified or re-encoded client-side.
 */
export function folderByUrl(roots: RootTree[], pathname: string): FolderEntry | null {
  for (const entry of roots) {
    for (const node of walk([entry.tree])) {
      if (node.type === "folder" && node.url !== null && node.url === pathname) {
        return { root: entry.root, available: entry.available, folder: node };
      }
    }
  }
  return null;
}

/**
 * The ENTRY whose `/docs/{root}` URL space owns [pathname], or null when the location is not under one (the
 * `/new`, `/review`, `/admin` and `/p/{id}` routes).
 *
 * Matched against each entry's SERVER-ISSUED root url (`RootTree.tree.url`) rather than by splitting the
 * pathname on `/`: the server is the single URL authority (§A4), and this answer decides WHICH TREE a create
 * writes its bytes into - the one place a client-side guess would be silently destructive rather than merely
 * wrong on screen.
 *
 * The whole entry, not just the name: url ownership is the only thing an UNAVAILABLE root still tells the
 * client (its subtree arrives empty, so no folder lookup can reach inside it), and reading that answer needs
 * the entry's `available` - see PageView's FolderLanding.
 */
export function rootEntryOfUrl(roots: RootTree[], pathname: string): RootTree | null {
  for (const entry of roots) {
    const url = entry.tree.url;
    if (url !== null && (pathname === url || pathname.startsWith(`${url}/`))) return entry;
  }
  return null;
}

/** The NAME of the root owning [pathname] - [rootEntryOfUrl]'s answer, so there stays exactly one url-ownership rule. */
export function rootOfUrl(roots: RootTree[], pathname: string): string | null {
  return rootEntryOfUrl(roots, pathname)?.root ?? null;
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

/** A folder's human display title: an explicit _folder.yaml title wins, else the index/README child's
 *  (frontmatter) title, else the raw directory name. A created dir (no _folder.yaml) thus shows its
 *  index page's title. */
export function folderTitle(folder: TreeFolder): string {
  return folder.title ?? landingPage(folder)?.title ?? folder.name;
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
 * The entry whose folder's landing page (index/README) is the page `pageId`, if any. A landing
 * page has one canonical home - the folder URL - so its own bare-page URL is redirected there;
 * this is the lookup that recognizes such a URL. Each entry's root folder is included (its
 * landing answers `/docs/{root}`).
 */
export function folderForLanding(roots: RootTree[], pageId: string): FolderEntry | null {
  for (const entry of roots) {
    for (const node of walk([entry.tree])) {
      if (node.type === "folder" && landingChild(node.children)?.id === pageId) {
        return { root: entry.root, available: entry.available, folder: node };
      }
    }
  }
  return null;
}
