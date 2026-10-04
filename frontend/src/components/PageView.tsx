import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouter, useRouterState } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { useEffect, useId, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { useDiscussionHighlight } from "../lib/useDiscussionHighlight";
import type { DiscussionDetail } from "../api/types";
import { pageDiscussionsQuery } from "../api/discussions";
import { byPathKeyForUrl, encodeTreePath, pageByPathQuery, pageHtmlKey, pageHtmlQuery, pageKey, pageQuery, treeQuery } from "../api/queries";
import type { DiscussionQuoteRequestAnchor, PageHtmlResponse, PageResponse, TreeDiagram, TreeFolder, TreePage } from "../api/types";
import { focusDiscussionElement } from "../lib/discussionFocus";
import { useDiscussionSelection } from "../lib/useDiscussionSelection";
import { parsePermalink, permalinkOf } from "../lib/permalink";
import {
  folderByUrl,
  folderForLanding,
  folderTitle,
  landingPage,
  pageHref,
  rootAcceptsWrites,
  rootLabel,
  rootLabelFor,
  entryFor,
  rootEntryOfUrl,
} from "../lib/tree";
import { Breadcrumbs } from "./Breadcrumbs";
import { QueryErrorView, RootUnavailableView } from "./ErrorView";
import { NotFoundView } from "./NotFound";
import { PageEditAction } from "./PageActions";
import { Prose } from "./Prose";
import { Toc } from "./Toc";
import { NewPageLink } from "./NewPageFlow";
import { DiscussionPanel, type PassageRequest } from "./DiscussionPanel";
import { uniqueDiscussionItems } from "./DiscussionRead";

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
    return <PageError error={page.error} root={rootEntryOfUrl(tree.data?.roots ?? [], pathname)?.root} />;
  }
  // A landing page renders AS its folder (the index content replaces the generated listing); the effect canonicalizes the URL.
  if (landingEntry?.folder.url) return <FolderLanding url={landingEntry.folder.url} />;
  // The by-path response IS the page's PageResponse (frontmatter included) — hand it to the Rail
  // directly so it reads already-loaded metadata with no redundant /api/v1/pages/:id fetch.
  return <PageContent key={JSON.stringify([page.data.root, page.data.id])} id={page.data.id} root={page.data.root} page={page.data} />;
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
    if (owner && !owner.available) return <><RootUnavailableView root={owner.root} label={rootLabel(owner)} /><DiscussionEscape root={owner.root} /></>;
    return <><NotFoundView /><DiscussionEscape root={owner?.root} /></>;
  }
  // A root that is not serving has an EMPTY subtree on the wire (the server must never ship its stale carried
  // listing), so rendering the folder anyway would draw an empty directory over an outage - "your docs are gone"
  // instead of "this disk is not mounted". The pages under it 503 through their own requests; the folder view has
  // no request to 503, which is exactly why the flag has to be read here.
  if (!resolved.available) return <><RootUnavailableView root={resolved.root} label={rootLabel(resolved)} /><DiscussionEscape root={resolved.root} /></>;

  // The landing renders AT the folder URL — its one canonical home (the index/README's own bare
  // page URL redirects here; see DocsPage). With an index/README the authored content renders as the
  // WHOLE landing (prose + rail), REPLACING the generated child listing — the children stay reachable
  // through the sidebar tree. With no index, it's a purely-generated listing — no rail, but the rail
  // column stays reserved so the content width matches a page (see FolderListing).
  const landing = landingPage(resolved.folder);
  return landing ? <PageContent key={JSON.stringify([resolved.root, landing.id])} id={landing.id} root={resolved.root} /> :
    <FolderListing root={resolved.root} folder={resolved.folder} />;
}

/**
 * The purely-generated directory view (no index/README): `_folder.yaml` title (else name) as
 * heading, then the generated listing. `data-pb-folder` marks this rail-less generated view.
 *
 * Generated listings use the available content area; authored landing pages retain the reading layout.
 */
