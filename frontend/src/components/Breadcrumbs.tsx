import { useQuery } from "@tanstack/react-query";
import { Fragment } from "react";
import { treeQuery } from "../api/queries";
import type { TreeFolder } from "../api/types";
import { folderTitle, foldersByPath, landingPage, treeFor } from "../lib/tree";

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
  const data = tree.data;
  const entryTree = data ? treeFor(data.roots, root) : null;
  const folders = entryTree ? foldersByPath(entryTree) : new Map<string, TreeFolder>();

  const segments = path.split("/").slice(0, -1);
  // The root crumb is NAMED only when there is more than one root - the same `roots.length > 1` rule the
  // sidebar section headers and the search root badges follow, and for the same reason: with the single
  // root every legacy install has, "main" is an internal name leaking into the UI where a meaningful word
  // used to be. One root keeps the URL-truthful `docs` -> `/docs` crumb it has always had.
  //
  // With 2+ roots the crumb names THIS page's root and links to that root's own url - taken from the tree
  // entry (server-issued, consumed verbatim like every other crumb), NEVER string-built from the name. A
  // hardcoded `/docs` crumb would name the wrong tree AND, since `/docs` resolves to main, walk the reader
  // out of the root they were reading.
  //
  // **Until the tree RESOLVES, the COUNT is unknown - so the crumb is inert rather than wrong.** The root's
  // NAME is in hand (the page response carries it), but the single-root `docs` -> `/docs` form is only correct
  // if there IS one root, and on a multi-root install `/docs` resolves to MAIN: a reader on an extra root's page
  // who clicks that crumb in the pending window is walked out of the tree they are reading and into a different
  // one. `Shell` makes the same call for "New" in the same window (disabled beats wrong); here the crumb still
  // renders - the trail must not reflow - it simply does not link anywhere.
  //
  // A FAILED tree is that same inert trail, and that is the bug this splits: `data?.roots` alone renders the
  // pending window and the error IDENTICALLY, so a reader whose tree fetch died sits in front of a trail that
  // will never link, with nothing to tell them it is not still loading. The degraded render is right either way
  // (we still do not know the count); what differs is that the pending one RESOLVES and the failed one does not,
  // so the failure is SAID (below) and the pending window is announced as busy instead of silently waiting.
  const roots = data?.roots;
  const rootCrumb = !roots
    ? { key: "/docs", label: "docs", url: null }
    : roots.length > 1
      ? { key: `root:${root}`, label: root, url: entryTree?.url ?? null }
      : { key: "/docs", label: "docs", url: "/docs" };
  const ancestors = segments.map((name, i) => {
    const folderPath = segments.slice(0, i + 1).join("/");
    const folder = folders.get(folderPath);
    return { key: folderPath, label: folder ? folderTitle(folder) : name, url: folder?.url ?? null };
  });
  // When the page IS its parent folder's landing (index/README), the parent ancestor crumb and the
  // leaf crumb are the same place: `folderTitle` resolves to the index title and the parent's url is
  // this very page's URL. Drop the redundant ancestor so the trail reads `<root> / <Title>`, not
  // `<root> / <Title> / <Title>` with the ancestor self-linking to the page being viewed (Phase 5.5).
  const parent = folders.get(segments.join("/"));
  const pageIsLanding = parent ? landingPage(parent)?.path === path : false;
  const crumbs = path === "" ? [] : [rootCrumb, ...(pageIsLanding ? ancestors.slice(0, -1) : ancestors)];

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
