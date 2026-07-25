import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { pageByPathQuery, sessionQuery, treeQuery } from "../api/queries";
import type { PageResponse, TreeResponse } from "../api/types";
import { QueryErrorView } from "../components/ErrorView";
import { createAppRouter } from "../router";

/**
 * The outage surfaces (ADR-0011 D5, client half). A 503 `root_unavailable` is NOT a crash: the pages exist, a disk
 * is not mounted, and recovery is an operator's. Every surface that renders a failed read therefore has to tell the
 * two apart — and three of them didn't, because the check was hand-rolled per call site. These tests pin the rule at
 * the shared component AND at each route that reaches it, so the next panel cannot quietly re-introduce
 * "Something went wrong" over an unmounted disk.
 */

const OUTAGE = { error: { code: "root_unavailable", message: 'The "handbook" root is not serving right now.' } };
const CRASH = { error: { code: "internal_error", message: "boom" } };
const MISSING = { error: { code: "not_found", message: "no page at that path" } };

const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
const PATH = "main/guides/deploy-guide";
const PAGE_URL = "/docs/main/guides/deploy-guide";
const HASH = "sha256:5df17ea6dababd5ad54c0f365a1a1cbf02f304c48db492b8046f2c0d2341534e";

const emptyTree: TreeResponse = { roots: [{ root: "main", available: true, editable: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs/main", page_count: 0, children: [] } }] };
/** The tree a down root actually ships: LISTED (it is configured) with an EMPTY subtree - so nothing under
 *  `/docs/handbook` can be found by folder lookup, and only the root url space still names it. */
const outageTree: TreeResponse = {
  roots: [
    ...emptyTree.roots,
    { root: "handbook", available: false, editable: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs/handbook", page_count: 0, children: [] } },
  ],
};
const AUTHED = { authenticated: true, username: "admin", csrf_token: "c", auth_mode: "builtin" };

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function pageResponse(): PageResponse {
  return {
    id: ID,
    root: "main",
    path: "guides/deploy-guide.md",
    slug: "deploy-guide",
    url: PAGE_URL,
    title: "Deploy Guide",
    markdown: "# Deploy Guide\n",
    frontmatter: {},
    content_hash: HASH,
    id_materialized: true,
    commit: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    citation: { page_id: ID, heading_id: null, path: "guides/deploy-guide.md", content_hash: HASH, commit: null, uri: `plainbase://${ID}@${HASH}` },
  };
}

/** Every request 503s with the outage envelope — the whole root is down, which is exactly the real condition. */
function stubOutage() {
  vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(OUTAGE, 503)));
}

/** A by-path 404 - the folder-landing fallthrough (ADR-0003), the only way into the resolver under test. The primed
 *  tree/session are fresh (60s staleTime), so nothing else fetches and this stub answers only the page read. */
function stubPageMissing() {
  vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(MISSING, 404)));
}

function renderAt(initialPath: string, prime: (qc: QueryClient) => void = () => {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  queryClient.setQueryData(sessionQuery.queryKey, AUTHED);
  prime(queryClient);
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [initialPath] }));
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

/** The outage surface is up, and the crash surface is NOT — a passing "not null" alone would miss the regression. */
async function expectOutage(view: ReturnType<typeof render>) {
  await waitFor(() => expect(view.container.querySelector("[data-pb-root-unavailable]")).not.toBeNull());
  expect(view.container.querySelector("[data-pb-error]")).toBeNull();
  expect(view.container.textContent).not.toContain("Something went wrong");
}

afterEach(() => vi.unstubAllGlobals());

describe("QueryErrorView: the one query-error surface", () => {
  it("renders a root_unavailable 503 as the outage, carrying the server's message", () => {
    const view = render(<QueryErrorView error={new ApiError(503, "root_unavailable", "the handbook root is not serving")} />);

    expect(view.container.querySelector("[data-pb-root-unavailable]")).not.toBeNull();
    expect(view.container.textContent).toContain("the handbook root is not serving");
    expect(view.container.textContent).not.toContain("Something went wrong");
  });

  it("renders every other failure as the error it is", () => {
    const view = render(<QueryErrorView error={new ApiError(500, "internal_error", "boom")} />);

    expect(view.container.querySelector("[data-pb-error]")).not.toBeNull();
    expect(view.container.querySelector("h1")?.textContent).toBe("Something went wrong");
    expect(view.container.textContent).toContain("boom");
  });

  it("survives a non-Error rejection (no .message to read)", () => {
    const view = render(<QueryErrorView error="offline" />);

    expect(view.container.textContent).toContain("offline");
  });
});

describe("the routes that render a failed read", () => {
  it("the read view shows the outage, not a crash", async () => {
    stubOutage();
    await expectOutage(renderAt(PAGE_URL));
  });

  it("the editor shows the outage, not a crash", async () => {
    stubOutage();
    await expectOutage(renderAt(`${PAGE_URL}?mode=edit`));
  });

  it("the history view shows the outage, not a crash", async () => {
    stubOutage();
    await expectOutage(renderAt(`${PAGE_URL}?mode=history`));
  });

  it("the history view shows the outage when the page resolved but /history 503s (the root went down between them)", async () => {
    // The page is already in cache, so ONLY the /history fetch fails — the inner error branch, which used to file an
    // outage under "Couldn't load the page history".
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => (String(input).includes("/history") ? jsonResponse(OUTAGE, 503) : jsonResponse({}))));

    await expectOutage(renderAt(`${PAGE_URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse())));
  });

  it("still calls a genuine failure a failure", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(CRASH, 500)));

    const view = renderAt(PAGE_URL);

    await waitFor(() => expect(view.container.querySelector("[data-pb-error]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-root-unavailable]")).toBeNull();
  });
});

/**
 * The outage arriving with NO request to answer it: the folder-landing views read the tree's `available` flag. The
 * bare root url always resolved (the synthetic root folder node survives an emptied subtree), so it was the DEEP
 * links that fell through the folder lookup and got answered "page not found" - an outage told as a deletion.
 */
describe("the folder landing under a root that is not serving", () => {
  const under = (path: string) => renderAt(path, (qc) => qc.setQueryData(treeQuery.queryKey, outageTree));

  it("shows the outage for a DEEP url, whose folder the emptied subtree no longer carries", async () => {
    stubPageMissing();

    const view = under("/docs/handbook/guides/onboarding");

    await expectOutage(view);
    expect(view.container.querySelector("[data-pb-not-found]")).toBeNull();
    expect(view.container.textContent).toContain("handbook");
  });

  it("shows the outage for the bare root url too (the behavior the deep-link fix must preserve)", async () => {
    stubPageMissing();

    await expectOutage(under("/docs/handbook"));
  });

  it("still calls a bogus deep url under a SERVING root not-found (an outage is not a 404's excuse)", async () => {
    stubPageMissing();

    const view = renderAt("/docs/main/nope/nope");

    await waitFor(() => expect(view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-root-unavailable]")).toBeNull();
  });
});
