import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouter, useRouterState } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { useEffect, useState } from "react";
import { ApiError } from "../api/client";
import { encodeTreePath, pageByPathQuery, pageHtmlQuery, pageQuery, treeQuery } from "../api/queries";
import type { PageResponse, TreeFolder, TreePage } from "../api/types";
import { folderByUrl, folderForLanding, landingPage, pageHref } from "../lib/tree";
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
  const tree = useQuery(treeQuery);
  const pathname = useRouterState({ select: (s) => s.location.pathname });

  // The URL this component was resolved FOR. The replace must only fire while the address
  // bar still shows it — during a click-navigation the outgoing page briefly observes the
  // incoming pathname, and an unguarded compare would snap the URL straight back.
  const resolvedFor = `/docs/${encodeTreePath(path)}`;
  const resolved = page.data;
  // A folder's landing page (index/README) has ONE canonical home: the folder URL. Reaching it at
  // its own bare-page URL redirects to the folder (the lookup needs the tree, kept warm by the
  // Sidebar). Otherwise the canonical target is the page's own `url` (alias → canonical).
  const landingFolder = resolved && tree.data ? folderForLanding(tree.data.root, resolved.id) : null;
  useEffect(() => {
    if (!resolved || pathname !== resolvedFor) return;
    if (landingFolder) {
      if (landingFolder.url && landingFolder.url !== resolvedFor) {
        router.history.replace(landingFolder.url + window.location.search + window.location.hash);
      }
      return;
    }
    const canonicalUrl = resolved.url;
    if (canonicalUrl && canonicalUrl !== resolvedFor) {
      // The alias response IS the canonical page — seed its by-path key so the
      // post-replace render hits cache instead of refetching the same page.
      if (canonicalUrl.startsWith("/docs/")) {
        const canonicalPath = canonicalUrl.slice("/docs/".length).split("/").map(decodeURIComponent).join("/");
        queryClient.setQueryData(pageByPathQuery(canonicalPath).queryKey, resolved);
      }
      router.history.replace(canonicalUrl + window.location.search + window.location.hash);
    }
  }, [resolved, landingFolder, pathname, resolvedFor, router, queryClient]);

  if (page.isPending) return <PagePending />;
  if (page.isError) {
    // A by-path 404 may be a folder's URL prefix — folders aren't in by-path space (ADR-0003).
    if (page.error instanceof ApiError && page.error.isNotFound) return <FolderLanding />;
    return <PageError error={page.error} />;
  }
  // A landing page renders AS its folder (prose + child listing); the effect canonicalizes the URL.
  if (landingFolder?.url) return <FolderLanding url={landingFolder.url} />;
  // The by-path response IS the page's PageResponse (frontmatter included) — hand it to the Rail
  // directly so it reads already-loaded metadata with no redundant /api/v1/pages/:id fetch.
  return <PageContent id={page.data.id} page={page.data} />;
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

  // The landing renders AT the folder URL — its one canonical home (the index/README's own bare
  // page URL redirects here; see DocsPage). With an index/README the authored content renders as a
  // real page (prose + rail) and the generated listing follows BELOW it (the index itself excluded).
  // With no index, it's a purely-generated listing — no rail, but the rail column stays reserved so
  // the content width matches a page (see FolderListing).
  const landing = landingPage(folder);
  return landing ? (
    <PageContent id={landing.id} below={<FolderListingGroups folder={folder} excludeId={landing.id} />} />
  ) : (
    <FolderListing folder={folder} />
  );
}

/**
 * The purely-generated directory view (no index/README): `_folder.yaml` title (else name) as
 * heading, then the generated listing. `data-pb-folder` marks this rail-less generated view.
 *
 * It has no rail or TOC, but mirrors PageContent's column shell — reading column centered at 72ch,
 * an (empty) rail column held open beside it — so the content lands at the same width as a page.
 * Without that spacer the listing would bleed full-bleed and jar against every page view.
 */
