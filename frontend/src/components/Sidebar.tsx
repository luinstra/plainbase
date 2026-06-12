import { useQuery } from "@tanstack/react-query";
import { useRouterState } from "@tanstack/react-router";
import { treeQuery } from "../api/queries";
import type { TreeFolder, TreeNode, TreePage } from "../api/types";
import { pageHref } from "../lib/tree";

/** Tree navigation, fed by `GET /api/v1/tree`; links are the node `url`s verbatim. */
export function Sidebar() {
  const { data } = useQuery(treeQuery);
  const currentPathname = useRouterState({ select: (s) => s.location.pathname });
  if (!data) return <aside className="pb-sidebar w-72 shrink-0" data-pb-sidebar />;
  return <SidebarNav root={data.root} currentPathname={currentPathname} />;
}

/**
 * Presentational tree nav. `.pb-sidebar` and the `data-pb-*` attributes are stable
 * selectors (public customization API) — guarded by the snapshot test.
 */
export function SidebarNav({ root, currentPathname }: { root: TreeFolder; currentPathname: string }) {
  return (
    <aside
      className="pb-sidebar sticky top-14 h-[calc(100vh-3.5rem)] w-72 shrink-0 overflow-y-auto border-r border-edge bg-raised max-lg:hidden"
      data-pb-sidebar
    >
      <nav aria-label="Documentation tree" className="px-4 py-5 text-sm">
        <NodeList nodes={root.children} currentPathname={currentPathname} />
      </nav>
    </aside>
  );
}

function NodeList({ nodes, currentPathname }: { nodes: TreeNode[]; currentPathname: string }) {
  return (
    <ul className="space-y-0.5">
      {nodes.map((node) =>
        node.type === "folder" ? (
          <FolderItem key={node.path} folder={node} currentPathname={currentPathname} />
        ) : (
          <PageItem key={node.id} page={node} currentPathname={currentPathname} />
        ),
      )}
    </ul>
  );
}

function FolderItem({ folder, currentPathname }: { folder: TreeFolder; currentPathname: string }) {
  return (
    <li data-pb-nav-item="folder">
      <details open>
        <summary className="rounded px-2 py-1 font-medium text-muted hover:bg-hovered hover:text-ink">
          {folder.title ?? folder.name}
        </summary>
        <div className="ml-3 border-l border-edge pl-2">
          <NodeList nodes={folder.children} currentPathname={currentPathname} />
        </div>
      </details>
    </li>
  );
}

function PageItem({ page, currentPathname }: { page: TreePage; currentPathname: string }) {
  const href = pageHref(page);
  const active = href === currentPathname;
  return (
    <li data-pb-nav-item="page">
      <a
        href={href}
        data-pb-status={page.status}
        aria-current={active ? "page" : undefined}
        className={
          active
            ? "block rounded bg-active px-2 py-1 font-medium text-ink"
            : "block rounded px-2 py-1 text-muted hover:bg-hovered hover:text-ink"
        }
      >
        {page.title}
      </a>
    </li>
  );
}