function FolderListing({ root, folder }: { root: string; folder: TreeFolder }) {
  const tree = useQuery(treeQuery);
  const entry = tree.data ? entryFor(tree.data.roots, root) : null;
  const title = folderTitle(folder) || (entry ? rootLabel(entry) : root);
  const readOnlyId = useId();
  const canCreate = rootAcceptsWrites(tree.data?.roots, root);
  const folderCount = folder.children.filter((child) => child.type === "folder").length;
  const pageCount = folder.children.filter((child) => child.type === "page").length;
  const diagramCount = folder.children.filter((child) => child.type === "diagram").length;
  const counts = [
    `${folderCount} ${folderCount === 1 ? "folder" : "folders"}`,
    `${pageCount} ${pageCount === 1 ? "page" : "pages"}`,
    ...(diagramCount ? [`${diagramCount} ${diagramCount === 1 ? "diagram" : "diagrams"}`] : []),
  ];
  useEffect(() => {
    document.title = `${title} · Plainbase`;
  }, [title]);

  return (
    <div className="pb-folder" data-pb-folder>
      <div className="min-w-0">
        <div>
          <Breadcrumbs root={root} path={folder.path} title={title} />
          <header className="pb-folder-header">
            <div className="min-w-0">
              <h1 className="text-3xl font-bold text-ink">{title}</h1>
              <p className="pb-folder-counts" data-pb-folder-counts>{counts.join(" · ")}</p>
            </div>
            {canCreate ? (
              <NewPageLink className="pb-folder-create" root={root} folder={folder.path}>New page here</NewPageLink>
            ) : (
              <>
                <button className="pb-folder-create" disabled title="This space is read-only." aria-describedby={readOnlyId}>
                  New page here
                </button>
                <span id={readOnlyId} className="sr-only">This space is read-only.</span>
              </>
            )}
          </header>
          <FolderListingGroups root={root} folder={folder} />
        </div>
      </div>
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
  const diagrams = folder.children.filter((c): c is TreeDiagram => c.type === "diagram");

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
                  <span className="pt" title={child.title}>{child.title}</span>
                  <RowChevron />
                </a>
              ))}
            </div>
          </section>
        )}
        {diagrams.length > 0 && (
          <section className="pb-listing-group">
            <div className="pb-listing-label">Diagrams</div>
            <div className="pb-page-grid">
              {diagrams.map((child) => (
                <a key={child.path} href={child.url} data-pb-folder-child="diagram" className="pb-page-row">
                  <span className="pt" title={child.title}>{child.title}</span>
                  <RowChevron />
                </a>
              ))}
            </div>
          </section>
        )}
      </div>
  );
}