function FolderListing({ folder }: { folder: TreeFolder }) {
  // The root has no `_folder.yaml` title and its name is "" — "docs" mirrors the root breadcrumb.
  const title = folder.title ?? (folder.name || "docs");
  useEffect(() => {
    document.title = `${title} · Plainbase`;
  }, [title]);

  return (
    <div className="pb-folder flex gap-12" data-pb-folder>
      <div className="min-w-0 flex-1">
        <div className="mx-auto max-w-[72ch]">
          <Breadcrumbs path={folder.path} title={title} />
          <h1 className="text-3xl font-bold text-ink">{title}</h1>
          <FolderListingGroups folder={folder} />
        </div>
      </div>
      {/* Rail column reserved (empty) — no rail/TOC here, but the reading column keeps a page's width. */}
      <div className="hidden w-[clamp(14rem,18vw,20rem)] shrink-0 xl:block" aria-hidden="true" />
    </div>
  );
}

/**
 * The generated child groups — subfolders into a card grid, pages into a compact list — each
 * group preserving the tree response's order (never re-sorted; a stable partition, not a sort).
 * Pages link via their node `url` (losers via `/p/{id}`); subfolders via their folder `url` (a
 * loser subfolder has none and stays an inert card). `excludeId` drops one child (the index/README
 * already rendered as prose above). `data-pb-folder*` hooks are stable selectors.
 */
function FolderListingGroups({ folder, excludeId }: { folder: TreeFolder; excludeId?: string }) {
  const subfolders = folder.children.filter((c): c is TreeFolder => c.type === "folder");
  const pages = folder.children.filter((c): c is TreePage => c.type === "page" && c.id !== excludeId);

  return (
    <div className="pb-listing" data-pb-folder-children>
      {subfolders.length > 0 && (
          <section className="pb-listing-group">
            <div className="pb-listing-label">Folders</div>
            <div className="pb-folder-grid">
              {subfolders.map((child) => (
                <FolderCard key={child.path} folder={child} />
              ))}
            </div>
          </section>
        )}
        {pages.length > 0 && (
          <section className="pb-listing-group">
            <div className="pb-listing-label">Pages</div>
            <div className="pb-page-grid">
              {pages.map((child) => (
                <a
                  key={child.id}
                  href={pageHref(child)}
                  data-pb-folder-child="page"
                  data-pb-status={child.status}
                  className="pb-page-row"
                >
                  <span className="pb-pdot" data-pb-status={child.status} aria-hidden="true" />
                  <span className="pt">{child.title}</span>
                  {child.updated && <span className="pdate">{child.updated}</span>}
                </a>
              ))}
            </div>
          </section>
        )}
      </div>
  );
}

/** A subfolder landing card: icon + name + optional description + `path/ · N pages` meta. A
 * collision-loser subfolder has `url === null` and renders inert (no link). */
function FolderCard({ folder }: { folder: TreeFolder }) {
  const name = folder.title ?? folder.name;
  const pageLabel = folder.page_count === 1 ? "1 page" : `${folder.page_count} pages`;
  const body = (
    <>
      <span className="ficon" aria-hidden="true">
        <FolderIcon />
      </span>
      <span>
        <span className="fn">{name}</span>
        {folder.description && <span className="fm">{folder.description}</span>}
        <span className="fc">
          {folder.path}/ · {pageLabel}
        </span>
      </span>
    </>
  );
  return folder.url ? (
    <a href={folder.url} data-pb-folder-child="folder" className="pb-folder-card">
      {body}
    </a>
  ) : (
    <div data-pb-folder-child="folder" className="pb-folder-card pb-folder-card-inert">
      {body}
    </div>
  );
}

/** The landing-card folder icon — `currentColor` stroke SVG (the design accepts this icon, unlike
 * the sidebar's rejected ones); matches the ThemeToggle icon idiom. */
