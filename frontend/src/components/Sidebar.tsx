import { useQuery } from "@tanstack/react-query";
import { useRouterState } from "@tanstack/react-router";
import { useState } from "react";
import { treeQuery } from "../api/queries";
import type { TreeFolder, TreeNode, TreePage } from "../api/types";
import { folderTitle, landingPage, nonLandingChildren, pageHref } from "../lib/tree";

/** Tree navigation, fed by `GET /api/v1/tree`; links are the node `url`s verbatim. */
export function Sidebar() {
  const { data } = useQuery(treeQuery);
  const currentPathname = useRouterState({ select: (s) => s.location.pathname });
  if (!data) return <aside className="pb-sidebar w-[clamp(16rem,20vw,22rem)] shrink-0" data-pb-sidebar />;
  return <SidebarNav root={data.root} currentPathname={currentPathname} />;
}

/**
 * Presentational tree nav. `.pb-sidebar` and the `data-pb-*` attributes are stable
 * selectors (public customization API) — guarded by the snapshot test.
 */
export function SidebarNav({ root, currentPathname }: { root: TreeFolder; currentPathname: string }) {
  // The root has no folder row of its own, so its landing (index/README) is surfaced as an explicit
  // home link AT THE TOP — pointing at the folder URL (`/docs`), never the page's bare URL.
  const home = landingPage(root);
  return (
    <aside
      className="pb-sidebar sticky top-14 h-[calc(100vh-3.5rem)] w-[clamp(16rem,20vw,22rem)] shrink-0 overflow-y-auto border-r border-edge bg-raised max-lg:hidden"
      data-pb-sidebar
    >
      <nav aria-label="Documentation tree" className="px-4 py-5 text-sm">
        <ul className="space-y-0.5">
          {home && root.url && (
            <PageRow href={root.url} status={home.status} label={home.title} currentPathname={currentPathname} />
          )}
          <NodeRows nodes={nonLandingChildren(root)} currentPathname={currentPathname} />
        </ul>
      </nav>
    </aside>
  );
}

function NodeList({ nodes, currentPathname }: { nodes: TreeNode[]; currentPathname: string }) {
  return (
    <ul className="space-y-0.5">
      <NodeRows nodes={nodes} currentPathname={currentPathname} />
    </ul>
  );
}

function NodeRows({ nodes, currentPathname }: { nodes: TreeNode[]; currentPathname: string }) {
  return nodes.map((node) =>
    node.type === "folder" ? (
      <FolderItem key={node.path} folder={node} currentPathname={currentPathname} />
    ) : (
      <PageItem key={node.id} page={node} currentPathname={currentPathname} />
    ),
  );
}

/**
 * A folder row: the label links to the folder's landing view (ADR-0003) while a separate
 * disclosure button owns expand/collapse — split affordances, so navigating and toggling
 * never contest one click (`aria-expanded` lives on the button). A collision-loser folder
 * has no `url` and keeps an inert label.
 */
function FolderItem({ folder, currentPathname }: { folder: TreeFolder; currentPathname: string }) {
  const [open, setOpen] = useState(true);
  const label = folderTitle(folder);
  const active = folder.url !== null && folder.url === currentPathname;
  // Content paths are unique per folder; encodeURIComponent keeps that uniqueness (injective)
  // while clearing every id-hostile character (whitespace, quotes) from the DOM id.
  const childrenId = `pb-folder-children-${encodeURIComponent(folder.path)}`;
  return (
    <li data-pb-nav-item="folder">
      <div className="flex items-center">
        <button
          type="button"
          aria-expanded={open}
          aria-controls={childrenId}
          aria-label={`${open ? "Collapse" : "Expand"} ${label}`}
          onClick={() => setOpen((value) => !value)}
          className="rounded p-1 text-faint hover:bg-hovered hover:text-ink"
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
      {open && (
        <div id={childrenId} className="ml-3 border-l border-edge pl-2">
          {/* A folder with a `url` surfaces its landing (index/README) THROUGH the label link above,
              so it's dropped from the child rows (one canonical path — matches the root). A loser
              folder has no url and an inert label, so its landing can only be reached as a child row:
              keep the full children there or the index page becomes unreachable from the tree. */}
          <NodeList nodes={folder.url ? nonLandingChildren(folder) : folder.children} currentPathname={currentPathname} />
        </div>
      )}
    </li>
  );
}

function PageItem({ page, currentPathname }: { page: TreePage; currentPathname: string }) {
  return <PageRow href={pageHref(page)} status={page.status} label={page.title} currentPathname={currentPathname} />;
}

/**
 * A leaf nav link. `href` is usually the page's own url, but the root landing passes the folder
 * url (`/docs`) so a folder's index/README has exactly one path. `data-pb-status` stays a stable
 * selector for the active-tint/slash-bar rule.
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
