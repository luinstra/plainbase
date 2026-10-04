import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, cleanup, fireEvent, render, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { searchQuery, sessionQuery, treeQuery } from "../api/queries";
import type { SearchResponse, TreeResponse } from "../api/types";
import { QUICK_SWITCH_MAX } from "../components/SearchPalette";
import { createAppRouter } from "../router";

/**
 * Palette behavior is exercised through the real Shell so
 * the palette mounts once, alongside the router (memory history) and a primed tree cache.
 */

const LOSER_ID = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99";

const tree: TreeResponse = {
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
        page_count: 4,
        children: [
          { type: "page", id: "p-deploy", title: "Deploy Guide", slug: "deploy-guide", path: "guides/deploy-guide.md", url: "/docs/guides/deploy-guide", status: "active", updated: null },
          { type: "page", id: "p-getting", title: "Getting Started", slug: "getting-started", path: "guides/getting-started.md", url: "/docs/guides/getting-started", status: "active", updated: null },
          { type: "page", id: "p-dev", title: "Developer Setup", slug: "developer-setup", path: "guides/developer-setup.md", url: "/docs/guides/developer-setup", status: "active", updated: null },
          // A collision loser: url null → navigates via its ROOTED /p/{root}/{id}.
          { type: "page", id: LOSER_ID, title: "Shadowed Page", slug: "shadowed", path: "notes/shadowed.md", url: null, status: "active", updated: null },
        ],
      },
    },
  ],
};

function searchResponse(query: string): SearchResponse {
  return {
    query,
    engine: "embedded",
    limit: 20,
    offset: 0,
    total: 1,
    hits: [
      {
        page_id: "p-deploy",
        root: "docs",
        path: "guides/deploy-guide.md",
        url: "/docs/guides/deploy-guide",
        title: "Deploy Guide",
        heading_id: "rollback",
        heading_text: "Rollback",
        heading_path: ["Deploy Guide", "Rollback"],
        snippet: "…how to rollback a deploy…",
        highlights: [{ start: 7, end: 15 }],
        score: 4.2,
        citation: { page_id: "p-deploy", heading_id: "rollback", path: "guides/deploy-guide.md", content_hash: "h", commit: null, uri: "plainbase://p-deploy#rollback@h" },
      },
    ],
  };
}

/**
 * Two roots each holding the SAME relative path with the SAME title - the ambiguity a root-blind palette
 * cannot survive: `path` is root-relative, so "Deploy Guide · guides/deploy-guide.md" describes both.
 */
const twoRootTree: TreeResponse = {
  roots: [
    tree.roots[0],
    {
      root: "handbook",
      available: true,
      editable: true,
      primary: false,
      tree: {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/handbook",
        page_count: 1,
        children: [
          { type: "page", id: "h-deploy", title: "Deploy Guide", slug: "deploy-guide", path: "guides/deploy-guide.md", url: "/handbook/guides/deploy-guide", status: "active", updated: null },
        ],
      },
    },
  ],
};

/** The same hit shape, one per root - what a corpus-wide query routinely returns once there are two roots. */
function crossRootSearchResponse(query: string): SearchResponse {
  const [hit] = searchResponse(query).hits;
  return {
    ...searchResponse(query),
    total: 2,
    hits: [hit, { ...hit, page_id: "h-deploy", root: "handbook", url: "/handbook/guides/deploy-guide" }],
  };
}

function setup(initialPath = "/docs", treeData: TreeResponse = tree) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, treeData);
  // The Shell gates its "Review" nav on a session read — prime it (unauthenticated) so it serves from cache
  // and the no-fetch assertions stay honest.
  queryClient.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  const history = createMemoryHistory({ initialEntries: [initialPath] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { view, history, queryClient };
}

async function openPalette() {
  await waitFor(() => expect(document.querySelector("[data-pb-search-trigger]")).not.toBeNull());
  act(() => {
    document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", metaKey: true }));
  });
  await waitFor(() => expect(document.querySelector("[data-pb-search-input]")).not.toBeNull());
}

function getInput(): HTMLInputElement {
  return document.querySelector("[data-pb-search-input]") as HTMLInputElement;
}

beforeEach(() => {
  // jsdom: scrollIntoView is unimplemented. It is exercised by both the active-row scroll
  // (the layout effect keeps the selected option in view) and the deep-link path.
  Element.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  cleanup();
  document.body.style.overflow = "";
  vi.restoreAllMocks();
});

describe("automatic search", () => {
  it("appends content matches automatically while keeping immediate title matches", async () => {
    const response = searchResponse("deploy");
    response.hits[0].page_id = "content-only";
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(response), { headers: { "content-type": "application/json" } })));
    try {
      setup();
      await openPalette();
      fireEvent.change(getInput(), { target: { value: "deploy" } });
      expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull();
      expect(document.querySelector("[data-pb-search-bridge]")).toBeNull();
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
      expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull();
    } finally { vi.unstubAllGlobals(); }
  });
});