function FolderIcon() {
  return (
    <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <path d="M3 7a2 2 0 0 1 2-2h4l2 2.5h6a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" />
    </svg>
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
  // The permalink response is the page's PageResponse — hand it to the Rail, no redundant fetch.
  return <PageContent id={page.data.id} page={page.data} />;
}

/**
 * Breadcrumbs + server HTML + doc footer in the main column, with a metadata Rail + TOC in
 * the right rail. HTML is the primary content and gates the view (pending/error → the whole
 * page); the Rail/footer read the page's frontmatter. Callers that already hold the page's
 * `PageResponse` (the `/docs/*` by-path route, the permalink route) pass it in via [seeded], so
 * the Rail reads already-loaded metadata with NO extra `/api/v1/pages/:id` fetch. Only a
 * folder-landing child — which arrives with just a tree-node id — fetches `pageQuery` here, and a
 * slow or failed fetch degrades the Rail to its always-present File row, never blanking the doc.
 *
 * [below] is optional content rendered under the prose in the reading column — the generated
 * child listing when this page IS a folder's index/README landing (the rail still belongs here
 * because there's authored content; a purely-generated folder view has no rail — see FolderLanding).
 */
function PageContent({ id, page: seeded, below }: { id: string; page?: PageResponse; below?: ReactNode }) {
  const html = useQuery(pageHtmlQuery(id));
  // Fetch by id only when the caller didn't already resolve the page (folder-landing path).
  const fetched = useQuery({ ...pageQuery(id), enabled: seeded === undefined });
  const page = seeded ?? fetched.data;

  const title = html.data?.title;
  useEffect(() => {
    if (title) document.title = `${title} · Plainbase`;
  }, [title]);

  if (html.isPending) return <PagePending />;
  if (html.isError) return <PageError error={html.error} />;

  const frontmatter = page?.frontmatter;
  return (
    <div className="flex gap-12">
      {/* The reading column takes the middle and centers at a readable width; the side columns
          (sidebar + this rail) grow/shrink with the window up to their clamp caps. */}
      <div className="min-w-0 flex-1">
        <div className="mx-auto max-w-[72ch]">
          <Breadcrumbs path={html.data.path} title={html.data.title} />
          <Prose html={html.data.html} />
          {below}
          <DocFooter frontmatter={frontmatter} url={page?.url ?? null} hasHistory={(page?.commit ?? null) !== null} />
        </div>
      </div>
      <aside
        className="pb-rail sticky top-20 hidden max-h-[calc(100vh-6rem)] w-[clamp(14rem,18vw,20rem)] shrink-0 overflow-y-auto xl:block"
        data-pb-rail
      >
        <DocRail frontmatter={frontmatter} path={html.data.path} />
        <Toc headings={html.data.headings} />
      </aside>
    </div>
  );
}

/** Coerce an untrusted frontmatter scalar to a non-blank string, else null. */
function asString(value: unknown): string | null {
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

/** Coerce frontmatter `tags` to a string list: a `string[]` keeps its strings, a bare string
 * becomes a singleton, anything else is empty. */
function asTags(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((tag): tag is string => typeof tag === "string");
  const single = asString(value);
  return single ? [single] : [];
}

/** Up-to-two avatar initials, uppercased: the leading char of each of the first two words
 * (`Ada Lovelace` → `AL`), or — for a single-word owner — its first two characters
 * (`ops` → `OP`). */
function ownerInitials(owner: string): string {
  const words = owner.trim().split(/\s+/);
  const initials = words.length > 1 ? words.slice(0, 2).map((word) => word.charAt(0)).join("") : words[0].slice(0, 2);
  return initials.toUpperCase();
}

/**
 * The right-rail metadata list — a de-chromed quiet list of frontmatter fields (owner /
 * status / tags / updated / review) plus the always-present source File path. Missing keys
 * drop their row. This is app chrome: it renders in the rail `<aside>`, never inside
 * `.pb-prose`.
 */
function DocRail({ frontmatter, path }: { frontmatter?: Record<string, unknown>; path: string }) {
  const owner = asString(frontmatter?.owner);
  const status = asString(frontmatter?.status);
  const tags = asTags(frontmatter?.tags);
  const updated = asString(frontmatter?.updated);
  const review = asString(frontmatter?.review);

  return (
    <div className="pb-rail-card" data-pb-rail-meta>
      <div className="pb-rail-head">Page info</div>
      <div className="pb-meta">
        {owner && (
          <MetaRow label="Owner">
            <span className="pb-avatar" aria-hidden="true">
              {ownerInitials(owner)}
            </span>
            {owner}
          </MetaRow>
        )}
        {status && (
          <MetaRow label="Status">
            <span className="pb-chip" data-pb-chip-status={status}>
              <span className="pb-chip-dot" aria-hidden="true" />
              {status}
            </span>
          </MetaRow>
        )}
        {tags.length > 0 && (
          <MetaRow label="Tags">
            {tags.map((tag) => (
              <span key={tag} className="pb-tag">
                {tag}
              </span>
            ))}
          </MetaRow>
        )}
        {updated && (
          <MetaRow label="Updated">
            <span className="pb-mono-val">{updated}</span>
          </MetaRow>
        )}
        {review && (
          <MetaRow label="Review">
            <span className="pb-mono-val">{review}</span>
          </MetaRow>
        )}
        <MetaRow label="File">
          <FilePath path={path} />
        </MetaRow>
      </div>
    </div>
  );
}

/**
 * The source path. A deep path is truncated with a LEADING ellipsis (the filename end stays
 * visible) so it always fits the rail instead of forcing a horizontal scrollbar; the full path
 * is available on hover (`title`) and by clicking to expand it inline (wrapped).
 */
function FilePath({ path }: { path: string }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <button
      type="button"
      className={expanded ? "pb-path-val pb-path-val-full" : "pb-path-val"}
      data-pb-path=""
      aria-expanded={expanded}
      title={path}
      onClick={() => setExpanded((v) => !v)}
    >
      {path}
    </button>
  );
}

function MetaRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="pb-meta-row">
      <span className="pb-meta-key">{label}</span>
      <span className="pb-meta-val">{children}</span>
    </div>
  );
}

