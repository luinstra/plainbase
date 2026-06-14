import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, pageQuery, treeQuery } from "../api/queries";
import type { PageHtmlResponse, PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

/**
 * Router-level flows that the fixture-backed smoke suite cannot reach:
 *  - alias by-path resolution → replaceState to the canonical `url` from the API response
 *  - a collision-loser permalink (`/p/{id}`, served as shell by chunk 6) rendering by id
 *    WITHOUT a canonical path to replace to
 * Queries are primed in the cache; no network.
 */

const WINNER_ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
const LOSER_ID = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99";

function citation(id: string) {
  return { page_id: id, heading_id: null, path: "x.md", content_hash: "h", commit: null, uri: `plainbase://${id}@h` };
}

function pageResponse(id: string, url: string | null, title: string): PageResponse {
  return {
    id,
    path: "guides/deploy-guide.md",
    slug: "deploy-guide",
    url,
    title,
    markdown: "# x",
    frontmatter: {},
    content_hash: "h",
    id_materialized: true,
    commit: null,
    citation: citation(id),
  };
}

function htmlResponse(id: string, url: string | null, title: string): PageHtmlResponse {
  return {
    id,
    path: "guides/deploy-guide.md",
    slug: "deploy-guide",
    url,
    title,
    html: `<h1 id="t">${title}</h1>`,
    content_hash: "h",
    commit: null,
    headings: [{ id: "t", level: 1, text: title }],
    citation: citation(id),
  };
}

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };

// A root-level README child — the fixture-backed smoke suite can't isolate readme-only at
// the root (demo-docs carries an index.md too), so the readme branch is mocked here.
const rootReadmeTree: TreeResponse = {
  root: {
    type: "folder",
    name: "",
    title: null,
    description: null,
    path: "",
    url: "/docs",
    page_count: 1,
    children: [{ type: "page", id: WINNER_ID, title: "Docs Home", slug: "readme", path: "README.md", url: "/docs/readme", status: "active", updated: null }],
  },
};