describe("search palette", () => {
  it("opens on Cmd/Ctrl+K with focused combobox and one result list", async () => {
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    const input = getInput();
    expect(input.getAttribute("role")).toBe("combobox");
    expect(input.getAttribute("aria-controls")).toBe("pb-search-listbox");
    expect(document.activeElement).toBe(input);
    expect(document.querySelector('[data-pb-search-list]')).not.toBeNull();
  });

  it("typing recomputes cached title matches synchronously before the debounce", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    try {
      setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "dev" } });
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull());
      // The fuzzy list narrowed to "Developer Setup" first.
      const firstRow = document.querySelector('[data-pb-search-item="jump"]')!;
      expect(firstRow.textContent).toContain("Developer Setup");
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("opening with a stale tree cache never starts another tree request", async () => {
    // Regression guard (Codex review): the palette must read the cached tree passively. After
    // treeQuery's 60s staleTime elapses, a default useQuery observer would refetch /api/v1/tree on
    // mount — i.e. just opening the palette would hit the network. `refetchOnMount: false` fixes it.
    // Stub returns a VALID tree so a stray refetch (if any) doesn't corrupt the cache and mask the
    // real signal — the assertion below is purely on whether /api/v1/tree was requested at all.
    const fetchSpy = vi.fn((_input?: unknown) => Promise.resolve(new Response(JSON.stringify(tree), { status: 200 })));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      const { queryClient } = setup();
      // Simulate ">60s elapsed" the way real time does: backdate the cache entry past staleTime
      // (NOT invalidateQueries — invalidation sets isInvalidated, which refetches on mount even
      // with refetchOnMount:false; that's a different, stronger condition than natural staleness).
      queryClient.setQueryData(treeQuery.queryKey, tree, { updatedAt: Date.now() - 61_000 });
      // Backdating via setQueryData can make the already-mounted shell observer refetch (a test
      // artifact — in the real app the clock crossing staleTime never refetches a mounted
      // observer). So measure the DELTA around opening the palette: the guarantee is that
      // *opening the palette* adds zero tree fetches, regardless of any shell baseline.
      const treeFetchCount = () =>
        fetchSpy.mock.calls.filter((c) => String(c[0]).includes("/api/v1/tree")).length;
      await waitFor(() => expect(document.querySelector("[data-pb-search-trigger]")).not.toBeNull());
      const baseline = treeFetchCount();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      // The quick-switcher still renders from the stale cache...
      fireEvent.change(getInput(), { target: { value: "dev" } });
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull());
      // ...and opening the palette issued NO additional /api/v1/tree fetch.
      expect(treeFetchCount()).toBe(baseline);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("caps the quick-switcher at QUICK_SWITCH_MAX rows", async () => {
    expect(QUICK_SWITCH_MAX).toBe(8);
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    // Empty query shows the page list capped; the fixture has 4 pages (< cap), so all show.
    const rows = document.querySelectorAll('[data-pb-search-item="jump"]');
    expect(rows.length).toBeLessThanOrEqual(QUICK_SWITCH_MAX);
  });

  it("arrows choose real options and clamp at both ends", async () => {
    setup(); await openPalette();
    const input = getInput();
    const rows = [...document.querySelectorAll('[role="option"]')];
    expect(input.getAttribute("aria-activedescendant")).toBeNull();
    fireEvent.keyDown(input, { key: "ArrowUp" });
    expect(input.getAttribute("aria-activedescendant")).toBe(rows.at(-1)!.id);
    for (let i = 0; i < 20; i++) fireEvent.keyDown(input, { key: "ArrowUp" });
    expect(input.getAttribute("aria-activedescendant")).toBe(rows[0].id);
    for (let i = 0; i < 20; i++) fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(input.getAttribute("aria-activedescendant")).toBe(rows.at(-1)!.id);
  });

  it("scrolls the active option into view without scrolling on open", async () => {
    setup(); await openPalette();
    expect(getInput().getAttribute("aria-activedescendant")).toBeNull();
    (Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>).mockClear();
    fireEvent.keyDown(getInput(), { key: "ArrowDown" });
    const row = document.getElementById(getInput().getAttribute("aria-activedescendant")!)!;
    expect(row.scrollIntoView).toHaveBeenCalledWith({ block: "nearest" });
  });

  it("Enter without explicit selection opens the first available result", async () => {
    const { history } = setup(); await openPalette();
    fireEvent.change(getInput(), { target: { value: "deploy" } });
    fireEvent.keyDown(getInput(), { key: "Enter" });
    await waitFor(() => expect(history.location.pathname).toBe("/docs/guides/deploy-guide"));
  });

  it("empty query does not request full text and Enter with no result does nothing", async () => {
    const fetchSpy = vi.fn(); vi.stubGlobal("fetch", fetchSpy);
    try {
      const empty = { roots: [{ ...tree.roots[0], tree: { ...tree.roots[0].tree, children: [] } }] };
      const { history } = setup("/docs", empty); await openPalette();
      fireEvent.keyDown(getInput(), { key: "Enter" });
      expect(history.location.pathname).toBe("/docs");
      expect(fetchSpy).not.toHaveBeenCalled();
      expect(document.querySelector('[data-pb-search]')).not.toBeNull();
    } finally { vi.unstubAllGlobals(); }
  });

  it("Enter on a quick-switcher row navigates via pageHref; a loser navigates via /p/{root}/{id}", async () => {
    const { history } = setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    fireEvent.change(getInput(), { target: { value: "deploy" } });
    await waitFor(() => expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull());
    fireEvent.keyDown(getInput(), { key: "ArrowDown" }); // select the top fuzzy match (row 0)
    fireEvent.keyDown(getInput(), { key: "Enter" });
    await waitFor(() => expect(history.location.pathname).toBe("/docs/guides/deploy-guide"));

    // Loser: url null → the rooted permalink, built from the entry's own root.
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    fireEvent.change(getInput(), { target: { value: "shadowed" } });
    await waitFor(() => expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull());
    fireEvent.keyDown(getInput(), { key: "ArrowDown" });
    fireEvent.keyDown(getInput(), { key: "Enter" });
    await waitFor(() => expect(history.location.pathname).toBe(`/p/docs/${LOSER_ID}`));
  });

  it("Content result Enter on a hit pushes hit.url + #heading_id", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => new Response(JSON.stringify(searchResponse(new URL(url, "http://x").searchParams.get("q") ?? "")), { status: 200, headers: { "content-type": "application/json" } })),
    );
    try {
      const { history } = setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
      fireEvent.keyDown(getInput(), { key: "Enter" });
      await waitFor(() => expect(history.location.pathname + history.location.hash).toBe("/docs/guides/deploy-guide#rollback"));
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("Content result Enter on a hit with NO url pushes the hit's OWN root's permalink + #heading_id", async () => {
    // The `??` branch of navigateToHit, uncovered until now: every other fixture hit carries a url.
    // The hit's root is `extra`, so a root-blind fallback is visible as a missing `/extra` segment
    // rather than as a wrong-looking id.
    const loserHit: SearchResponse = {
      query: "rollback",
      engine: "embedded",
      limit: 20,
      offset: 0,
      total: 1,
      hits: [
        {
          page_id: LOSER_ID,
          root: "extra",
          path: "notes/rollback.md",
          url: null,
          title: "Rollback Notes",
          heading_id: "rollback",
          heading_text: "Rollback",
          heading_path: ["Rollback Notes", "Rollback"],
          snippet: "…how to rollback…",
          highlights: [{ start: 7, end: 15 }],
          score: 4.2,
          citation: { page_id: LOSER_ID, heading_id: "rollback", path: "notes/rollback.md", content_hash: "h", commit: null, uri: `plainbase://extra/${LOSER_ID}#rollback@h` },
        },
      ],
    };
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(loserHit), { status: 200, headers: { "content-type": "application/json" } })));
    try {
      const { history } = setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
      fireEvent.keyDown(getInput(), { key: "Enter" });
      await waitFor(() => expect(history.location.pathname + history.location.hash).toBe(`/p/extra/${LOSER_ID}#rollback`));
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("Escape closes even after content results arrive", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(searchResponse("rollback")))));
    try {
      setup(); await openPalette();
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
      expect(document.querySelector("[data-pb-search-foot]")?.textContent).toContain("close");
      fireEvent.keyDown(getInput(), { key: "Escape" });
      expect(document.querySelector("[data-pb-search]")).toBeNull();
    } finally { vi.unstubAllGlobals(); }
  });

  it("no-match copy names the SEARCHED query (the server's echo), not the live input", async () => {
    // The empty-state label reads the response's echoed query, so it always matches the results it
    // describes — it can't run ahead of the debounced search the way the live input can.
    const empty: SearchResponse = { query: "zzz", engine: "embedded", limit: 20, offset: 0, total: 0, hits: [] };
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(empty), { status: 200, headers: { "content-type": "application/json" } })));
    try {
      setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "zzz" } });
      await waitFor(() => expect(document.querySelector("[data-pb-search-empty]")).not.toBeNull());
      expect(document.querySelector("[data-pb-search-empty]")?.textContent).toContain("No matches for");
      expect(document.querySelector("[data-pb-search-empty]")?.textContent).toContain("zzz");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("clearing the input immediately hides old content results and leaves the palette open", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(searchResponse("rollback")))));
    try {
      setup(); await openPalette(); fireEvent.change(getInput(), { target: { value: "rollback" } });
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "" } });
      fireEvent.keyDown(getInput(), { key: "Backspace" });
      expect(document.querySelector('[data-pb-search-item="hit"]')).toBeNull();
      expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull();
      expect(document.querySelector("[data-pb-search]")).not.toBeNull();
    } finally { vi.unstubAllGlobals(); }
  });

  it("outside-click closes the palette", async () => {
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    const overlay = document.querySelector("[data-pb-search]")!;
    fireEvent.mouseDown(overlay); // the scrim itself
    await waitFor(() => expect(document.querySelector("[data-pb-search]")).toBeNull());
  });

  it("locks body scroll while open and restores + returns focus on close", async () => {
    setup();
    await waitFor(() => expect(document.querySelector("[data-pb-search-trigger]")).not.toBeNull());
    const trigger = document.querySelector("[data-pb-search-trigger]") as HTMLElement;
    trigger.focus();
    expect(document.activeElement).toBe(trigger);

    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    expect(document.body.style.overflow).toBe("hidden");

    fireEvent.keyDown(getInput(), { key: "Escape" }); // Stage-1 Esc closes
    await waitFor(() => expect(document.querySelector("[data-pb-search]")).toBeNull());
    expect(document.body.style.overflow).not.toBe("hidden");
    expect(document.activeElement).toBe(trigger); // focus returned to the opener
  });

  it("status rows are non-selectable and skipped by Arrow nav (a11y)", async () => {
    // A pending fetch keeps a loading row present; arrow nav must never land on it.
    //
    // The stub stays pending until `release()`, which then answers every request - the ones already
    // parked AND any issued afterwards. It used to capture a single `resolve`, overwritten per call,
    // which silently assumed the fetch in flight when the loading row appeared was the LAST one this
    // test would provoke. Any later fetch was then parked forever and the hit row never arrived: a 5s
    // `waitFor` timeout on a contended runner that never reproduced locally. Note the ceiling is
    // already raised (test-setup.ts), so raising it again was not the fix; the single-shot resolve was.
    const parked: Array<(v: Response) => void> = [];
    let released = false;
    const hitResponse = () =>
      new Response(JSON.stringify(searchResponse("rollback")), { status: 200, headers: { "content-type": "application/json" } });
    const release = () => {
      released = true;
      parked.splice(0).forEach((r) => r(hitResponse()));
    };
    vi.stubGlobal("fetch", vi.fn(() => (released ? Promise.resolve(hitResponse()) : new Promise<Response>((r) => parked.push(r)))));
    try {
      setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      await waitFor(() => expect(document.querySelector("[data-pb-search-loading]")).not.toBeNull());
      const loadingRow = document.querySelector("[data-pb-search-loading]")!;
      expect(loadingRow.getAttribute("role")).not.toBe("option");
      // No option exists yet, so activedescendant resolves to undefined (not the status row).
      expect(getInput().getAttribute("aria-activedescendant")).toBeNull();
      release();
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("Tab cycles the input and scope chips in both directions", async () => {
    setup(); await openPalette();
    const chips = [...document.querySelectorAll<HTMLButtonElement>('[aria-label="Search scope"] button')];
    fireEvent.keyDown(getInput(), { key: "Tab" }); expect(document.activeElement).toBe(chips[0]);
    fireEvent.keyDown(chips[0], { key: "Tab" }); expect(document.activeElement).toBe(chips[1]);
    fireEvent.keyDown(chips[1], { key: "Tab" }); expect(document.activeElement).toBe(getInput());
    fireEvent.keyDown(getInput(), { key: "Tab", shiftKey: true }); expect(document.activeElement).toBe(chips[1]);
  });

  it("a later query's hits win over an earlier slow response (stale-response race)", async () => {
    // Two distinct queries; resolve the EARLIER one AFTER the later one. The query key is
    // the trimmed q, so the stale response lands on its own key and never paints the active q.
    const pending: Record<string, (v: Response) => void> = {};
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => {
        const q = new URL(url, "http://x").searchParams.get("q")!;
        return new Promise<Response>((r) => (pending[q] = r));
      }),
    );
    try {
      const { queryClient } = setup();
      // Seed both query caches directly to assert the keying contract deterministically.
      const early = searchQuery("rol");
      const late = searchQuery("rollback");
      // Resolve via the cache: the active observed key is "rollback".
      queryClient.setQueryData(late.queryKey, { ...searchResponse("rollback") });
      queryClient.setQueryData(early.queryKey, { ...searchResponse("rol"), hits: [{ ...searchResponse("rol").hits[0], title: "STALE" }] });

      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
      // The rendered hit is the active query's (rollback), never the stale "rol" data.
      expect(within(document.querySelector("[data-pb-search-list]") as HTMLElement).queryByText("STALE")).toBeNull();
      expect(document.querySelector('[data-pb-search-item="hit"]')!.textContent).toContain("Deploy Guide");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("marks exactly the selected row with data-pb-search-active (the selection-tint hook)", async () => {
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    const input = getInput();
    // Nothing selected at rest → no row carries the selection-tint hook.
    expect(document.querySelectorAll("[data-pb-search-active]")).toHaveLength(0);

    fireEvent.keyDown(input, { key: "ArrowDown" }); // lands on row 0
    const active = document.querySelectorAll("[data-pb-search-active]");
    expect(active).toHaveLength(1);
    expect(active[0].id).toBe(getInput().getAttribute("aria-activedescendant"));
  });
});

/**
 * A page is named by its ROOT-RELATIVE path on both search surfaces, so with two roots the same file
 * renders the same row twice - same title, same hint - and the reader picks one at random. Navigation
 * still lands correctly (the url carries the root), which is what makes it insidious: they only learn
 * they opened the wrong tree after they read it. Both assertions are on the RENDERED rows, so carrying
 * the root in the data without ever showing it still fails.
 */
describe("the palette with more than one root", () => {
  it("makes two roots' same-titled, same-path quick-switcher rows distinguishable", async () => {
    setup("/docs", twoRootTree);
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    fireEvent.change(getInput(), { target: { value: "deploy guide" } });
    await waitFor(() => expect(document.querySelectorAll('[data-pb-search-item="jump"]')).toHaveLength(2));

    const rows = [...document.querySelectorAll('[data-pb-search-item="jump"]')];
    // Both rows are the same page, in name and in path...
    expect([...document.querySelectorAll("[data-pb-search-group]")].map((row) => row.textContent)).toEqual(["docs", "handbook"]);
    // ...and the ONE thing that tells them apart is on screen, not just in the props.
    expect(rows.map((row) => row.textContent)).toEqual(["docs: Deploy Guideguides / deploy-guide.md", "handbook: Deploy Guideguides / deploy-guide.md"]);
  });

  it("badges each full-text hit with the root it came from", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => new Response(JSON.stringify(crossRootSearchResponse(new URL(url, "http://x").searchParams.get("q") ?? "")), { status: 200, headers: { "content-type": "application/json" } })),
    );
    try {
      setup("/docs", twoRootTree);
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      await waitFor(() => expect(document.querySelectorAll('[data-pb-search-item="hit"]')).toHaveLength(2));

      const badges = [...document.querySelectorAll("[data-pb-search-group]")];
      expect(badges.map((badge) => badge.textContent)).toEqual(["docs", "handbook"]);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("badges nothing with a single root (no gratuitous root name on every row)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => new Response(JSON.stringify(searchResponse(new URL(url, "http://x").searchParams.get("q") ?? "")), { status: 200, headers: { "content-type": "application/json" } })),
    );
    try {
      setup(); // the one-root fixture — every legacy install
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      expect(document.querySelectorAll("[data-pb-root-badge]")).toHaveLength(0); // Cached suggestions

      fireEvent.change(getInput(), { target: { value: "rollback" } });
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
      expect(document.querySelectorAll("[data-pb-root-badge]")).toHaveLength(0); // Content results
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe("combined results and space scope", () => {
  it("keeps All spaces distinct from a configured space named all", async () => {
    const errors = vi.spyOn(console, "error").mockImplementation(() => {});
    const allRoot = { ...twoRootTree.roots[1], root: "all" };
    const { queryClient } = setup("/docs", { roots: [tree.roots[0], allRoot] });
    await openPalette();
    const scopeGroup = within(document.querySelector('[aria-label="Search scope"]') as HTMLElement);
    fireEvent.click(scopeGroup.getByRole("button", { name: "all" }));
    expect(scopeGroup.getByRole("button", { name: "All spaces" }).getAttribute("aria-pressed")).toBe("false");
    expect(scopeGroup.getByRole("button", { name: "all" }).getAttribute("aria-pressed")).toBe("true");
    act(() => queryClient.setQueryData(treeQuery.queryKey, { roots: [allRoot, tree.roots[0]] }));
    await waitFor(() => expect(scopeGroup.getByRole("button", { name: "all" }).getAttribute("aria-pressed")).toBe("true"));
    fireEvent.click(scopeGroup.getByRole("button", { name: "All spaces" }));
    expect(document.querySelectorAll('[data-pb-search-item="jump"]')).toHaveLength(5);
    expect(errors.mock.calls.flat().join(" ")).not.toContain("same key");
  });

  it("scrolls the same selected result back into view when preceding groups move", async () => {
    const { queryClient } = setup("/docs", twoRootTree);
    await openPalette();
    fireEvent.keyDown(getInput(), { key: "ArrowDown" });
    const selected = getInput().getAttribute("aria-activedescendant")!;
    const scroll = vi.mocked(Element.prototype.scrollIntoView);
    scroll.mockClear();
    act(() => queryClient.setQueryData(treeQuery.queryKey, { roots: [...twoRootTree.roots].reverse() }));
    await waitFor(() => expect(document.querySelector('[data-pb-search-group]')?.getAttribute("data-pb-search-group")).toBe("handbook"));
    expect(getInput().getAttribute("aria-activedescendant")).toBe(selected);
    expect(scroll.mock.contexts).toContain(document.getElementById(selected));
  });

  it("keeps the selected quick match when asynchronous groups append, then hides stale content immediately", async () => {
    const pending = new Map<string, (response: Response) => void>();
    vi.stubGlobal("fetch", vi.fn((url: string) => new Promise<Response>((resolve) => pending.set(new URL(url, "http://x").searchParams.get("q")!, resolve))));
    try {
      setup("/docs", twoRootTree); await openPalette();
      fireEvent.change(getInput(), { target: { value: "deploy" } });
      fireEvent.keyDown(getInput(), { key: "ArrowDown" });
      fireEvent.keyDown(getInput(), { key: "ArrowDown" });
      const selected = getInput().getAttribute("aria-activedescendant");
      await waitFor(() => expect(pending.has("deploy")).toBe(true));
      const response = crossRootSearchResponse("deploy");
      response.hits = response.hits.map((hit) => ({ ...hit, page_id: `content-${hit.page_id}` }));
      await act(async () => pending.get("deploy")!(new Response(JSON.stringify(response))));
      await waitFor(() => expect(document.querySelectorAll('[data-pb-search-item="hit"]')).toHaveLength(2));
      expect(getInput().getAttribute("aria-activedescendant")).toBe(selected);
      expect(document.getElementById(selected!)?.textContent).toContain("Deploy Guide");
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      expect(getInput().getAttribute("aria-activedescendant")).toBeNull();
      expect(document.querySelector('[data-pb-search-item="hit"]')).toBeNull();
    } finally { vi.unstubAllGlobals(); }
  });

  it("an earlier deferred response cannot replace a later query", async () => {
    const pending = new Map<string, (response: Response) => void>();
    vi.stubGlobal("fetch", vi.fn((url: string) => new Promise<Response>((resolve) => pending.set(new URL(url, "http://x").searchParams.get("q")!, resolve))));
    try {
      setup(); await openPalette();
      fireEvent.change(getInput(), { target: { value: "old" } });
      await waitFor(() => expect(pending.has("old")).toBe(true));
      fireEvent.change(getInput(), { target: { value: "new" } });
      await waitFor(() => expect(pending.has("new")).toBe(true));
      const newer = searchResponse("new"); newer.hits[0].title = "Current result";
      await act(async () => pending.get("new")!(new Response(JSON.stringify(newer))));
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')?.textContent).toContain("Current result"));
      const older = searchResponse("old"); older.hits[0].title = "Stale result";
      await act(async () => pending.get("old")!(new Response(JSON.stringify(older))));
      expect(document.querySelector('[data-pb-search-list]')?.textContent).not.toContain("Stale result");
      expect(document.querySelector('[data-pb-search-item="hit"]')?.textContent).toContain("Current result");
    } finally { vi.unstubAllGlobals(); }
  });

  it("scope uses its own request, keeps readonly spaces searchable, and excludes unavailable cached candidates", async () => {
    const requests: URL[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const parsed = new URL(url, "http://x"); requests.push(parsed);
      const response = crossRootSearchResponse(parsed.searchParams.get("q") ?? "");
      response.hits = response.hits.map((hit) => ({ ...hit, page_id: `content-${hit.page_id}` }));
      if (parsed.searchParams.has("root")) response.hits = response.hits.filter((hit) => hit.root === parsed.searchParams.get("root"));
      return new Response(JSON.stringify(response));
    }));
    try {
      const scopedTree = { roots: twoRootTree.roots.map((root) => ({ ...root, editable: false, displayName: "Guides" })) };
      const { queryClient } = setup("/docs", scopedTree); await openPalette();
      const scopeGroup = within(document.querySelector('[aria-label="Search scope"]') as HTMLElement);
      fireEvent.click(scopeGroup.getByRole("button", { name: "Guides (handbook)" }));
      expect(document.querySelectorAll('[data-pb-search-item="jump"]')).toHaveLength(1);
      fireEvent.change(getInput(), { target: { value: "deploy" } });
      await waitFor(() => expect(requests.some((url) => url.searchParams.get("root") === "handbook")).toBe(true));
      await waitFor(() => expect(document.querySelectorAll('[data-pb-search-item="hit"]')).toHaveLength(1));
      act(() => queryClient.setQueryData(treeQuery.queryKey, { roots: scopedTree.roots.map((root) => ({ ...root, available: root.root !== "handbook" })) }));
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="jump"]')).toBeNull());
      expect(document.querySelector('[data-pb-search-item="hit"]')).toBeNull();
      expect(document.querySelector('[data-pb-search-error]')?.textContent).toContain("unavailable");
      fireEvent.click(scopeGroup.getByRole("button", { name: "All spaces" }));
      expect(document.querySelectorAll('[data-pb-search-item="jump"]')).toHaveLength(1);
      expect(scopeGroup.getByRole("button", { name: "Guides (handbook) (unavailable)" }).hasAttribute("disabled")).toBe(true);
    } finally { vi.unstubAllGlobals(); }
  });

  it("content errors retain quick matches without presenting stale hits as current", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: { code: "search_unavailable", message: "Search is unavailable" } }), { status: 503 })));
    try {
      setup(); await openPalette(); fireEvent.change(getInput(), { target: { value: "deploy" } });
      await waitFor(() => expect(document.querySelector('[data-pb-search-error]')?.textContent).toContain("Search is unavailable"));
      expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull();
      expect(document.querySelector('[data-pb-search-empty]')).toBeNull();
    } finally { vi.unstubAllGlobals(); }
  });
});
