import type { QueryClient } from "@tanstack/react-query";
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  redirect,
  useRouterState,
  type RouterHistory,
} from "@tanstack/react-router";
import { NotFoundView } from "./components/NotFound";
import { DocsPage, FolderLanding, PermalinkPage } from "./components/PageView";
import { Shell } from "./components/Shell";

/**
 * Route table (chunk 7 + the chunk-6 amendment):
 *
 *   /          → redirect to /docs
 *   /docs      → the ROOT folder landing (the root node's `url` is /docs): root
 *                index/readme child if present, else the top-level listing
 *   /docs/$    → canonical page route; the splat is the by-path key
 *   /p/$       → loser-permalink route. Winners never reach it (the server 302s
 *                /p/{id} → canonical), but a collision loser's permalink serves the
 *                shell (200), so the SPA fetches by id and renders in place.
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
  beforeLoad: () => {
    throw redirect({ to: "/docs", replace: true });
  },
});

const docsIndexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/docs",
  component: FolderLanding,
});

const docsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/docs/$",
  component: DocsSplat,
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

function DocsSplat() {
  const { _splat } = docsRoute.useParams();
  const encodedSlash = useHasEncodedSlash();
  if (encodedSlash) return <NotFoundView />;
  const path = _splat ?? "";
  // An empty splat ("/docs/") is the root landing too; the trailing-slash pathname would
  // never match the root's verbatim `/docs` url, so it is passed explicitly.
  if (!path) return <FolderLanding url="/docs" />;
  return <DocsPage path={path} />;
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

const routeTree = rootRoute.addChildren([indexRoute, docsIndexRoute, docsRoute, permalinkRoute]);

/** [history] is injectable for tests (memory history); the app default is browser history. */
export function createAppRouter(queryClient: QueryClient, history?: RouterHistory) {
  return createRouter({
    routeTree,
    history,
    context: { queryClient },
    defaultPreload: "intent",
    scrollRestoration: true,
  });
}

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
