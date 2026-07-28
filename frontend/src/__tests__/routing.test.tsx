import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, pageQuery, sessionQuery, treeQuery } from "../api/queries";
import type { PageHtmlResponse, PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

/**
 * Router-level flows that the fixture-backed smoke suite cannot reach:
 *  - alias by-path resolution → replaceState to the canonical `url` from the API response
 *  - a collision-loser permalink rendering by id WITHOUT a canonical path to replace to, in BOTH
 *    forms: the rooted `/p/{root}/{id}` the server emits, and the bare `/p/{id}` it still serves
 *    (those bare rows are the regression proof that the bare arm still works, so they do not move)
 * Queries are primed in the cache; no network.
 */

const WINNER_ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
const LOSER_ID = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99";
/** A HAND-BUILT id held by BOTH roots - the shape a copied corpus produces. Deliberately not one of
 *  the e2e fixture's ids: the two id spaces stay disjoint so nobody reads a unit row as proof about
 *  the corpus, or "fixes" the fixture frontmatter to match a unit test. */
const DUP_ID = "0197c2d1-9f3b-7a4e-8d6c-1b5a7e9c3f21";

function citation(id: string) {
  return { page_id: id, heading_id: null, path: "x.md", content_hash: "h", commit: null, uri: `plainbase://${id}@h` };
}

function pageResponse(id: string, url: string | null, title: string, root = "docs"): PageResponse {
  return {
    id,
    root,
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

function htmlResponse(id: string, url: string | null, title: string, root = "docs"): PageHtmlResponse {
  return {
    id,
    root,
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

const emptyTree: TreeResponse = { roots: [{ root: "docs", available: true, editable: true, primary: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } }] };

// A root-level README child — the fixture-backed smoke suite can't isolate readme-only at
// the root (demo-docs carries an index.md too), so the readme branch is mocked here.
const rootReadmeTree: TreeResponse = {
  roots: [
    {
      root: "docs",
      available: true,
      editable: true,
      primary: true,
      tree: {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/docs",
        page_count: 1,
        children: [{ type: "page", id: WINNER_ID, title: "Docs Home", slug: "readme", path: "README.md", url: "/docs/readme", status: "active", updated: null }],
      },
    },
  ],
};

/**
 * One root's tree holding `permalink/hub`, for the cross-root rows below. Both roots get the SAME page
 * id: that is what makes the reads those rows watch cross-root ones.
 *
 * `hub` is deliberately NOT a landing child - its stem is neither `index` nor `readme` - so
 * `folderForLanding` answers null and DocsPage renders `<PageContent>`. Give the folder an index/README
 * child instead and DocsPage diverts to FolderLanding, and the rows would quietly stop testing the
 * by-id read they exist to test.
 */
function hubEntry(root: string, id: string): TreeResponse["roots"][number] {
  const rootUrl = root === "docs" ? "/docs" : `/${root}`;
  return {
    root,
    available: true,
    editable: true,
    primary: root === "docs",
    tree: {
      type: "folder",
      name: "",
      title: null,
      description: null,
      path: "",
      url: rootUrl,
      page_count: 1,
      children: [
        {
          type: "folder",
          name: "permalink",
          title: "Permalink",
          description: null,
          path: "permalink",
            url: `${rootUrl}/permalink`,
          page_count: 1,
          children: [
            { type: "page", id, title: "Permalink Hub", slug: "hub", path: "permalink/hub.md", url: `${rootUrl}/permalink/hub`, status: "active", updated: null },
          ],
        },
      ],
    },
  };
}

const twoRoots: TreeResponse = { roots: [hubEntry("docs", DUP_ID), hubEntry("extra", DUP_ID)] };

const extraPageTree: TreeResponse = {
  roots: [
    {
      root: "extra",
      available: true,
      editable: true,
      primary: false,
      tree: {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/extra",
        page_count: 1,
        children: [
          {
            type: "folder",
            name: "notes",
            title: "Notes",
            description: null,
            path: "notes",
            url: "/extra/notes",
            page_count: 1,
            children: [{ type: "page", id: WINNER_ID, title: "Extra Note", slug: "y", path: "notes/y.md", url: "/extra/notes/y", status: "active", updated: null }],
          },
        ],
      },
    },
    emptyTree.roots[0],
  ],
};

/** An unauthenticated session — the Shell renders no "Review" link, and serves it from cache (no /session fetch). */
const ANON_SESSION = { authenticated: false, username: null, csrf_token: null, auth_mode: "off" };

function renderAt(initialPath: string, prime: (qc: QueryClient) => void) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  queryClient.setQueryData(sessionQuery.queryKey, ANON_SESSION);
  prime(queryClient);
  const history = createMemoryHistory({ initialEntries: [initialPath] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { history, view, queryClient };
}

describe("routing flows", () => {
  it("redirects / to /docs and renders the root folder landing", async () => {
    const { history, view } = renderAt("/", () => {});

    await waitFor(() => expect(history.location.pathname).toBe("/docs"));
    await waitFor(() => expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-folder] h1")?.textContent).toBe("docs"); // root fallback heading
  });

  it("renders a root landing when its bare address carries a stray mode", async () => {
    const primary = renderAt("/docs?mode=edit", () => {});
    await waitFor(() => expect(primary.view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    expect(primary.view.container.querySelector("[data-pb-editor]")).toBeNull();
    expect(primary.view.container.querySelector("[data-pb-not-found]")).toBeNull();

    const extra = renderAt("/extra?mode=history", (qc) => qc.setQueryData(treeQuery.queryKey, extraPageTree));
    await waitFor(() => expect(extra.view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    expect(extra.view.container.querySelector("[data-pb-history]")).toBeNull();
    expect(extra.view.container.querySelector("[data-pb-not-found]")).toBeNull();
  });

  it("dispatches an extra-root page through the root-qualified splat", async () => {
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 500 }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      const key = "extra/notes/y";
      const { view, queryClient } = renderAt("/extra/notes/y", (qc) => {
        qc.setQueryData(treeQuery.queryKey, extraPageTree);
        qc.setQueryData(pageByPathQuery(key).queryKey, pageResponse(WINNER_ID, "/extra/notes/y", "Extra Note", "extra"));
        qc.setQueryData(pageHtmlQuery(WINNER_ID, "extra").queryKey, htmlResponse(WINNER_ID, "/extra/notes/y", "Extra Note", "extra"));
      });

      await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Extra Note"));
      expect(queryClient.getQueryData(pageByPathQuery(key).queryKey)?.url).toBe("/extra/notes/y");
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("selects the primary landing from the wire field and normalizes one trailing slash", async () => {
    const primarySelectionTree: TreeResponse = {
      roots: [
        { ...extraPageTree.roots[0], tree: { ...extraPageTree.roots[0].tree, children: [] } },
        { ...emptyTree.roots[0], root: "handbook", tree: { ...emptyTree.roots[0].tree, url: "/hb" } },
      ],
    };
    const { history, view } = renderAt("/", (qc) => qc.setQueryData(treeQuery.queryKey, primarySelectionTree));

    await waitFor(() => expect(history.location.pathname).toBe("/hb"));
    await waitFor(() => expect(view.container.querySelector("[data-pb-folder] h1")?.textContent).toBe("handbook"));
    expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull();

    history.push("/hb/");
    await waitFor(() => expect(history.location.pathname).toBe("/hb/"));
    await waitFor(() => expect(view.container.querySelector("[data-pb-folder] h1")?.textContent).toBe("handbook"));
    expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull();
  });

  it("renders a root README child's content at bare /docs — README-preference applies to the root node too", async () => {
    const { history, view } = renderAt("/docs", (qc) => {
      qc.setQueryData(treeQuery.queryKey, rootReadmeTree);
      qc.setQueryData(pageHtmlQuery(WINNER_ID, "docs").queryKey, htmlResponse(WINNER_ID, "/docs/readme", "Docs Home"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Docs Home"));
    expect(history.location.pathname).toBe("/docs"); // rendered AT the root url, no redirect
    expect(view.container.querySelector("[data-pb-folder]")).toBeNull();
  });

  it("replaceStates an alias path to the canonical url from the by-path response", async () => {
    const canonical = "/docs/guides/deploy-guide";
    const { history } = renderAt("/docs/old/deployment", (qc) => {
      qc.setQueryData(pageByPathQuery("docs/old/deployment").queryKey, pageResponse(WINNER_ID, canonical, "Deploy Guide"));
      qc.setQueryData(pageByPathQuery("docs/guides/deploy-guide").queryKey, pageResponse(WINNER_ID, canonical, "Deploy Guide"));
      qc.setQueryData(pageHtmlQuery(WINNER_ID, "docs").queryKey, htmlResponse(WINNER_ID, canonical, "Deploy Guide"));
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
        qc.setQueryData(pageByPathQuery("docs/old/deployment").queryKey, pageResponse(WINNER_ID, canonical, "Deploy Guide"));
        qc.setQueryData(pageHtmlQuery(WINNER_ID, "docs").queryKey, htmlResponse(WINNER_ID, canonical, "Deploy Guide"));
        // PageContent now also subscribes pageQuery(id, root) for the metadata Rail — prime it so the
        // new fetch hits cache and the "no refetch" assertion below stays honest.
        qc.setQueryData(pageQuery(WINNER_ID, "docs").queryKey, pageResponse(WINNER_ID, canonical, "Deploy Guide"));
      });

      await waitFor(() => expect(history.location.pathname).toBe(canonical));
      await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Deploy Guide"));
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("renders a collision loser at its bare /p/{id} permalink, fetched by id, no redirect", async () => {
    // BOTH legs are keyed BARE, because the address is: the client acts on the root the reader typed.
    const { history, view } = renderAt(`/p/${LOSER_ID}`, (qc) => {
      qc.setQueryData(pageQuery(LOSER_ID, null).queryKey, pageResponse(LOSER_ID, null, "Shadowed Page"));
      qc.setQueryData(pageHtmlQuery(LOSER_ID, null).queryKey, htmlResponse(LOSER_ID, null, "Shadowed Page"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Shadowed Page"));
    expect(history.location.pathname).toBe(`/p/${LOSER_ID}`);
  });

  it("leaves a BARE permalink's HTML read BARE - never re-pinned to the root the first response named", async () => {
    // The client acts on the ADDRESS the reader used, never on a root it inferred from a response. Pin the
    // /html leg to the metadata response's root and the SPA quietly resolves an ambiguity the server refuses
    // to resolve: once that id is duplicated, a fresh load of this same bare `/p/{id}` answers 300 Multiple Choices
    // while the pinned SPA renders a page. That is the click-vs-reload split, one layer up.
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        calls.push(typeof input === "string" ? input : input.toString());
        return new Response(JSON.stringify(htmlResponse(LOSER_ID, null, "Shadowed Page", "extra")), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }),
    );
    try {
      // Only the METADATA leg is primed (bare, as the parse leaves it), and it names a root the address does
      // NOT - so the /html leg is the one live request and its URL is the assertion.
      const { view } = renderAt(`/p/${LOSER_ID}`, (qc) => {
        qc.setQueryData(pageQuery(LOSER_ID, null).queryKey, pageResponse(LOSER_ID, null, "Shadowed Page", "extra"));
      });

      await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Shadowed Page"));
      expect(calls.filter((u) => u.includes("/html"))).toEqual([`/api/v1/pages/${LOSER_ID}/html`]);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("carries the ROOTED permalink's root into the HTML read, because THAT address names one", async () => {
    // The other half of the rule: `?root=` belongs on the leg whose address supplied it. `/p/extra/{id}` was
    // typed with a root, so both reads carry it and a reload answers exactly what this render showed.
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        calls.push(typeof input === "string" ? input : input.toString());
        return new Response(JSON.stringify(htmlResponse(LOSER_ID, null, "Shadowed Page", "extra")), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }),
    );
    try {
      const { view } = renderAt(`/p/extra/${LOSER_ID}`, (qc) => {
        qc.setQueryData(pageQuery(LOSER_ID, "extra").queryKey, pageResponse(LOSER_ID, null, "Shadowed Page", "extra"));
      });

      await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Shadowed Page"));
      expect(calls.filter((u) => u.includes("/html"))).toEqual([`/api/v1/pages/${LOSER_ID}/html?root=extra`]);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("renders one rooted permalink link per candidate root when a bare id reads 409 ambiguous_page_id", async () => {
    // The 409 message ENDS "retry against one of the candidate roots below", so a view that renders the
    // message alone points its remedy at nothing. The links are the ROOTED permalinks, not the envelope's
    // own candidate `url`s: those are the API retry targets and would hand a reader raw JSON.
    const envelope = {
      error: {
        code: "ambiguous_page_id",
        message: `The id ${DUP_ID} exists in more than one root, so the server cannot pick one; retry against one of the candidate roots below.`,
        candidates: [
          { root: "runbooks", url: `/api/v1/pages/${DUP_ID}?root=runbooks` },
          { root: "docs", url: `/api/v1/pages/${DUP_ID}?root=docs` },
        ],
      },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify(envelope), { status: 409, headers: { "content-type": "application/json" } })),
    );
    try {
      const { view } = renderAt(`/p/${DUP_ID}`, () => {});

      await waitFor(() => expect(view.container.querySelector("[data-pb-candidates]")).not.toBeNull());
      const links = [...view.container.querySelectorAll("[data-pb-candidates] a")];
      // Rank order is the server's, and it is preserved verbatim - the client never re-sorts the roots.
      expect(links.map((a) => a.textContent)).toEqual(["runbooks", "docs"]);
      expect(links.map((a) => a.getAttribute("href"))).toEqual([`/p/runbooks/${DUP_ID}`, `/p/docs/${DUP_ID}`]);
      // The message the links answer is still on screen; the API retry urls are NOT linked.
      expect(view.container.querySelector("[data-pb-error]")?.textContent).toContain("retry against one of the candidate roots");
      expect(view.container.innerHTML).not.toContain("/api/v1/pages/");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("ignores a stale trailing slug segment on a permalink, like the server route", async () => {
    const { view } = renderAt(`/p/${LOSER_ID}/stale-slug`, (qc) => {
      qc.setQueryData(pageQuery(LOSER_ID, null).queryKey, pageResponse(LOSER_ID, null, "Shadowed Page"));
      qc.setQueryData(pageHtmlQuery(LOSER_ID, null).queryKey, htmlResponse(LOSER_ID, null, "Shadowed Page"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Shadowed Page"));
  });

  it("renders a ROOTED loser permalink by (root, id), not by the root segment read as an id", async () => {
    // `/p/extra/{id}`: the address the server has emitted since C5. A splat parse that takes the first
    // segment reads "extra" as the page id, so ONLY the rooted keys are primed and fetch is a loud 500
    // - an accidental network call fails loudly instead of degrading into a quiet 404.
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 500 }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      const { history, view } = renderAt(`/p/extra/${LOSER_ID}`, (qc) => {
        // `url` null on the metadata prime, or the canonical replace fires and this row fails under
        // CORRECT code.
        qc.setQueryData(pageQuery(LOSER_ID, "extra").queryKey, pageResponse(LOSER_ID, null, "Shadowed Page", "extra"));
        qc.setQueryData(pageHtmlQuery(LOSER_ID, "extra").queryKey, htmlResponse(LOSER_ID, null, "Shadowed Page", "extra"));
      });

      await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Shadowed Page"));
      expect(history.location.pathname).toBe(`/p/extra/${LOSER_ID}`);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("keys a rooted permalink's BOTH id-addressed reads per root: the same id in two roots is two pages", async () => {
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 500 }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      // extra FIRST and the primary root LAST, deliberately: with a bare key the two primes of a leg COLLIDE and
      // the last seeded wins, so a dropped root renders the primary root's copy rather than nothing. That makes
      // each back-out deterministic instead of order-dependent.
      const { view } = renderAt(`/p/extra/${DUP_ID}`, (qc) => {
        qc.setQueryData(pageHtmlQuery(DUP_ID, "extra").queryKey, htmlResponse(DUP_ID, null, "Extra Copy", "extra"));
        qc.setQueryData(pageQuery(DUP_ID, "extra").queryKey, {
          ...pageResponse(DUP_ID, null, "Extra Copy", "extra"),
          frontmatter: { owner: "extra-owner" },
        });
        qc.setQueryData(pageHtmlQuery(DUP_ID, "docs").queryKey, htmlResponse(DUP_ID, null, "Main Copy"));
        qc.setQueryData(pageQuery(DUP_ID, "docs").queryKey, {
          ...pageResponse(DUP_ID, null, "Main Copy"),
          frontmatter: { owner: "main-owner" },
        });
      });

      // (1) the html key
      await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Extra Copy"));
      // (2) the metadata key. The rail CARD always renders and only its Owner ROW is frontmatter-gated,
      // so this reads the card's TEXT.
      const rail = view.container.querySelector("[data-pb-rail-meta]");
      expect(rail).not.toBeNull();
      expect(rail!.textContent).toContain("extra-owner");
      expect(rail!.textContent).not.toContain("main-owner");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("replaces a ROOTED permalink with the page's canonical url when it has one", async () => {
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 500 }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      // The primed page's `url` must be NON-null or canonicalUrl is undefined and nothing replaces; the
      // destination is primed too, so the row stays quiet rather than firing a live by-path fetch.
      const canonical = "/extra/guides/x";
      const { history } = renderAt(`/p/extra/${WINNER_ID}`, (qc) => {
        qc.setQueryData(pageQuery(WINNER_ID, "extra").queryKey, pageResponse(WINNER_ID, canonical, "Deploy Guide", "extra"));
        qc.setQueryData(pageHtmlQuery(WINNER_ID, "extra").queryKey, htmlResponse(WINNER_ID, canonical, "Deploy Guide", "extra"));
        qc.setQueryData(pageByPathQuery("extra/guides/x").queryKey, pageResponse(WINNER_ID, canonical, "Deploy Guide", "extra"));
      });

      await waitFor(() => expect(history.location.pathname).toBe(canonical));
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("ignores a stale trailing slug segment on a ROOTED permalink too", async () => {
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 500 }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      const { view } = renderAt(`/p/extra/${LOSER_ID}/stale-slug`, (qc) => {
        qc.setQueryData(pageQuery(LOSER_ID, "extra").queryKey, pageResponse(LOSER_ID, null, "Shadowed Page", "extra"));
        qc.setQueryData(pageHtmlQuery(LOSER_ID, "extra").queryKey, htmlResponse(LOSER_ID, null, "Shadowed Page", "extra"));
      });

      await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Shadowed Page"));
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("reads an ordinary root-qualified page's HTML BY ROOT, not by bare id", async () => {
    // The PRIMARY read path, not the permalink route: on a corpus carrying a cross-root duplicate id a
    // bare `GET /api/v1/pages/{id}/html` answers 409, so this is the whole `/docs` read view for that
    // page, not just its permalink. Only the `extra` key is primed and fetch is a loud 500, so a
    // root-blind read cannot quietly succeed.
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 500 }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      const { view } = renderAt("/extra/permalink/hub", (qc) => {
        qc.setQueryData(treeQuery.queryKey, twoRoots);
        // `url` is EXACTLY the mounted path, so DocsPage's canonical-replace effect stays quiet.
        qc.setQueryData(
          pageByPathQuery("extra/permalink/hub").queryKey,
          pageResponse(DUP_ID, "/extra/permalink/hub", "Permalink Hub", "extra"),
        );
        qc.setQueryData(pageHtmlQuery(DUP_ID, "extra").queryKey, htmlResponse(DUP_ID, "/extra/permalink/hub", "Permalink Hub", "extra"));
      });

      await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Permalink Hub"));
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("does not snap the URL back when navigating away from a resolved page", async () => {
    // Regression: during a click-navigation the OUTGOING DocsPage briefly observes the
    // incoming pathname; its canonical-correction must not replace the URL back.
    const canonicalA = "/docs/guides/deploy-guide";
    const { history } = renderAt(canonicalA, (qc) => {
      qc.setQueryData(pageByPathQuery("docs/guides/deploy-guide").queryKey, pageResponse(WINNER_ID, canonicalA, "Deploy Guide"));
      qc.setQueryData(pageHtmlQuery(WINNER_ID, "docs").queryKey, htmlResponse(WINNER_ID, canonicalA, "Deploy Guide"));
      qc.setQueryData(pageByPathQuery("docs/welcome").queryKey, pageResponse(LOSER_ID, "/docs/welcome", "Welcome"));
      qc.setQueryData(pageHtmlQuery(LOSER_ID, "docs").queryKey, htmlResponse(LOSER_ID, "/docs/welcome", "Welcome"));
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
        qc.setQueryData(pageByPathQuery("docs/guides/deploy-guide").queryKey, pageResponse(WINNER_ID, null, "Deploy Guide"));
        // Left UNROOTED deliberately: the %2F guard 404s before PageContent ever mounts, so this
        // prime is never read under any root spelling.
        qc.setQueryData(pageHtmlQuery(WINNER_ID, null).queryKey, htmlResponse(WINNER_ID, null, "Deploy Guide"));
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
      qc.setQueryData(pageQuery(LOSER_ID, null).queryKey, pageResponse(LOSER_ID, null, "Shadowed Page"));
      qc.setQueryData(pageHtmlQuery(LOSER_ID, null).queryKey, htmlResponse(LOSER_ID, null, "Shadowed Page"));
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

  it("keeps the option-B route table matched to its recorded addresses", () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: ["/"] }));
    const exemplars = [
      { address: "/", routePath: "/", routeId: "/", params: {} },
      { address: "/new", routePath: "/new", routeId: "/new", params: {} },
      { address: "/admin", routePath: "/admin", routeId: "/admin", params: {} },
      { address: "/review", routePath: "/review", routeId: "/review", params: {} },
      { address: "/review/123", routePath: "/review/$id", routeId: "/review/$id", params: { id: "123" } },
      { address: "/p/docs/abc", routePath: "/p/$", routeId: "/p/$", params: { _splat: "docs/abc" } },
      { address: "/p/abc", routePath: "/p/$", routeId: "/p/$", params: { _splat: "abc" } },
      { address: "/docs", routePath: "/$", routeId: "/$", params: { _splat: "docs" } },
      { address: "/docs/", routePath: "/$", routeId: "/$", params: { _splat: "docs" } },
      { address: "/docs/guides/deploy-guide", routePath: "/$", routeId: "/$", params: { _splat: "docs/guides/deploy-guide" } },
      { address: "/extra/notes/y", routePath: "/$", routeId: "/$", params: { _splat: "extra/notes/y" } },
      { address: "/nope/guides/x", routePath: "/$", routeId: "/$", params: { _splat: "nope/guides/x" } },
    ];
    const registeredPaths = Object.values(router.routesById)
      .filter((route) => route.id !== "__root__" && route.fullPath !== "/$")
      .map((route) => route.fullPath);
    for (const path of registeredPaths) expect(exemplars.some((exemplar) => exemplar.routePath === path), path).toBe(true);

    const observed = exemplars.map(({ address, routeId, params }) => {
      const match = router.matchRoutes(address).at(-1)!;
      const observedParams: Record<string, string> = { ...(match.params as Record<string, string>) };
      delete observedParams["*"];
      expect(match.routeId).toBe(routeId);
      expect(observedParams).toEqual(params);
      return { routeId: match.routeId, params: observedParams };
    });
    expect(observed).toEqual(exemplars.map(({ routeId, params }) => ({ routeId, params })));
    expect(new Set(observed.map((match) => match.routeId)).size).toBeGreaterThan(1);
  });
});

describe("review nav gating (F8 — session.authenticated, the only available signal)", () => {
  it("shows the Review nav link when the session reports authenticated:true", async () => {
    const { view } = renderAt("/docs", (qc) => {
      qc.setQueryData(sessionQuery.queryKey, { authenticated: true, username: "admin", csrf_token: "c", auth_mode: "builtin" });
    });
    await waitFor(() => expect(view.container.querySelector("[data-pb-review-nav]")).not.toBeNull());
  });

  it("hides the Review nav link when unauthenticated (the default ANON_SESSION)", async () => {
    const { view } = renderAt("/docs", () => {});
    await waitFor(() => expect(view.container.querySelector("[data-pb-shell]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-review-nav]")).toBeNull();
  });
});
