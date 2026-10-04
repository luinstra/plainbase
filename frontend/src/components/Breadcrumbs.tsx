import { useQuery } from "@tanstack/react-query";
import { Fragment } from "react";
import { treeQuery } from "../api/queries";
import { breadcrumbTrail } from "../lib/breadcrumbs";

/**
 * Breadcrumb trail derived from the page's content-relative `path` (the API's value,
 * verbatim); folder display titles and landing urls come from `root`'s tree entry - the
 * lookup is root-SCOPED (multi-root C3): identical relative paths in two roots must never
 * borrow each other's titles/urls. An ancestor crumb links to its folder landing view
 * (ADR-0003) - server-issued `url`s consumed verbatim; a folder without one
 * (collision-loser subtree, or tree not loaded yet) stays inert text. Every trail opens
 * with the root crumb; on the root landing itself (path "") the trail collapses to just
 * the non-link current crumb.
 */
export function Breadcrumbs({ root, path, title }: { root: string; path: string; title: string }) {
  const tree = useQuery(treeQuery);
  const crumbs = breadcrumbTrail(tree.data?.roots ?? [], root, path);

  return (
    <nav className="pb-breadcrumbs mb-4 text-sm text-muted" data-pb-breadcrumbs aria-label="Breadcrumb" aria-busy={tree.isPending}>
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
      {/* Nav-sized, like the sidebar's outage notice: the page itself loaded and is perfectly readable, so a
          full-page error would be a lie about what is broken. It says the one thing the reader cannot see from
          the inert trail - that it is not coming. */}
      {tree.isError && (
        <p className="mt-1 text-xs text-muted" data-pb-breadcrumbs-error>
          Couldn’t load the navigation tree, so this trail can’t link anywhere. Reload to try again.
        </p>
      )}
    </nav>
  );
}
