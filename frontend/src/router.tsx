import type { QueryClient } from "@tanstack/react-query";
import { useQuery } from "@tanstack/react-query";
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  notFound,
  redirect,
  useRouterState,
  type RouterHistory,
} from "@tanstack/react-router";
import { Admin } from "./components/Admin";
import { EditorPage, NewPage } from "./components/EditorPage";
import { ErrorView } from "./components/ErrorView";
import { History } from "./components/History";
import { NotFoundView } from "./components/NotFound";
import { DocsPage, FolderLanding, PermalinkPage } from "./components/PageView";
import { ReviewDetail } from "./components/ReviewDetail";
import { ReviewQueue } from "./components/ReviewQueue";
import { Shell } from "./components/Shell";
import { treeQuery } from "./api/queries";
import { primaryEntry } from "./lib/tree";

/**
 * Route table (chunk 7 + the chunk-6 amendment):
 *
 *   /          → redirect to the primary root's server-issued url
 *   /$         → canonical root-qualified page route; the splat is the by-path key. `?mode=edit` mounts the
 *                editor, `?mode=history` is the W7 history seam; absent = the clean read view.
 *   /new       → new-page creation (no path exists pre-create — the server mints it)
 *   /p/$       → permalink route, BOTH forms: the rooted `/p/{root}/{id}` the server emits
 *                and the bare `/p/{id}` it still serves. Winners never reach it (the server
 *                302s a permalink → canonical), but a collision loser's permalink serves the
 *                shell (200), so the SPA parses the splat (lib/permalink.ts), fetches by
 *                (root, id) and renders in place.
 *   anything else → 404 view (the server's static fallback returns the shell)
 */
export interface RouterContext {
  queryClient: QueryClient;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Shell,
  notFoundComponent: NotFoundView,
});

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  beforeLoad: async ({ context }) => {
    const tree = await context.queryClient.ensureQueryData(treeQuery);
    const primary = primaryEntry(tree.roots);
    if (!primary?.tree.url) throw new Error("The primary root has no server-issued URL");
    throw redirect({ href: primary.tree.url, replace: true });
  },
});

/** The `/$` query mode: `edit` (the editor), `history` (W7 seam), or absent (the read view). */
interface DocsSearch {
  mode?: "edit" | "history";
}

const splatRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/$",
  component: DocsSplat,
  // A bogus `?mode=foo` coerces to undefined → the read view; never an undefined/blank state (D-1).
  validateSearch: (search: Record<string, unknown>): DocsSearch => {
    const mode = search.mode;
    return mode === "edit" || mode === "history" ? { mode } : {};
  },
});

/**
 * The server's decode-once rule (PB-LINK-1) REJECTS encoded slashes — `%2F` is never a
 * path separator, so a URL carrying one names nothing. `useParams()` has already decoded
 * the splat, erasing the distinction (`foo%2Fbar` → `foo/bar`), so the raw router
 * pathname is checked before the splat is trusted; offenders get the 404 view, never a
 * fetch. The client must not re-derive URL semantics the server forbids (§A4).
 */
function useHasEncodedSlash(): boolean {
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  return /%2f/i.test(pathname);
}

/**
 * The `/$` dispatcher (D-1): a thin switch on `?mode=` delegating to sub-components that each own
 * their own `useQuery` (component-level data-fetching — no route loader, so the lifecycle separation
 * holds without separate routes). The editor lives UNDER the read route's canonical-redirect resolution
 * (which preserves the query string), so `?mode=edit` is rename-stable.
 */
function DocsSplat() {
  const { _splat } = splatRoute.useParams();
  const { mode } = splatRoute.useSearch();
  const encodedSlash = useHasEncodedSlash();
  const tree = useQuery(treeQuery);
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  if (encodedSlash) return <NotFoundView />;
  const path = _splat ?? "";
  const normalizedPathname = pathname.endsWith("/") && pathname !== "/" ? pathname.slice(0, -1) : pathname;
  const landing = tree.data?.roots.find((entry) => entry.tree.url === normalizedPathname);
  if (landing?.tree.url) return <FolderLanding url={landing.tree.url} />;
  if (mode === "edit" && path) return <EditorPage path={path} />;
  if (mode === "history" && path) return <History path={path} />;
  return <DocsPage path={path} />;
}

// W7 landed: the per-page history surface (`?mode=history`) is the real `<History>` component
// (./components/History) — the commit list + two-commit unified diff, consuming the W5 read API. The
// dispatcher branch + the `validateSearch` enum were pre-wired by W6, so W7 added only the component.

/** The `/new` search: `?root=` names the document root the create lands in. */
interface NewSearch {
  root?: string;
}

const newRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/new",
  component: NewSplat,
  beforeLoad: ({ location }) => rejectTrailingSlash(location.pathname),
  // A non-string `root` coerces to undefined. An unknown name is not decided here: the server owns the registry
  // and answers 400 `invalid_root`, so the client never guesses.
  validateSearch: (search: Record<string, unknown>): NewSearch =>
    typeof search.root === "string" && search.root !== "" ? { root: search.root } : {},
});

/** Threads the `?root=` search param into the form (the [DocsSplat] shape) — the root the bytes will land in. */
function NewSplat() {
  const { root } = newRoute.useSearch();
  return <NewPage root={root} />;
}

const adminRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/admin",
  component: Admin,
  beforeLoad: ({ location }) => rejectTrailingSlash(location.pathname),
});

const reviewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/review",
  component: ReviewQueue,
  beforeLoad: ({ location }) => rejectTrailingSlash(location.pathname),
});

const reviewDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/review/$id",
  component: ReviewDetailSplat,
  beforeLoad: ({ location }) => rejectTrailingSlash(location.pathname),
});

function ReviewDetailSplat() {
  const { id } = reviewDetailRoute.useParams();
  return <ReviewDetail id={id} />;
}

/** The server deliberately rejects trailing-slash spellings of exact SPA routes with a 404 shell. */
function rejectTrailingSlash(pathname: string): void {
  if (pathname.endsWith("/")) throw notFound();
}

const permalinkRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/p/$",
  component: PermalinkSplat,
});

function PermalinkSplat() {
  const { _splat } = permalinkRoute.useParams();
  const encodedSlash = useHasEncodedSlash();
  // Ids are ASCII/hyphens — an encoded slash here would have 400'd on the server route.
  if (encodedSlash) return <NotFoundView />;
  return <PermalinkPage splat={_splat ?? ""} />;
}

const routeTree = rootRoute.addChildren([
  indexRoute,
  splatRoute,
  newRoute,
  adminRoute,
  reviewRoute,
  reviewDetailRoute,
  permalinkRoute,
]);

/** [history] is injectable for tests (memory history); the app default is browser history. */
export function createAppRouter(queryClient: QueryClient, history?: RouterHistory) {
  return createRouter({
    routeTree,
    history,
    context: { queryClient },
    defaultPreload: "intent",
    // Per-match branded boundary: a route render crash keeps the Shell mounted and auto-resets
    // on the next navigation (vs the global backstop, which unmounts everything unbranded).
    defaultErrorComponent: ErrorView,
    scrollRestoration: true,
  });
}

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
