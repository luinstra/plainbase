import type { RootTree, TreeFolder, TreeNode, TreePage } from "../api/types";
import { parsePermalink, permalinkOf, permalinkSplat } from "./permalink";

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

/** A page's href: the canonical URL from the tree, or its ROOTED `/p/{root}/{id}` permalink for a
 *  collision loser. [root] is required: the tree node carries no root of its own (two roots hold the
 *  same relative path), and a bare permalink now answers 300/409 whenever the id is duplicated. */
export function pageHref(root: string, page: TreePage): string {
  return page.url ?? permalinkOf(root, page.id);
}

function* walk(nodes: TreeNode[]): Generator<TreeNode> {
  for (const node of nodes) {
    yield node;
    if (node.type === "folder") yield* walk(node.children);
  }
}

/** The named entry, or null when the roots are not loaded / the name is not one of them. */
export function entryFor(roots: RootTree[], root: string): RootTree | null {
  return roots.find((entry) => entry.root === root) ?? null;
}

/** The named entry's tree, if served. */
export function treeFor(roots: RootTree[], root: string): TreeFolder | null {
  return entryFor(roots, root)?.tree ?? null;
}

/**
 * The server-selected primary entry, or null when the roots are not loaded or no primary is present.
 */
export function primaryEntry(roots: RootTree[]): RootTree | null {
  return roots.find((entry) => entry.primary) ?? null;
}

/**
 * Whether [root] can take a page write RIGHT NOW - the gate on every write AFFORDANCE (Shell's "New",
 * PageView's "Edit this page").
 *
 * BOTH wire bits, because an affordance that can only fail is the same lie whichever one is false:
 * `editable` says the root accepts writes at all (403 `root_not_editable` otherwise, in every auth mode),
 * and `available` says its content is reachable (503 `root_unavailable` otherwise - the root is configured
 * and still listed, but its subtree arrives EMPTY and nothing can land in it until an operator restores it).
 * An editable root that is DOWN was the gap: `editable` alone still offered the action.
 *
 * **Unknown is NOT writable**, and that is the point of the default: a root name the tree does not carry, or a
 * tree that has not loaded, means we do not know the topology - and offering an action we cannot honor walks a
 * reader into the editor, takes their keystrokes and fails at save. `plainbase root add` defaults an extra root
 * to `editable = false`, so guessing `true` would be wrong for the DEFAULT CLI-added root.
 *
 * It gates the affordance ONLY. The server's 403/503 is the authority and the editor's buffer-preserving
 * failure path is the backstop - this never becomes the thing that decides a write.
 */
export function rootAcceptsWrites(roots: RootTree[] | undefined, root: string | null): boolean {
  if (!roots || !root) return false;
  const entry = entryFor(roots, root);
  return entry !== null && entry.available && entry.editable;
}

/**
 * A page WITH its entry's root, for the same reason [FolderEntry] carries one: a page's `path` is
 * ROOT-RELATIVE, so two roots holding `guides/deploy.md` yield two pages that are indistinguishable
 * once the root is dropped. Navigation still works (the `url` carries the root), which is what makes
 * dropping it insidious - the reader only learns they opened the wrong tree after reading it.
 */
export interface PageEntry {
  root: string;
  page: TreePage;
}

/** Every page across all entries, in wire (D7) order - the quick-switcher's candidate set. */
export function pages(roots: RootTree[]): PageEntry[] {
  const result: PageEntry[] = [];
  for (const entry of roots) {
    for (const node of walk(entry.tree.children)) {
      if (node.type === "page") result.push({ root: entry.root, page: node });
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
 * The entry whose folder owns a root-content location, if any - the folder-landing resolution
 * (ADR-0003). Each entry's synthetic root folder is included (its `url` is the bare
 * root URL). Matched verbatim against the server-issued `url`: the server is the single URL
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
 * The ENTRY whose server-issued root URL space owns [pathname], or null when the location is not under one (the
 * `/new`, `/review`, `/admin` and `/p/...` permalink routes).
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
 * The root the READER is standing in, from the address alone - root URL ownership OR a rooted
 * `/p/{root}/{id}` permalink. The two sources are disjoint (a permalink is never in a root-content URL space)
 * and the second one is not a nicety: a path-space collision loser has `url = null`, so its permalink is
 * its ONLY address, and reading that location as "no root" is what sent a reader's new page into the primary root
 * from a page that lives somewhere else entirely.
 *
 * A BARE `/p/{id}` still answers null, and must: that address names no root, and the only thing that
 * could supply one is the page response this location has not made. An UNKNOWN root name is answered
 * VERBATIM rather than filtered to null - callers gate on the registry themselves ([rootAcceptsWrites],
 * [entryFor]), and their answer for an unknown root is "not writable", which is the right one here too.
 * Filtering would instead hand back "no root", which every caller reads as the primary root.
 */
export function rootOfLocation(roots: RootTree[], pathname: string): string | null {
  const splat = permalinkSplat(pathname);
  return splat === null ? rootOfUrl(roots, pathname) : parsePermalink(splat).root;
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
 * The entry whose folder's landing page (index/README) is the page `pageId` WITHIN [root], if any. A
 * landing page has one canonical home - the folder URL - so its own bare-page URL is redirected there;
 * this is the lookup that recognizes such a URL. Each entry's root folder is included (its landing
 * answers its server-issued root URL).
 *
 * [root] is REQUIRED and is the caller's ALREADY-RESOLVED root (`PageResponse.root`), not a guess: a
 * page id can be held by more than one root (per-root identity), and two roots holding
 * `permalink/index.md` under the same id is the NORMAL shape of a copied corpus. An unscoped walk
 * answers with whichever entry comes first in wire order, and the caller turns that answer into a
 * `history.replace` - so the bug's symptom is a reader silently moved into another root's document,
 * with the page rendering perfectly. Scoping here rather than filtering at the call site keeps the rule
 * in the SIGNATURE, where a future caller cannot forget it.
 *
 * Scope first, id second: that is the argument order of the tree-lookup family it belongs to
 * ([entryFor], [treeFor], [folderByUrl]), not the id-first order of the API helpers.
 */
export function folderForLanding(roots: RootTree[], root: string, pageId: string): FolderEntry | null {
  const entry = entryFor(roots, root);
  if (entry === null) return null;
  for (const node of walk([entry.tree])) {
    if (node.type === "folder" && landingChild(node.children)?.id === pageId) {
      return { root: entry.root, available: entry.available, folder: node };
    }
  }
  return null;
}
