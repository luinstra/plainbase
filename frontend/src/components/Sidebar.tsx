import { useQuery } from "@tanstack/react-query";
import { useRouterState } from "@tanstack/react-router";
import { useState } from "react";
import { treeQuery } from "../api/queries";
import type { RootTree, TreeFolder, TreeNode, TreePage } from "../api/types";
import { folderTitle, landingPage, nonLandingChildren, pageHref } from "../lib/tree";
import { ROOT_UNAVAILABLE } from "./ErrorView";

/**
 * Tree navigation, fed by `GET /api/v1/tree`; links are the node `url`s verbatim.
 *
 * ONE `<aside>` however many roots are configured, with one `<section>` per root entry inside it: the
 * aside is the layout column (a fixed `w-[clamp(...)]` slice of the Shell's flex row), so a second one
 * is a second column and N roots would squeeze `<main>` off the screen. `.pb-sidebar`, `data-pb-sidebar`,
 * `data-pb-root-section` and `data-pb-root-label` are stable selectors (public customization API).
 *
 * Section headers appear only with 2+ roots: with the single root every legacy install has, a header
 * reading "main" is noise. A root that is not SERVING gets the outage notice instead of its tree, never
 * an empty list - see [RootSection].
 */
export function Sidebar() {
  const { data } = useQuery(treeQuery);
  const currentPathname = useRouterState({ select: (s) => s.location.pathname });
  if (!data) return <aside className="pb-sidebar w-[clamp(16rem,20vw,22rem)] shrink-0" data-pb-sidebar />;
  return (
    <aside
      className="pb-sidebar sticky top-14 h-[calc(100vh-3.5rem)] w-[clamp(16rem,20vw,22rem)] shrink-0 overflow-y-auto border-r border-edge bg-raised max-lg:hidden"
      data-pb-sidebar
    >
      {data.roots.map((entry) => (
        <RootSection key={entry.root} entry={entry} labeled={data.roots.length > 1} currentPathname={currentPathname} />
      ))}
    </aside>
  );
}

/** One root's slice of the sidebar: its label (multi-root only) over its tree — or over the outage
 *  notice, because the server EMPTIES a down root's subtree (tree.ts [FolderEntry]) and rendering
 *  those absent children would tell the reader their docs are gone. */
function RootSection({ entry, labeled, currentPathname }: { entry: RootTree; labeled: boolean; currentPathname: string }) {
  return (
    <section data-pb-root-section={entry.root}>
      {labeled && (
        <h2 className="px-4 pt-5 font-mono text-xs font-medium uppercase tracking-wide text-faint" data-pb-root-label={entry.root}>
          {entry.root}
        </h2>
      )}
      {entry.available ? (
        <SidebarNav tree={entry.tree} root={entry.root} currentPathname={currentPathname} />
      ) : (
        <RootUnavailableNotice root={entry.root} />
      )}
    </section>
  );
}

/** The COMPACT renderer of the shared outage vocabulary ([ROOT_UNAVAILABLE]): the full-page
 *  `RootUnavailableView` is a centered `<h1>` block, which is wrong markup and worse layout inside a
 *  nav. Same words, nav-sized. */
function RootUnavailableNotice({ root }: { root: string }) {
  return (
    <div className="px-4 py-5 text-sm" data-pb-root-section-unavailable={root}>
      <p className="text-xs font-semibold uppercase tracking-wide text-faint">{ROOT_UNAVAILABLE.eyebrow}</p>
      <p className="mt-1 font-medium text-ink">{ROOT_UNAVAILABLE.headline(root)}</p>
      <p className="mt-1 text-xs text-muted">{ROOT_UNAVAILABLE.body}</p>
    </div>
  );
}

/**
 * Presentational tree nav — one root's rows. The `data-pb-*` attributes are stable selectors
 * (public customization API) — guarded by the snapshot test.
 *
 * [tree] is the root FOLDER, [root] its NAME. Two parameters, two names: a page row's permalink is
 * built from the root name (the tree node carries none of its own), and one identifier called `root`
 * meaning both is how a rooted href silently reverts to a bare one.
 */
export function SidebarNav({ tree, root, currentPathname }: { tree: TreeFolder; root: string; currentPathname: string }) {
  // The root has no folder row of its own, so its landing (index/README) is surfaced as an explicit
  // home link AT THE TOP — pointing at the folder URL (`/docs/{root}` since C3), never the page's bare URL.
  const home = landingPage(tree);
  return (
    <nav aria-label="Documentation tree" className="px-4 py-5 text-sm">
      <ul className="space-y-0.5">
        {home && tree.url && <PageRow href={tree.url} status={home.status} label={home.title} currentPathname={currentPathname} />}
        <NodeRows nodes={nonLandingChildren(tree)} root={root} currentPathname={currentPathname} />
      </ul>
    </nav>
  );
}