/** Page counts are direct; diagram counts include descendants. Collision losers remain inert. */
function FolderCard({ folder }: { folder: TreeFolder }) {
  const name = folderTitle(folder);
  const diagramCount = folderDiagramCount(folder);
  const labels = [
    ...(folder.page_count > 0 || diagramCount === 0 ? [folder.page_count === 1 ? "1 page" : `${folder.page_count} pages`] : []),
    ...(diagramCount > 0 ? [diagramCount === 1 ? "1 diagram" : `${diagramCount} diagrams`] : []),
  ];
  const body = (
    <>
      <span className="ficon" aria-hidden="true">
        <FolderIcon />
      </span>
      <span className="min-w-0 flex-1">
        <span className="fn" title={name}>{name}</span>
        <span className="mt-1 flex min-w-0 items-baseline gap-1 text-xs text-faint">
          <span className="fc">{labels.join(" · ")}</span><span aria-hidden="true">·</span>
          <span className="fp" title={`${folder.path}/`}>{folder.path}/</span>
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

function folderDiagramCount(folder: TreeFolder): number {
  return folder.children.reduce((count, child) => {
    if (child.type === "diagram") return count + 1;
    return child.type === "folder" ? count + folderDiagramCount(child) : count;
  }, 0);
}

function RowChevron() {
  return (
    <svg data-pb-row-chevron aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6">
      <path d="m9 5 7 7-7 7" />
    </svg>
  );
}

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
  if (page.isError) return <PermalinkError error={page.error} id={id} root={root} />;
  // The permalink response is the page's PageResponse — hand it to the Rail, no redundant fetch. Still the
  // PARSED root, never the response's: the client acts on the address the reader used. Re-pin the html leg
  // to the root the metadata read NAMED and this view silently resolves an ambiguity the server refuses to -
  // once the id is duplicated, a fresh load of the same bare `/p/{id}` answers 300 while the pinned render
  // shows a page, which is the click-vs-reload split the structural gate exists to close.
  return <PageContent key={JSON.stringify([root, page.data.id])} id={page.data.id} root={root} page={page.data} />;
}

/**
 * The permalink route's error surface: the shared one, plus the single thing only THIS caller can supply. A
 * bare `/p/{id}` whose id lives in more than one root reads 409 `ambiguous_page_id`, and that message ENDS
 * "retry against one of the candidate roots below" - so rendering the message alone points the remedy at a
 * list that does not exist. The envelope carries the candidate roots and the address carries the id, so the
 * links are built here, from `permalinkOf` (the same emitter mirror `pageHref` uses - no new URL semantics).
 * NOT the candidates' own `url`s: those are the API retry targets, and would send a reader to JSON.
 */
function PermalinkError({ error, id, root }: { error: Error; id: string; root: string | null }) {
  const tree = useQuery(treeQuery);
  const candidates = error instanceof ApiError ? error.candidates : [];
  if (candidates.length === 0) return <PageError error={error} root={root} />;
  return (
    <QueryErrorView error={error}>
      <ul className="mt-4 space-y-1" data-pb-candidates>
        {candidates.map((candidate) => (
          <li key={candidate.root}>
            <a href={permalinkOf(candidate.root, id)} className="font-medium text-link hover:text-link-hover hover:underline">
              {rootLabelFor(tree.data?.roots, candidate.root)}
            </a>
          </li>
        ))}
      </ul>
      <DiscussionEscape root={root} />
    </QueryErrorView>
  );
}

/**
 * Breadcrumbs + server HTML + doc footer in the main column, with a metadata Rail + TOC in
 * the right rail. HTML gates the reading column; after success the rail and discussion workspace
 * survive same-page source failures. The Rail/footer read the page's frontmatter. Callers that already hold the page's
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
  const [passage, setPassage] = useState<{ workspace: string; detail: DiscussionDetail } | null>(null);
  const [discussionOpen, setDiscussionOpen] = useState(true);
  const discussionBodyId = `pb-page-discussions-${useId()}`;
  const [passageRequest, setPassageRequest] = useState<PassageRequest | null>(null);
  const [pageRequest, setPageRequest] = useState<{ nonce: number; id: string; root: string | null } | null>(null);
  const [selectionHelp, setSelectionHelp] = useState<string | null>(null);
  const [postingFor, setPostingFor] = useState<{ workspace: string; owner: string } | null>(null);
  const [actionFor, setActionFor] = useState<{ workspace: string; owner: string } | null>(null);
  const discussionTrigger = useRef<HTMLButtonElement>(null);
  const articleWrapper = useRef<HTMLDivElement>(null);
  const requestNumber = useRef(0);
  const reloading = useRef<string | null>(null);
  const html = useQuery(pageHtmlQuery(id, root));
  const lastSource = useRef<{ id: string; root: string | null; hash: string; data: PageHtmlResponse } | null>(null);
  if (html.isSuccess) lastSource.current = { id, root, hash: html.data.content_hash, data: html.data };
  const sourceHash = lastSource.current?.id === id && lastSource.current.root === root ? lastSource.current.hash : null;
  const railSource = sourceHash !== null ? lastSource.current!.data : null;
  const displayedRoot = railSource?.root ?? root;
  const workspaceId = JSON.stringify([displayedRoot, id]);
  const [presentationWorkspace, setPresentationWorkspace] = useState(workspaceId);
  useDiscussionHighlight(articleWrapper, { root: displayedRoot, id, path: html.data?.path ?? "", hash: html.data?.content_hash ?? null,
    html: html.data?.html ?? "", ready: discussionOpen && html.isSuccess && !html.isFetching },
  passage?.workspace === workspaceId ? passage.detail : null);
  // A bare permalink keeps its unqualified query keys even when fresh HTML resolves another root.
  // Reset before rendering the new keyed panel so old requests cannot activate it or steal focus.
  if (presentationWorkspace !== workspaceId) {
    setPresentationWorkspace(workspaceId);
    setDiscussionOpen(true); setSelectionHelp(null); setPassageRequest(null); setPageRequest(null);
    setPostingFor(null); setActionFor(null);
  }
  const { capture, captureIfSelected, reset: resetSelection } = useDiscussionSelection(
    articleWrapper, workspaceId, html.data?.content_hash ?? null, html.isSuccess,
  );
  const queryClient = useQueryClient();
  // Fetch by id only when the caller didn't already resolve the page (folder-landing path).
  const fetched = useQuery({ ...pageQuery(id, root), enabled: seeded === undefined });
  const page = seeded ?? fetched.data;
  // The page names its own root; the TREE is what says whether that root takes writes. Read-only, down, and
  // not-yet-known roots get no Edit affordance - the same call Shell makes for "New", and for the same
  // reason: the alternative is an editor session that can only end in a 403 (or a 503) at save.
  const tree = useQuery(treeQuery);
  const editable = rootAcceptsWrites(tree.data?.roots, html.data?.root ?? null);
  const rootEntry = tree.data?.roots.find((entry) => entry.root === displayedRoot);
  const knownUnsupported = !!rootEntry && (!rootEntry.available || !rootEntry.editable);
  // Observe an existing page-list answer without fetching solely to decide whether to offer creation.
  const discussions = useInfiniteQuery({ ...pageDiscussionsQuery(displayedRoot ?? "", id), enabled: false });
  const canStartDiscussion = !knownUnsupported && discussions.data?.pages[0]?.discussions_available !== false;
  const discussionCount = discussions.data?.pages[0]?.discussions_available && !discussions.isError
    ? uniqueDiscussionItems(discussions.data.pages).length : null;
  const moreDiscussions = discussions.data?.pages.at(-1)?.next != null;

  const title = html.data?.title;
  useEffect(() => {
    if (title) document.title = `${title} · Plainbase`;
  }, [title]);

  useEffect(() => {
    setPassageRequest(null);
  }, [html.data?.content_hash]);

  function startDiscussion(requireSelection = false) {
    if (posting || activeAction || !html.isSuccess || html.isFetching || !canStartDiscussion) return;
    const captured = requireSelection ? capture() : captureIfSelected(true);
    if (captured === null) {
      discussWholePage();
      return;
    }
    if ("reason" in captured) {
      setSelectionHelp(captured.reason);
      return;
    }
    if (new TextEncoder().encode(captured.selected_text).length > 16_384) {
      setSelectionHelp("That passage is too long. Select less text or discuss the whole page.");
      return;
    }
    setSelectionHelp(null);
    setPassageRequest({ nonce: ++requestNumber.current, root: html.data!.root, pageId: id, anchor: captured as DiscussionQuoteRequestAnchor });
    resetSelection();
  }

  function discussWholePage() {
    if (posting || activeAction || !html.isSuccess || html.isFetching || !canStartDiscussion) return;
    resetSelection();
    setSelectionHelp(null); setPassageRequest(null);
    setPageRequest({ nonce: ++requestNumber.current, id, root: displayedRoot });
  }

  async function reloadPage(): Promise<boolean> {
    if (posting || reloading.current === workspaceId) return false;
    reloading.current = workspaceId;
    const jobs = [
      queryClient.invalidateQueries({ queryKey: pageHtmlKey(id), predicate: (query) => query.queryKey[3] === root }, { throwOnError: true }),
      queryClient.invalidateQueries({ queryKey: pageKey(id), predicate: (query) => query.queryKey[3] === root }, { throwOnError: true }),
      queryClient.invalidateQueries({ queryKey: ["page", "by-path"], predicate: (query) => {
        const cached = query.state.data as PageResponse | undefined;
        return cached?.id === id && (root === null || cached.root === root);
      } }, { throwOnError: true }),
    ];
    try {
      const results = await Promise.allSettled(jobs);
      return results.every((result) => result.status === "fulfilled") && queryClient.getQueryState(pageHtmlQuery(id, root).queryKey)?.status === "success";
    } finally {
      if (reloading.current === workspaceId) reloading.current = null;
    }
  }

  const posting = postingFor?.workspace === workspaceId;
  const activeAction = actionFor?.workspace === workspaceId;
  function focusDiscussionAction() {
    const panel = document.getElementById(discussionBodyId);
    requestAnimationFrame(() => {
      focusDiscussionElement(panel?.querySelector<HTMLElement>("[data-pb-active-action] textarea") ??
        panel?.querySelector<HTMLElement>("[data-pb-action-focus]") ?? panel?.querySelector<HTMLElement>("textarea"));
    });
  }
  const retainedPanel = sourceHash !== null && displayedRoot !== null;
  const frontmatter = page?.frontmatter;
  const rail = <aside className="pb-rail pb-reading-rail" data-pb-rail>
    {railSource && <Toc headings={railSource.headings} />}
    {retainedPanel && <section className="pb-margin-discussions" aria-label="Discussions">
      <div className="pb-discussion-margin-header">
        <h2 className="pb-rail-head">Discussions {discussionCount !== null && <span className="pb-discussion-count"
          aria-label={`${discussionCount}${moreDiscussions ? " or more" : ""} ${discussionCount === 1 ? "discussion" : "discussions"}`}>
          {discussionCount}{moreDiscussions ? "+" : ""}</span>}</h2>
        <button ref={discussionTrigger} type="button" className="pb-discussion-action pb-discussion-quiet" data-pb-discussions-toggle
          aria-label={discussionOpen ? "Hide discussions" : "Show discussions"}
          aria-expanded={discussionOpen} aria-controls={discussionBodyId} onClick={() => {
            setDiscussionOpen((open) => !open);
            if (discussionOpen) discussionTrigger.current?.focus(); else focusDiscussionAction();
          }}>{discussionOpen ? "Hide" : "Show"}</button>
      </div>
      <div id={discussionBodyId} hidden={!discussionOpen}>
        {selectionHelp && canStartDiscussion && <p role="alert" className="pb-discussion-notice mb-4">{selectionHelp}
          <button type="button" className="pb-discussion-action ml-2"
            disabled={posting || activeAction || !html.isSuccess || html.isFetching}
            onClick={discussWholePage}>Discuss the whole page instead</button>
        </p>}
        <DiscussionPanel key={workspaceId} root={displayedRoot} pageId={id} active={discussionOpen}
          onPassageChange={(detail) => setPassage(detail ? { workspace: workspaceId, detail } : null)}
          onStart={() => startDiscussion()}
          startDisabled={posting || activeAction || !html.isSuccess || html.isFetching || !canStartDiscussion}
          sourceHash={sourceHash} sourceReady={html.isSuccess} sourceBusy={html.isFetching}
          pageRequestNonce={pageRequest?.id === id && pageRequest.root === displayedRoot ? pageRequest.nonce : 0}
          request={passageRequest?.root === displayedRoot && passageRequest.pageId === id && passageRequest.anchor.content_hash === html.data?.content_hash ? passageRequest : null}
          onReselect={() => startDiscussion(true)} onReload={reloadPage}
          onPostingChange={(busy, owner) => setPostingFor((current) => busy ? { workspace: workspaceId, owner } : current?.owner === owner ? null : current)}
          onActionChange={(active, owner) => setActionFor((current) => active ? { workspace: workspaceId, owner } : current?.owner === owner ? null : current)}
          source={{ root: displayedRoot, pageId: id, hash: html.data?.content_hash ?? null,
            ready: html.isSuccess && html.data?.id === id && html.data?.root === displayedRoot, busy: html.isFetching,
            capture: () => html.isFetching ? { reason: "Wait for the page to finish loading before selecting a passage." } : capture(true),
            resetSelection, reload: reloadPage }}
        />
      </div>
    </section>}
  </aside>;

  return (
    <div className="pb-reading-layout">
      <div className="min-w-0">
        <div className="pb-reading-column">
          {html.isPending ? <PagePending /> : html.isError ? <>
            {railSource && <DocRail frontmatter={frontmatter} path={railSource.path} />}
            <PageError error={html.error} root={root} />
          </> : <>
            <PageEditAction url={page?.url ?? null} editable={editable} />
            <Breadcrumbs root={html.data.root} path={html.data.path} title={html.data.title} />
            <div ref={articleWrapper} data-pb-page-article><Prose html={html.data.html} title={html.data.title}
              reading={{ root: html.data.root, path: html.data.path, url: page?.url ?? null, editable }}
              metadata={<DocRail frontmatter={frontmatter} path={html.data.path} editUrl={editable ? page?.url : null} />} /></div>
            <DocFooter
              frontmatter={frontmatter}
              url={page?.url ?? null}
              hasHistory={(page?.commit ?? null) !== null}
            />
          </>}
        </div>
      </div>
      {rail}
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

/** Properties stay outside the rendered source so selection quotes contain only authored text. */
function DocRail({ frontmatter, path, editUrl }: { frontmatter?: Record<string, unknown>; path: string; editUrl?: string | null }) {
  const splat = byPathKeyForUrl(editUrl ?? null);
  const owner = asString(frontmatter?.owner);
  const status = asString(frontmatter?.status);
  const tags = asTags(frontmatter?.tags);
  const updated = asString(frontmatter?.updated);
  const review = asString(frontmatter?.review);

  return (
    <div className="pb-property-strip" data-pb-rail-meta data-pb-selection-chrome role="group" aria-label="Page properties">
      <div className="pb-property-items">
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
        {splat && !status && <Link className="pb-property-add" to="/$" params={{ _splat: splat }} search={{ mode: "edit", property: "status" }}>+ Status</Link>}
        {splat && !owner && <Link className="pb-property-add" to="/$" params={{ _splat: splat }} search={{ mode: "edit", property: "owner" }}>+ Owner</Link>}
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
  const [copyState, setCopyState] = useState("");
  return <>
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
    <button type="button" className="pb-copy-path" aria-label="Copy file path" title="Copy file path" onClick={() => {
      void navigator.clipboard?.writeText(path).then(() => setCopyState("Path copied"), () => setCopyState("Could not copy path"));
      if (!navigator.clipboard) setCopyState("Could not copy path");
    }}><svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2" /><path d="M16 8V4H4v12h4" /></svg></button>
    <span className="sr-only" role="status">{copyState}</span>
  </>;
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
 * The doc footer below `<Prose>` (a sibling, never inside it): History plus a mono "Last updated {date} by
 * {owner}" line sourced from frontmatter. Editing lives in the shell header. A collision loser
 * (no canonical url) gets no History link (it has no root-content address). The History link gates on
 * `hasHistory` (W7/MF-1: `PageResponse.commit != null` — git-on with ≥1 commit — a ZERO-extra-fetch signal;
 * NoOp git always yields null so git-off never false-positives, and a zero-commit page correctly shows none).
 * History remains readable on read-only roots.
 */
function DocFooter({
  frontmatter,
  url,
  hasHistory,
}: {
  frontmatter?: Record<string, unknown>;
  url: string | null;
  hasHistory: boolean;
}) {
  const updated = asString(frontmatter?.updated);
  const owner = asString(frontmatter?.owner);
  const splat = byPathKeyForUrl(url);
  if (!(splat && hasHistory) && !updated) return null;
  return (
    <div className="pb-docfoot" data-pb-docfoot>
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

function PageError({ error, root }: { error: Error; root?: string | null }) {
  if (error instanceof ApiError && (error.isNotFound || error.status === 400)) return <><NotFoundView /><DiscussionEscape root={root} /></>;
  // Everything else - including the outage arriving the other way (a 503 on the page request rather than the tree's
  // flag) - is the shared query-error surface's call, not this one's.
  return <><QueryErrorView error={error} /><DiscussionEscape root={root} /></>;
}

function DiscussionEscape({ root }: { root?: string | null }) {
  return <p className="mt-4 text-center text-sm">{root ?
    <Link to="/discussions/$root" params={{ root }} className="text-link hover:underline">Discussions in {root}</Link> :
    <Link to="/discussions" className="text-link hover:underline">Browse discussions</Link>}</p>;
}