function renderAt(initialPath: string, prime: (qc: QueryClient) => void) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  prime(queryClient);
  const history = createMemoryHistory({ initialEntries: [initialPath] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { history, view };
}

describe("routing flows", () => {
  it("redirects / to /docs and renders the root folder landing", async () => {
    const { history, view } = renderAt("/", () => {});

    await waitFor(() => expect(history.location.pathname).toBe("/docs"));
    await waitFor(() => expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-folder] h1")?.textContent).toBe("docs"); // root fallback heading
  });

  it("renders a root README child's content at bare /docs — README-preference applies to the root node too", async () => {
    const { history, view } = renderAt("/docs", (qc) => {
      qc.setQueryData(treeQuery.queryKey, rootReadmeTree);
      qc.setQueryData(pageHtmlQuery(WINNER_ID).queryKey, htmlResponse(WINNER_ID, "/docs/readme", "Docs Home"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Docs Home"));
    expect(history.location.pathname).toBe("/docs"); // rendered AT the root url, no redirect
    expect(view.container.querySelector("[data-pb-folder]")).toBeNull();
  });

  it("replaceStates an alias path to the canonical url from the by-path response", async () => {
    const canonical = "/docs/guides/deploy-guide";
    const { history } = renderAt("/docs/old/deployment", (qc) => {
      qc.setQueryData(pageByPathQuery("old/deployment").queryKey, pageResponse(WINNER_ID, canonical, "Deploy Guide"));
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(WINNER_ID, canonical, "Deploy Guide"));
      qc.setQueryData(pageHtmlQuery(WINNER_ID).queryKey, htmlResponse(WINNER_ID, canonical, "Deploy Guide"));
    });

    await waitFor(() => expect(history.location.pathname).toBe(canonical));
  });

  it("seeds the canonical by-path cache from the alias response — no refetch after the replace", async () => {
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 500 }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      // Only the ALIAS key is primed; the canonical render must come from the seeded cache.
      const canonical = "/docs/guides/deploy-guide";
      const { history, view } = renderAt("/docs/old/deployment", (qc) => {
        qc.setQueryData(pageByPathQuery("old/deployment").queryKey, pageResponse(WINNER_ID, canonical, "Deploy Guide"));
        qc.setQueryData(pageHtmlQuery(WINNER_ID).queryKey, htmlResponse(WINNER_ID, canonical, "Deploy Guide"));
      });

      await waitFor(() => expect(history.location.pathname).toBe(canonical));
      await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Deploy Guide"));
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("renders a collision loser at its /p/{id} permalink, fetched by id, no redirect", async () => {
    const { history, view } = renderAt(`/p/${LOSER_ID}`, (qc) => {
      qc.setQueryData(pageQuery(LOSER_ID).queryKey, pageResponse(LOSER_ID, null, "Shadowed Page"));
      qc.setQueryData(pageHtmlQuery(LOSER_ID).queryKey, htmlResponse(LOSER_ID, null, "Shadowed Page"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Shadowed Page"));
    expect(history.location.pathname).toBe(`/p/${LOSER_ID}`);
  });

  it("ignores a stale trailing slug segment on a permalink, like the server route", async () => {
    const { view } = renderAt(`/p/${LOSER_ID}/stale-slug`, (qc) => {
      qc.setQueryData(pageQuery(LOSER_ID).queryKey, pageResponse(LOSER_ID, null, "Shadowed Page"));
      qc.setQueryData(pageHtmlQuery(LOSER_ID).queryKey, htmlResponse(LOSER_ID, null, "Shadowed Page"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Shadowed Page"));
  });

  it("does not snap the URL back when navigating away from a resolved page", async () => {
    // Regression: during a click-navigation the OUTGOING DocsPage briefly observes the
    // incoming pathname; its canonical-correction must not replace the URL back.
    const canonicalA = "/docs/guides/deploy-guide";
    const { history } = renderAt(canonicalA, (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(WINNER_ID, canonicalA, "Deploy Guide"));
      qc.setQueryData(pageHtmlQuery(WINNER_ID).queryKey, htmlResponse(WINNER_ID, canonicalA, "Deploy Guide"));
      qc.setQueryData(pageByPathQuery("welcome").queryKey, pageResponse(LOSER_ID, "/docs/welcome", "Welcome"));
      qc.setQueryData(pageHtmlQuery(LOSER_ID).queryKey, htmlResponse(LOSER_ID, "/docs/welcome", "Welcome"));
    });

    await waitFor(() => expect(history.location.pathname).toBe(canonicalA));
    history.push("/docs/welcome");
    await waitFor(() => expect(history.location.pathname).toBe("/docs/welcome"));
    // Give any stray replace a tick to fire, then confirm the URL held.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(history.location.pathname).toBe("/docs/welcome");
  });

  it("404s an encoded slash in a /docs path without fetching — PB-LINK-1 rejects %2F as a separator", async () => {
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 500 }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      // The DECODED form exists as a page; the raw URL still names nothing on the server.
      const { history, view } = renderAt("/docs/guides%2Fdeploy-guide", (qc) => {
        qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(WINNER_ID, null, "Deploy Guide"));
        qc.setQueryData(pageHtmlQuery(WINNER_ID).queryKey, htmlResponse(WINNER_ID, null, "Deploy Guide"));
      });
      await waitFor(() => expect(view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
      expect(view.container.querySelector(".pb-prose")).toBeNull();
      expect(fetchSpy).not.toHaveBeenCalled();

      // Client-side navigation to such a URL (lowercase variant) must 404 the same way.
      history.push("/docs/welcome%2fintro");
      await waitFor(() => expect(view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("404s an encoded slash in a /p permalink — the server route would have 400'd it", async () => {
    const { view } = renderAt(`/p/${LOSER_ID}%2Fstale-slug`, (qc) => {
      qc.setQueryData(pageQuery(LOSER_ID).queryKey, pageResponse(LOSER_ID, null, "Shadowed Page"));
      qc.setQueryData(pageHtmlQuery(LOSER_ID).queryKey, htmlResponse(LOSER_ID, null, "Shadowed Page"));
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
    expect(view.container.querySelector(".pb-prose")).toBeNull();
  });

  it("shows the 404 view when the API rejects the permalink id (400 invalid_page_id)", async () => {
    const envelope = { error: { code: "invalid_page_id", message: "Not a canonical-shape UUID: 'not-a-uuid'" } };
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify(envelope), { status: 400, headers: { "content-type": "application/json" } })),
    );
    try {
      const { view } = renderAt("/p/not-a-uuid", () => {});
      await waitFor(() => expect(view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
