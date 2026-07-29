import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouter, useRouterState } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { useEffect, useState } from "react";
import { ApiError } from "../api/client";
import { byPathKeyForUrl, encodeTreePath, pageByPathQuery, pageHtmlQuery, pageQuery, treeQuery } from "../api/queries";
import type { PageResponse, TreeFolder, TreePage } from "../api/types";
import { parsePermalink, permalinkOf } from "../lib/permalink";
import {
  folderByUrl,
  folderForLanding,
  folderTitle,
  landingPage,
  pageHref,
  rootAcceptsWrites,
  rootEntryOfUrl,
} from "../lib/tree";
import { Breadcrumbs } from "./Breadcrumbs";
import { QueryErrorView, RootUnavailableView } from "./ErrorView";
import { NotFoundView } from "./NotFound";
import { Prose } from "./Prose";
import { Toc } from "./Toc";

/**
 * The `/$` canonical route body: resolve the splat through `by-path` (canonical or
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
  const resolvedFor = `/${encodeTreePath(path)}`;
  const resolved = page.data;
  // A folder's landing page (index/README) has ONE canonical home: the folder URL. Reaching it at
  // its own bare-page URL redirects to the folder (the lookup needs the tree, kept warm by the
  // Sidebar). Otherwise the canonical target is the page's own `url` (alias → canonical).
  const landingEntry = resolved && tree.data ? folderForLanding(tree.data.roots, resolved.root, resolved.id) : null;
  useEffect(() => {
    if (!resolved || pathname !== resolvedFor) return;
    if (landingEntry) {
      if (landingEntry.folder.url && landingEntry.folder.url !== resolvedFor) {
        router.history.replace(landingEntry.folder.url + window.location.search + window.location.hash);
      }
      return;
    }
    const canonicalUrl = resolved.url;
    if (canonicalUrl && canonicalUrl !== resolvedFor) {
      // The alias response IS the canonical page — seed its by-path key so the
      // post-replace render hits cache instead of refetching the same page.
      const canonicalPath = byPathKeyForUrl(canonicalUrl);
      if (canonicalPath !== null) {
        queryClient.setQueryData(pageByPathQuery(canonicalPath).queryKey, resolved);
      }
      router.history.replace(canonicalUrl + window.location.search + window.location.hash);
    }
  }, [resolved, landingEntry, pathname, resolvedFor, router, queryClient]);

  if (page.isPending) return <PagePending />;
  if (page.isError) {
    // A by-path 404 may be a folder's URL prefix — folders aren't in by-path space (ADR-0003).
    if (page.error instanceof ApiError && page.error.isNotFound) return <FolderLanding />;
    return <PageError error={page.error} />;
  }
  // A landing page renders AS its folder (the index content replaces the generated listing); the effect canonicalizes the URL.
  if (landingEntry?.folder.url) return <FolderLanding url={landingEntry.folder.url} />;
  // The by-path response IS the page's PageResponse (frontmatter included) — hand it to the Rail
  // directly so it reads already-loaded metadata with no redundant /api/v1/pages/:id fetch.
  return <PageContent id={page.data.id} root={page.data.root} page={page.data} />;
}

/**
 * The `/$` 404 fallthrough (ADR-0003) and root landing body: by-path said no page owns
 * this location, but a folder might. The location is matched VERBATIM against the tree entries'
 * folder `url`s (the server stays the single URL authority; nothing is slugified here) and
 * nothing else: a location no entry owns is not-found here, the same answer the server gives
 * a tail whose first segment names no root. A README-preference child renders at the folder
 * URL; otherwise the generated listing. On the splat route by-path ran FIRST, so a page owning
 * the URL always shadows the folder view (the page-shadows-folder ordering, consistent with
 * ADR-0002).
 */
export function FolderLanding({ url }: { url?: string }) {
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const tree = useQuery(treeQuery);

  const target = url ?? pathname;
  const resolved = tree.data ? folderByUrl(tree.data.roots, target) : null;

  if (tree.isPending) return <PagePending />;
  if (tree.isError) return <PageError error={tree.error} />;
  if (!resolved) {
    // No folder owns the location - a 404, UNLESS a root that is not serving owns the url SPACE: its subtree is empty
    // on the wire, so every folder under it is missing and a DEEP url resolves to nothing at all (only its bare root
    // url survives, on the synthetic root folder node below). URL ownership is the one thing a down root still tells
    // us - every CONFIGURED root is listed with its url - so ask who owns the address before calling this not-found.
    const owner = rootEntryOfUrl(tree.data.roots, target);
    if (owner && !owner.available) return <RootUnavailableView root={owner.root} />;
    return <NotFoundView />;
  }
  // A root that is not serving has an EMPTY subtree on the wire (the server must never ship its stale carried
  // listing), so rendering the folder anyway would draw an empty directory over an outage - "your docs are gone"
  // instead of "this disk is not mounted". The pages under it 503 through their own requests; the folder view has
  // no request to 503, which is exactly why the flag has to be read here.
  if (!resolved.available) return <RootUnavailableView root={resolved.root} />;

  // The landing renders AT the folder URL — its one canonical home (the index/README's own bare
  // page URL redirects here; see DocsPage). With an index/README the authored content renders as the
  // WHOLE landing (prose + rail), REPLACING the generated child listing — the children stay reachable
  // through the sidebar tree. With no index, it's a purely-generated listing — no rail, but the rail
  // column stays reserved so the content width matches a page (see FolderListing).
  const landing = landingPage(resolved.folder);
  return landing ? <PageContent id={landing.id} root={resolved.root} /> : <FolderListing root={resolved.root} folder={resolved.folder} />;
}

