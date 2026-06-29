import { useQuery } from "@tanstack/react-query";
import { Fragment } from "react";
import { treeQuery } from "../api/queries";
import type { TreeFolder } from "../api/types";
import { folderTitle, foldersByPath, landingPage } from "../lib/tree";

/**
 * Breadcrumb trail derived from the page's content-relative `path` (the API's value,
 * verbatim); folder display titles and landing urls come from the tree. An ancestor crumb
 * links to its folder landing view (ADR-0003) — server-issued `url`s consumed verbatim; a
 * folder without one (collision-loser subtree, or tree not loaded yet) stays inert text.
 * Every trail opens with the root crumb linking to `/docs`; on the root landing itself
 * (path "") the trail collapses to just the non-link current crumb.
 */
export function Breadcrumbs({ path, title }: { path: string; title: string }) {
  const { data } = useQuery(treeQuery);
  const folders = data ? foldersByPath(data.root) : new Map<string, TreeFolder>();

  const segments = path.split("/").slice(0, -1);
  // The root label is deliberately the literal "docs" (URL-truthful); a site-title override is a future plainbase.yaml concern.
  const root = { key: "/docs", label: "docs", url: "/docs" };
  const ancestors = segments.map((name, i) => {
    const folderPath = segments.slice(0, i + 1).join("/");
    const folder = folders.get(folderPath);
    return { key: folderPath, label: folder ? folderTitle(folder) : name, url: folder?.url ?? null };
  });
  // When the page IS its parent folder's landing (index/README), the parent ancestor crumb and the
  // leaf crumb are the same place: `folderTitle` resolves to the index title and the parent's url is
  // this very page's URL. Drop the redundant ancestor so the trail reads `docs / <Title>`, not
  // `docs / <Title> / <Title>` with the ancestor self-linking to the page being viewed (Phase 5.5).
  const parent = folders.get(segments.join("/"));
  const pageIsLanding = parent ? landingPage(parent)?.path === path : false;
  const crumbs = path === "" ? [] : [root, ...(pageIsLanding ? ancestors.slice(0, -1) : ancestors)];

  return (
    <nav className="pb-breadcrumbs mb-4 text-sm text-muted" data-pb-breadcrumbs aria-label="Breadcrumb">
      <ol className="flex flex-wrap items-center gap-1.5">
        {crumbs.map((crumb) => (
          <Fragment key={crumb.key}>
            <li>
              {crumb.url ? (
                <a href={crumb.url} className="hover:text-ink hover:underline">
                  {crumb.label}
                </a>
              ) : (
                crumb.label
              )}
            </li>
            <li aria-hidden="true" className="text-faint">
              /
            </li>
          </Fragment>
        ))}
        <li aria-current="page" className="font-medium text-ink">
          {title}
        </li>
      </ol>
    </nav>
  );
}