/**
 * The doc footer below `<Prose>` (a sibling, never inside it): the "Edit this page" affordance
 * (W6/D-3 — links to the SAME path with `?mode=edit`, the canonical url is the splat key so the editor
 * inherits rename-stability), the W7 "History" affordance beside it, plus a mono "Last updated {date} by
 * {owner}" line sourced from frontmatter. The Edit link renders regardless of `updated`. A collision loser
 * (no canonical url) gets no Edit/History link (it has no `/docs` address). The History link gates on
 * `hasHistory` (W7/MF-1: `PageResponse.commit != null` — git-on with ≥1 commit — a ZERO-extra-fetch signal;
 * NoOp git always yields null so git-off never false-positives, and a zero-commit page correctly shows none).
 */
function DocFooter({ frontmatter, url, hasHistory }: { frontmatter?: Record<string, unknown>; url: string | null; hasHistory: boolean }) {
  const updated = asString(frontmatter?.updated);
  const owner = asString(frontmatter?.owner);
  const splat = url?.startsWith("/docs/") ? url.slice("/docs/".length).split("/").map(decodeURIComponent).join("/") : null;
  if (!splat && !updated) return null;
  return (
    <div className="pb-docfoot" data-pb-docfoot>
      {splat && (
        <Link to="/docs/$" params={{ _splat: splat }} search={{ mode: "edit" }} className="pb-docfoot-edit" data-pb-edit-page>
          Edit this page
        </Link>
      )}
      {splat && hasHistory && (
        <Link to="/docs/$" params={{ _splat: splat }} search={{ mode: "history" }} className="pb-docfoot-history" data-pb-history-page>
          History
        </Link>
      )}
      {updated && (
        <div className="pb-docfoot-updated">
          Last updated {updated}
          {owner ? ` by ${owner}` : ""}
        </div>
      )}
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