/**
 * The purely-generated directory view (no index/README): `_folder.yaml` title (else name) as
 * heading, then the generated listing. `data-pb-folder` marks this rail-less generated view.
 *
 * It has no rail or TOC, but mirrors PageContent's column shell — reading column centered at 72ch,
 * an (empty) rail column held open beside it — so the content lands at the same width as a page.
 * Without that spacer the listing would bleed full-bleed and jar against every page view.
 */
function FolderListing({ root, folder }: { root: string; folder: TreeFolder }) {
  const title = folderTitle(folder) || root;
  useEffect(() => {
    document.title = `${title} · Plainbase`;
  }, [title]);

  return (
    <div className="pb-folder flex gap-12" data-pb-folder>
      <div className="min-w-0 flex-1">
        <div className="mx-auto max-w-[72ch]">
          <Breadcrumbs root={root} path={folder.path} title={title} />
          <h1 className="text-3xl font-bold text-ink">{title}</h1>
          <FolderListingGroups root={root} folder={folder} />
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
 * Pages link via their node `url` (losers via the rooted `/p/{root}/{id}`); subfolders via their
 * folder `url` (a loser subfolder has none and stays an inert card). `data-pb-folder*` hooks are
 * stable selectors.
 */
function FolderListingGroups({ root, folder }: { root: string; folder: TreeFolder }) {
  const subfolders = folder.children.filter((c): c is TreeFolder => c.type === "folder");
  const pages = folder.children.filter((c): c is TreePage => c.type === "page");

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
                  href={pageHref(root, child)}
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
  const name = folderTitle(folder);
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
 * The `/p/$` route body. A collision loser has `url = null`, so its permalink cannot 302 anywhere and
 * the server serves the SPA shell (200) — for BOTH forms of the address: the ROOTED `/p/{root}/{id}`
 * the server emits, and the BARE `/p/{id}` that is still legal and still served. Here the page is
 * fetched BY ID, pinned to the parsed root, and rendered at the permalink itself. If the page turns out
 * to have a canonical url after all (e.g. the collision resolved since the link was minted), we
 * replaceState across to it — mirroring the server's 302 for winners.
 */
export function PermalinkPage({ splat }: { splat: string }) {
  // Both forms arrive here, and trailing segments after the id are decorative, like the server route.
  // The PARSED root, not the response's: this root is what the request must CARRY, so it cannot come
  // from the response to that same request. It is legitimately null on the bare arm.
  const { root, id, prefix } = parsePermalink(splat);
  const router = useRouter();
  const page = useQuery(pageQuery(id, root));
  const pathname = useRouterState({ select: (s) => s.location.pathname });

  const canonicalUrl = page.data?.url;
  // Compared against the parse's own `prefix`, never a rebuilt string: the guard used to reconstruct
  // the address from a variable the parse had already mangled, so the two drifted together.
  const stillHere = pathname === prefix || pathname.startsWith(`${prefix}/`);
  useEffect(() => {
    if (canonicalUrl && stillHere) {
      router.history.replace(canonicalUrl + window.location.search + window.location.hash);
    }
  }, [canonicalUrl, stillHere, router]);

  if (page.isPending) return <PagePending />;
  if (page.isError) return <PermalinkError error={page.error} id={id} />;
  // The permalink response is the page's PageResponse — hand it to the Rail, no redundant fetch. Still the
  // PARSED root, never the response's: the client acts on the address the reader used. Re-pin the html leg
  // to the root the metadata read NAMED and this view silently resolves an ambiguity the server refuses to -
  // once the id is duplicated, a fresh load of the same bare `/p/{id}` answers 300 while the pinned render
  // shows a page, which is the click-vs-reload split the structural gate exists to close.
  return <PageContent id={page.data.id} root={root} page={page.data} />;
}

/**
 * The permalink route's error surface: the shared one, plus the single thing only THIS caller can supply. A
 * bare `/p/{id}` whose id lives in more than one root reads 409 `ambiguous_page_id`, and that message ENDS
 * "retry against one of the candidate roots below" - so rendering the message alone points the remedy at a
 * list that does not exist. The envelope carries the candidate roots and the address carries the id, so the
 * links are built here, from `permalinkOf` (the same emitter mirror `pageHref` uses - no new URL semantics).
 * NOT the candidates' own `url`s: those are the API retry targets, and would send a reader to JSON.
 */
function PermalinkError({ error, id }: { error: Error; id: string }) {
  const candidates = error instanceof ApiError ? error.candidates : [];
  if (candidates.length === 0) return <PageError error={error} />;
  return (
    <QueryErrorView error={error}>
      <ul className="mt-4 space-y-1" data-pb-candidates>
        {candidates.map((candidate) => (
          <li key={candidate.root}>
            <a href={permalinkOf(candidate.root, id)} className="font-medium text-link hover:text-link-hover hover:underline">
              {candidate.root}
            </a>
          </li>
        ))}
      </ul>
    </QueryErrorView>
  );
}

/**
 * Breadcrumbs + server HTML + doc footer in the main column, with a metadata Rail + TOC in
 * the right rail. HTML is the primary content and gates the view (pending/error → the whole
 * page); the Rail/footer read the page's frontmatter. Callers that already hold the page's
 * `PageResponse` (the root-qualified by-path route, the permalink route) pass it in via [seeded], so
 * the Rail reads already-loaded metadata with NO extra `/api/v1/pages/:id` fetch. Only a
 * folder-landing child — which arrives with just a tree-node id — fetches `pageQuery` here, and a
 * slow or failed fetch degrades the Rail to its always-present File row, never blanking the doc.
 *
 * BOTH reads carry [root], and it is NULLABLE on purpose. An id can be held by more than one root (per-root
 * identity, C5), so a bare id-addressed read of a duplicated id answers 409 `ambiguous_page_id` and this view
 * renders an error rather than picking a root - which is the RIGHT answer for the one caller that has no root
 * to give: a bare `/p/{id}`, whose reader named none. The rooted callers all pass a real one (the two by-path
 * routes from the response, the folder landing from the tree entry, the rooted permalink from its address), so
 * making this non-nullable would only let the bare route infer a root from a response and render a page the
 * server would refuse to serve on reload.
 */
function PageContent({ id, root, page: seeded }: { id: string; root: string | null; page?: PageResponse }) {
  const html = useQuery(pageHtmlQuery(id, root));
  // Fetch by id only when the caller didn't already resolve the page (folder-landing path).
  const fetched = useQuery({ ...pageQuery(id, root), enabled: seeded === undefined });
  const page = seeded ?? fetched.data;
  // The page names its own root; the TREE is what says whether that root takes writes. Read-only, down, and
  // not-yet-known roots get no Edit affordance - the same call Shell makes for "New", and for the same
  // reason: the alternative is an editor session that can only end in a 403 (or a 503) at save.
  const tree = useQuery(treeQuery);
  const editable = rootAcceptsWrites(tree.data?.roots, html.data?.root ?? null);

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
          <Breadcrumbs root={html.data.root} path={html.data.path} title={html.data.title} />
          <Prose html={html.data.html} />
          <DocFooter
            frontmatter={frontmatter}
            url={page?.url ?? null}
            editable={editable}
            hasHistory={(page?.commit ?? null) !== null}
          />
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
 * (no canonical url) gets no Edit/History link (it has no root-content address). The History link gates on
 * `hasHistory` (W7/MF-1: `PageResponse.commit != null` — git-on with ≥1 commit — a ZERO-extra-fetch signal;
 * NoOp git always yields null so git-off never false-positives, and a zero-commit page correctly shows none).
 *
 * [editable] is the root's topology bit (`RootTree.editable`), not a permission: a READ-ONLY root's pages
 * offer no Edit link at all, because every write into one answers 403 `root_not_editable` in every auth mode.
 * History is NOT gated on it - a read-only root's history is perfectly readable.
 */
function DocFooter({
  frontmatter,
  url,
  editable,
  hasHistory,
}: {
  frontmatter?: Record<string, unknown>;
  url: string | null;
  editable: boolean;
  hasHistory: boolean;
}) {
  const updated = asString(frontmatter?.updated);
  const owner = asString(frontmatter?.owner);
  const splat = byPathKeyForUrl(url);
  // A read-only page with no `updated` and no history has nothing to put in the footer - render no footer at
  // all rather than an empty frame (a `splat` alone no longer implies an Edit link).
  if (!(splat && (editable || hasHistory)) && !updated) return null;
  return (
    <div className="pb-docfoot" data-pb-docfoot>
      {splat && editable && (
        <Link to="/$" params={{ _splat: splat }} search={{ mode: "edit" }} className="pb-docfoot-edit" data-pb-edit-page>
          Edit this page
        </Link>
      )}
      {splat && hasHistory && (
        <Link to="/$" params={{ _splat: splat }} search={{ mode: "history" }} className="pb-docfoot-history" data-pb-history-page>
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
  // Everything else - including the outage arriving the other way (a 503 on the page request rather than the tree's
  // flag) - is the shared query-error surface's call, not this one's.
  return <QueryErrorView error={error} />;
}
