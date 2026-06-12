import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { useEffect } from "react";
import { ApiError } from "../api/client";
import { encodeTreePath, pageByPathQuery, pageHtmlQuery, pageQuery, treeQuery } from "../api/queries";
import { firstPage, pageHref } from "../lib/tree";
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
  if (page.isError) return <PageError error={page.error} />;
  return <PageContent id={page.data.id} />;
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

/** The `/` (and bare `/docs`) target: send the visitor to the first page in tree order. */
export function FirstPageRedirect() {
  const router = useRouter();
  const tree = useQuery(treeQuery);

  const target = tree.data ? firstPage(tree.data.root) : null;
  useEffect(() => {
    if (target) router.history.replace(pageHref(target));
  }, [target, router]);

  if (tree.isError) return <PageError error={tree.error} />;
  if (tree.data && !target) return <NotFoundView />;
  return <PagePending />;
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
