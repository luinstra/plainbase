import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, treeQuery } from "../api/queries";
import type { PageHtmlResponse, PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

/**
 * `useDeepLinkHighlight` via `Prose`/`PageView` (criteria 6, 9, 10). jsdom has no real
 * layout, so we assert the BEHAVIORAL contract: scrollIntoView is called on the target,
 * the pulse class lands (and is suppressed under reduced motion), and a missing fragment
 * is a silent no-op.
 */

const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";

function citation(id: string) {
  return { page_id: id, heading_id: null, path: "x.md", content_hash: "h", commit: null, uri: `plainbase://${id}@h` };
}
function pageResponse(url: string): PageResponse {
  return {
    id: ID,
    path: "guides/deploy-guide.md",
    slug: "deploy-guide",
    url,
    title: "Deploy Guide",
    markdown: "# x",
    frontmatter: {},
    content_hash: "h",
    id_materialized: true,
    commit: null,
    citation: citation(ID),
  };
}
function htmlResponse(url: string): PageHtmlResponse {
  return {
    id: ID,
    path: "guides/deploy-guide.md",
    slug: "deploy-guide",
    url,
    title: "Deploy Guide",
    html: '<h1 id="deploy-guide">Deploy Guide</h1><h2 id="rollback">Rollback</h2>',
    content_hash: "h",
    commit: null,
    headings: [
      { id: "deploy-guide", level: 1, text: "Deploy Guide" },
      { id: "rollback", level: 2, text: "Rollback" },
    ],
    citation: citation(ID),
  };
}

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };

function renderAt(initialPath: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  const canonical = "/docs/guides/deploy-guide";
  queryClient.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(canonical));
  queryClient.setQueryData(pageHtmlQuery(ID).queryKey, htmlResponse(canonical));
  const history = createMemoryHistory({ initialEntries: [initialPath] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { view, history };
}

function mockMatchMedia(reduced: boolean) {
  window.matchMedia = ((query: string) => ({
    matches: reduced && query.includes("reduce"),
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  })) as typeof window.matchMedia;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("useDeepLinkHighlight (via Prose)", () => {
  it("scrolls to the fragment target on commit and pulses it", async () => {
    mockMatchMedia(false);
    const scrollSpy = vi.fn();
    Element.prototype.scrollIntoView = scrollSpy;
    const { view } = renderAt("/docs/guides/deploy-guide#rollback");

    await waitFor(() => expect(view.container.querySelector("#rollback")).not.toBeNull());
    await waitFor(() => expect(scrollSpy).toHaveBeenCalled());
    const target = view.container.querySelector("#rollback")!;
    expect(target.classList.contains("pb-deeplink-pulse")).toBe(true);
  });

  it("scrolls but does NOT pulse under prefers-reduced-motion", async () => {
    mockMatchMedia(true);
    const scrollSpy = vi.fn();
    Element.prototype.scrollIntoView = scrollSpy;
    const { view } = renderAt("/docs/guides/deploy-guide#rollback");

    await waitFor(() => expect(view.container.querySelector("#rollback")).not.toBeNull());
    await waitFor(() => expect(scrollSpy).toHaveBeenCalled());
    expect(view.container.querySelector("#rollback")!.classList.contains("pb-deeplink-pulse")).toBe(false);
  });

  it("is a silent no-op for a missing fragment (no throw, no scroll, no class)", async () => {
    mockMatchMedia(false);
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const scrollSpy = vi.fn();
    Element.prototype.scrollIntoView = scrollSpy;
    const { view } = renderAt("/docs/guides/deploy-guide#does-not-exist");

    await waitFor(() => expect(view.container.querySelector("#deploy-guide")).not.toBeNull());
    expect(scrollSpy).not.toHaveBeenCalled();
    expect(view.container.querySelector(".pb-deeplink-pulse")).toBeNull();
    expect(errorSpy).not.toHaveBeenCalled();
  });
});