function NodeList({ nodes, root, currentPathname }: { nodes: TreeNode[]; root: string; currentPathname: string }) {
  return (
    <ul className="space-y-0.5">
      <NodeRows nodes={nodes} root={root} currentPathname={currentPathname} />
    </ul>
  );
}

function NodeRows({ nodes, root, currentPathname }: { nodes: TreeNode[]; root: string; currentPathname: string }) {
  return nodes.map((node) =>
    node.type === "folder" ? (
      <FolderItem key={node.path} folder={node} root={root} currentPathname={currentPathname} />
    ) : (
      <PageItem key={node.id} page={node} root={root} currentPathname={currentPathname} />
    ),
  );
}

/**
 * A folder row: the label links to the folder's landing view (ADR-0003) while a separate
 * disclosure button owns expand/collapse — split affordances, so navigating and toggling
 * never contest one click (`aria-expanded` lives on the button). A collision-loser folder
 * has no `url` and keeps an inert label.
 *
 * It builds no href from [root] itself and still needs it: its child list is the ONLY path to a
 * nested page row, so a `FolderItem` that drops the root un-roots every page below the top level
 * while the top-level rows keep looking right.
 */
function FolderItem({ folder, root, currentPathname }: { folder: TreeFolder; root: string; currentPathname: string }) {
  const [open, setOpen] = useState(true);
  const label = folderTitle(folder);
  const active = folder.url !== null && folder.url === currentPathname;
  // The rows actually rendered under this folder, computed ONCE: a `url`-folder surfaces its landing
  // (index/README) THROUGH the label link above, so it's dropped from the child rows (one canonical path
  // — matches the root). A loser folder has no url and an inert label, so its landing can only be reached
  // as a child row: keep the full children there or the index page becomes unreachable from the tree.
  const visibleChildren = folder.url ? nonLandingChildren(folder) : folder.children;
  // An index-only folder (its sole child IS the landing, surfaced via the label) has nothing left to
  // disclose — gate BOTH the chevron and the child list on the VISIBLE count so it shows no expand affordance.
  const expandable = visibleChildren.length > 0;
  // Content paths are unique per folder; encodeURIComponent keeps that uniqueness (injective)
  // while clearing every id-hostile character (whitespace, quotes) from the DOM id.
  const childrenId = `pb-folder-children-${encodeURIComponent(folder.path)}`;
  return (
    <li data-pb-nav-item="folder">
      <div className="flex items-center">
        <button
          type="button"
          disabled={!expandable}
          aria-expanded={expandable ? open : undefined}
          aria-controls={childrenId}
          aria-label={`${open ? "Collapse" : "Expand"} ${label}`}
          onClick={() => setOpen((value) => !value)}
          className="rounded p-1 text-faint hover:bg-hovered hover:text-ink disabled:invisible"
          data-pb-folder-toggle
        >
          <span aria-hidden="true" className="pb-folder-caret" />
        </button>
        {folder.url ? (
          <a
            href={folder.url}
            aria-current={active ? "page" : undefined}
            className={
              active
                ? "block flex-1 rounded px-2 py-1 font-semibold text-ink"
                : "block flex-1 rounded px-2 py-1 font-semibold text-ink hover:bg-hovered hover:text-ink"
            }
          >
            {label}
          </a>
        ) : (
          <span className="block flex-1 px-2 py-1 font-semibold text-ink">{label}</span>
        )}
      </div>
      {open && expandable && (
        <div id={childrenId} className="ml-3 border-l border-edge pl-2">
          <NodeList nodes={visibleChildren} root={root} currentPathname={currentPathname} />
        </div>
      )}
    </li>
  );
}

function PageItem({ page, root, currentPathname }: { page: TreePage; root: string; currentPathname: string }) {
  return <PageRow href={pageHref(root, page)} status={page.status} label={page.title} currentPathname={currentPathname} />;
}

/**
 * A leaf nav link. `href` is usually the page's own url, but the root landing passes the folder
 * url (`/docs/{root}`) so a folder's index/README has exactly one path. `data-pb-status` stays a
 * stable selector for the active-tint/slash-bar rule.
 */
function PageRow({
  href,
  status,
  label,
  currentPathname,
}: {
  href: string;
  status?: string;
  label: string;
  currentPathname: string;
}) {
  const active = href === currentPathname;
  return (
    <li data-pb-nav-item="page">
      <a
        href={href}
        data-pb-status={status}
        aria-current={active ? "page" : undefined}
        className={
          active
            ? "block rounded px-2 py-1 text-ink"
            : "block rounded px-2 py-1 text-muted hover:bg-hovered hover:text-ink"
        }
      >
        {label}
      </a>
    </li>
  );
}
