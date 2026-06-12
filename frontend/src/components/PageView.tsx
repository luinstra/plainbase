import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { useEffect } from "react";
import { ApiError } from "../api/client";
import { encodeTreePath, pageByPathQuery, pageHtmlQuery, pageQuery, treeQuery } from "../api/queries";
import type { TreeFolder } from "../api/types";
import { folderByUrl, landingPage, pageHref } from "../lib/tree";
import { Breadcrumbs } from "./Breadcrumbs";
import { NotFoundView } from "./NotFound";
import { Prose } from "./Prose";
import { Toc } from "./Toc";

/**
 * The `/docs/$` canonical route body: resolve the splat through `by-path` (canonical or
 * alias), then render by id. When the response's canonical `url` differs from the address
 * bar (alias resolved mid-rebuild, page moved under us), the URL is replaceState'd to the
 * canonical — the server's `url` is the single source of URL truth (§A4).
 */
export function DocsPage({ path }: { path: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const page = useQuery(pageByPathQuery(path));
  const pathname = useRouterState({ select: (s) => s.location.pathname });

  // The URL this component was resolved FOR. The replace must only fire while the address
  // bar still shows it — during a click-navigation the outgoing page briefly observes the
  // incoming pathname, and an unguarded compare would snap the URL straight back.
  const resolvedFor = `/docs/${encodeTreePath(path)}`;
  const resolved = page.data;
  useEffect(() => {
    const canonicalUrl = resolved?.url;
    if (canonicalUrl && pathname === resolvedFor && canonicalUrl !== resolvedFor) {
      // The alias response IS the canonical page — seed its by-path key so the
      // post-replace render hits cache instead of refetching the same page.
      if (canonicalUrl.startsWith("/docs/")) {
        const canonicalPath = canonicalUrl.slice("/docs/".length).split("/").map(decodeURIComponent).join("/");
        queryClient.setQueryData(pageByPathQuery(canonicalPath).queryKey, resolved);
      }
      router.history.replace(canonicalUrl + window.location.search + window.location.hash);
    }
  }, [resolved, pathname, resolvedFor, router, queryClient]);

  if (page.isPending) return <PagePending />;
  if (page.isError) {
    // A by-path 404 may be a folder's URL prefix — folders aren't in by-path space (ADR-0003).
    if (page.error instanceof ApiError && page.error.isNotFound) return <FolderLanding />;
    return <PageError error={page.error} />;
  }
  return <PageContent id={page.data.id} />;
}

/**
 * The `/docs/$` 404 fallthrough (ADR-0003) AND the bare `/docs` route body: by-path said
 * no page owns this location — but a folder might (bare `/docs` is always the root
 * folder; no page can own it, so that route skips by-path entirely and passes `url`
 * explicitly). The location is matched VERBATIM against the tree's folder `url`s (the
 * server stays the single URL authority; nothing is slugified here). A README-preference
 * child renders at the folder URL; otherwise the generated listing. On the splat route
 * by-path ran FIRST, so a page owning the URL always shadows the folder view (the
 * page-shadows-folder ordering, consistent with ADR-0002).
 */
export function FolderLanding({ url }: { url?: string }) {
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const tree = useQuery(treeQuery);

  if (tree.isPending) return <PagePending />;
  if (tree.isError) return <PageError error={tree.error} />;

  const folder = folderByUrl(tree.data.root, url ?? pathname);
  if (!folder) return <NotFoundView />;

  // The landing page renders AT the folder URL — no redirect; it stays independently
  // reachable at its own canonical URL — fetched by id like a loser permalink.
  const landing = landingPage(folder);
  return landing ? <PageContent id={landing.id} /> : <FolderListing folder={folder} />;
}

/**
 * The generated directory view: `_folder.yaml` title (else name) as heading, children in
 * TREE ORDER — the order the tree response carries, never re-sorted. Pages link via their
 * node `url` (losers via `/p/{id}`); subfolders via their folder `url` (a loser subfolder
 * has none and stays inert text). `data-pb-folder*` hooks are stable selectors.
 */
function FolderListing({ folder }: { folder: TreeFolder }) {
  // The root has no `_folder.yaml` title and its name is "" — "docs" mirrors the root breadcrumb.
  const title = folder.title ?? (folder.name || "docs");
  useEffect(() => {
    document.title = `${title} · Plainbase`;
  }, [title]);

  return (
    <div className="pb-folder" data-pb-folder>
      <Breadcrumbs path={folder.path} title={title} />
      <h1 className="text-3xl font-bold text-ink">{title}</h1>
      <ul className="mt-6 space-y-1.5" data-pb-folder-children>
        {folder.children.map((child) =>
          child.type === "folder" ? (
            <li key={child.path} data-pb-folder-child="folder">
              {child.url ? (
                <a href={child.url} className="font-medium text-link hover:text-link-hover hover:underline">
                  {child.title ?? child.name}
                </a>
              ) : (
                <span className="font-medium text-muted">{child.title ?? child.name}</span>
              )}
            </li>
          ) : (
            <li key={child.id} data-pb-folder-child="page">
              <a href={pageHref(child)} data-pb-status={child.status} className="text-link hover:text-link-hover hover:underline">
                {child.title}
              </a>
            </li>
          ),
        )}
      </ul>
    </div>
  );
}

/**
 * The `/p/$` route body — the chunk-6 amendment: a collision loser has `url = null`, so
 * its permalink cannot 302 anywhere and the server serves the SPA shell (200). Here the
 * page is fetched BY ID and rendered at the permalink itself. If the page turns out to
 * have a canonical url after all (e.g. the collision resolved since the link was minted),
 * we replaceState across to it — mirroring the server's 302 for winners.
 */
export function PermalinkPage({ splat }: { splat: string }) {
  // Trailing segments after the id are tolerated and ignored, like the server route.
  const id = splat.split("/")[0] ?? "";
  const router = useRouter();
  const page = useQuery(pageQuery(id));
  const pathname = useRouterState({ select: (s) => s.location.pathname });

  const canonicalUrl = page.data?.url;
  const stillHere = pathname === `/p/${id}` || pathname.startsWith(`/p/${id}/`);
  useEffect(() => {
    if (canonicalUrl && stillHere) {
      router.history.replace(canonicalUrl + window.location.search + window.location.hash);
    }
  }, [canonicalUrl, stillHere, router]);

  if (page.isPending) return <PagePending />;
  if (page.isError) return <PageError error={page.error} />;
  return <PageContent id={page.data.id} />;
}

/** Breadcrumbs + server HTML + TOC for a resolved page id. */
function PageContent({ id }: { id: string }) {
  const html = useQuery(pageHtmlQuery(id));

  const title = html.data?.title;
  useEffect(() => {
    if (title) document.title = `${title} · Plainbase`;
  }, [title]);

  if (html.isPending) return <PagePending />;
  if (html.isError) return <PageError error={html.error} />;

  return (
    <div className="flex gap-12">
      <div className="min-w-0 flex-1">
        <Breadcrumbs path={html.data.path} title={html.data.title} />
        <Prose html={html.data.html} />
      </div>
      <Toc headings={html.data.headings} />
    </div>
  );
}

function PagePending() {
  return (
    <p className="py-16 text-center text-faint" data-pb-loading>
      Loading…
    </p>
  );
}

function PageError({ error }: { error: Error }) {
  if (error instanceof ApiError && (error.isNotFound || error.status === 400)) return <NotFoundView />;
  return (
    <div className="py-16 text-center" data-pb-error>
      <h1 className="text-2xl font-bold text-ink">Something went wrong</h1>
      <p className="mt-3 text-muted">{error.message}</p>
    </div>
  );
}
