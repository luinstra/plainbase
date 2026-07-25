import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, sessionQuery, treeQuery } from "../api/queries";
import type { PageHtmlResponse, PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

/**
 * "Edit this page" is gated on the ROOT's `editable` bit, which the tree carries (multi-root C5).
 *
 * `plainbase root add` defaults an extra root to `editable = false`, so a read-only root is the DEFAULT
 * CLI-added one - and without the wire bit the SPA cannot tell, so it offered Edit on every page of it and
 * failed at save with a 403 `root_not_editable`. The 403 is still the authority and still the backstop; this
 * is the affordance not lying about it.
 */

const HASH = "sha256:5df17ea6dababd5ad54c0f365a1a1cbf02f304c48db492b8046f2c0d2341534e";
const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";

/** `main` (editable, as every legacy install runs) + `handbook` (read-only, as `root add` produces). */
const tree: TreeResponse = {
  roots: [
    { root: "main", available: true, editable: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs/main", page_count: 0, children: [] } },
    { root: "handbook", available: true, editable: false, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs/handbook", page_count: 0, children: [] } },
  ],
};

function pageResponse(root: string, url: string): PageResponse {
  return {
    id: ID,
    root,
    path: "guides/onboarding.md",
    slug: "onboarding",
    url,
    title: "Onboarding",
    markdown: "# Onboarding\n",
    frontmatter: { updated: "2026-01-01" },
    content_hash: HASH,
    id_materialized: true,
    commit: null,
    citation: { page_id: ID, heading_id: null, path: "guides/onboarding.md", content_hash: HASH, commit: null, uri: `plainbase://${ID}@${HASH}` },
  };
}

function htmlResponse(root: string, url: string): PageHtmlResponse {
  return {
    id: ID,
    root,
    path: "guides/onboarding.md",
    slug: "onboarding",
    url,
    title: "Onboarding",
    html: "<h1>Onboarding</h1>",
    content_hash: HASH,
    commit: null,
    headings: [],
    citation: { page_id: ID, heading_id: null, path: "guides/onboarding.md", content_hash: HASH, commit: null, uri: `plainbase://${ID}@${HASH}` },
  };
}

/** Mounts the canonical `/docs/{root}/...` read view with the page and the tree already resolved. */
function renderPage(root: string) {
  const url = `/docs/${root}/guides/onboarding`;
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, tree);
  queryClient.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  queryClient.setQueryData(pageByPathQuery(`${root}/guides/onboarding`).queryKey, pageResponse(root, url));
  queryClient.setQueryData(pageHtmlQuery(ID, root).queryKey, htmlResponse(root, url));
  vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 200, headers: { "content-type": "application/json" } })));
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [url] }));
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("the Edit affordance", () => {
  it("renders on an EDITABLE root", async () => {
    const { container } = renderPage("main");
    await waitFor(() => expect(container.querySelector("[data-pb-docfoot]")).not.toBeNull());
    expect(container.querySelector("[data-pb-edit-page]")).not.toBeNull();
  });

  it("is ABSENT on a READ-ONLY root - the rest of the footer is untouched", async () => {
    // The failure it replaces: "Edit this page" on every page of a root that answers 403 to every write, so
    // the reader learns the topology only after typing into an editor they were never allowed to save from.
    const { container } = renderPage("handbook");
    await waitFor(() => expect(container.querySelector("[data-pb-docfoot]")).not.toBeNull());
    expect(container.querySelector("[data-pb-edit-page]")).toBeNull();
    // `editable` gates the WRITE affordance only: a read-only root's metadata still renders.
    expect(container.querySelector(".pb-docfoot-updated")?.textContent).toBe("Last updated 2026-01-01");
  });
});
