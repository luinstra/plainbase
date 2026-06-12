import { useQuery } from "@tanstack/react-query";
import { Fragment } from "react";
import { treeQuery } from "../api/queries";
import { folderTitles } from "../lib/tree";

/**
 * Breadcrumb trail derived from the page's content-relative `path` (the API's value,
 * verbatim); folder display titles come from the tree. Folders have no landing pages in
 * Phase 1, so ancestor crumbs are inert text.
 */
export function Breadcrumbs({ path, title }: { path: string; title: string }) {
  const { data } = useQuery(treeQuery);
  const titles = data ? folderTitles(data.root) : new Map<string, string | null>();

  const segments = path.split("/").slice(0, -1);
  const crumbs = segments.map((name, i) => {
    const folderPath = segments.slice(0, i + 1).join("/");
    return { key: folderPath, label: titles.get(folderPath) ?? name };
  });

  return (
    <nav className="pb-breadcrumbs mb-4 text-sm text-muted" data-pb-breadcrumbs aria-label="Breadcrumb">
      <ol className="flex flex-wrap items-center gap-1.5">
        {crumbs.map((crumb) => (
          <Fragment key={crumb.key}>
            <li>{crumb.label}</li>
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
