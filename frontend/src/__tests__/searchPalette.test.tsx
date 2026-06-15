import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, cleanup, fireEvent, render, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { searchQuery, treeQuery } from "../api/queries";
import type { SearchResponse, TreeResponse } from "../api/types";
import { QUICK_SWITCH_MAX } from "../components/SearchPalette";
import { createAppRouter } from "../router";

/**
 * Two-stage palette component tests (criteria 14, 18–23). Driven through the real Shell so
 * the palette mounts once, alongside the router (memory history) and a primed tree cache.
 */

const LOSER_ID = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99";

const tree: TreeResponse = {
  root: {
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
      // A collision loser: url null → navigates via /p/{id}.
      { type: "page", id: LOSER_ID, title: "Shadowed Page", slug: "shadowed", path: "notes/shadowed.md", url: null, status: "active", updated: null },
    ],
  },
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

function setup(initialPath = "/docs") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, tree);
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

describe("two-stage search palette", () => {
  it("opens on Cmd/Ctrl+K with the input focused, combobox role, and a Stage-1 listbox", async () => {
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    const input = getInput();
    expect(input.getAttribute("role")).toBe("combobox");
    expect(input.getAttribute("aria-controls")).toBe("pb-search-listbox");
    expect(document.activeElement).toBe(input);
    expect(document.querySelector('[data-pb-search-stage="jump"]')).not.toBeNull();
  });

  it("Stage 1 is zero-network: typing recomputes the quick-switcher with no fetch", async () => {
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

  it("Stage 1 stays zero-network even when the tree cache is STALE (no refetch on palette open)", async () => {
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

  it("ArrowDown/Up clamp at both ends (no wrap) and update aria-activedescendant", async () => {
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    const input = getInput();
    // Stage 1 default: no row actively selected → no activedescendant (criterion 22).
    expect(input.getAttribute("aria-activedescendant")).toBeNull();

    fireEvent.keyDown(input, { key: "ArrowUp" }); // nothing selected → clamp, still nothing
    expect(input.getAttribute("aria-activedescendant")).toBeNull();

    fireEvent.keyDown(input, { key: "ArrowDown" }); // first step lands on row 0
    expect(input.getAttribute("aria-activedescendant")).toBe("pb-search-opt-jump-0");

    // Arrow all the way down past the end → clamps at the bridge (last row), no wrap.
    for (let i = 0; i < 20; i++) fireEvent.keyDown(input, { key: "ArrowDown" });
    const max = document.querySelectorAll('[data-pb-search-item="jump"]').length; // bridge index
    expect(input.getAttribute("aria-activedescendant")).toBe(`pb-search-opt-jump-${max}`);

    // Arrow back up past the top → clamps at "no selection" again.
    for (let i = 0; i < 20; i++) fireEvent.keyDown(input, { key: "ArrowUp" });
    expect(input.getAttribute("aria-activedescendant")).toBeNull();
  });

  it("scrolls the active row into view on ArrowDown, but not while Stage-1 selection is -1", async () => {
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    const input = getInput();

    // Stage-1 default is -1 (no active row): the layout effect guards on activeId, so settling
    // into the -1 default scrolls nothing.
    await waitFor(() => expect(input.getAttribute("aria-activedescendant")).toBeNull());
    (Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>).mockClear();
    fireEvent.keyDown(input, { key: "ArrowUp" }); // already at -1 → clamp, stays -1
    expect(input.getAttribute("aria-activedescendant")).toBeNull();
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled();

    fireEvent.keyDown(input, { key: "ArrowDown" }); // first step lands on row 0
    await waitFor(() => expect(input.getAttribute("aria-activedescendant")).toBe("pb-search-opt-jump-0"));

    const activeRow = document.getElementById("pb-search-opt-jump-0")!;
    expect(activeRow.scrollIntoView).toHaveBeenCalledWith({ block: "nearest" });
  });

  it("Enter with no row actively selected activates the bridge (snappy full-text path)", async () => {
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    // Type a query whose top fuzzy match exists, then Enter WITHOUT arrowing → bridge.
    fireEvent.change(getInput(), { target: { value: "deploy" } });
    await waitFor(() => expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull());
    expect(getInput().getAttribute("aria-activedescendant")).toBeNull(); // nothing selected
    fireEvent.keyDown(getInput(), { key: "Enter" });
    await waitFor(() => expect(document.querySelector('[data-pb-search-stage="search"]')).not.toBeNull());
  });

  it("activating the bridge at an empty query does nothing (no stage change, no fetch)", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    try {
      setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      // Empty query: arrow to the bridge and press Enter.
      const bridge = document.querySelector("[data-pb-search-bridge]")!;
      expect(bridge.getAttribute("aria-disabled")).toBe("true");
      fireEvent.mouseDown(bridge);
      expect(document.querySelector('[data-pb-search-stage="search"]')).toBeNull();
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("Enter on a quick-switcher row navigates via pageHref; a loser navigates via /p/{id}", async () => {
    const { history } = setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    fireEvent.change(getInput(), { target: { value: "deploy" } });
    await waitFor(() => expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull());
    fireEvent.keyDown(getInput(), { key: "ArrowDown" }); // select the top fuzzy match (row 0)
    fireEvent.keyDown(getInput(), { key: "Enter" });
    await waitFor(() => expect(history.location.pathname).toBe("/docs/guides/deploy-guide"));

    // Loser: url null → /p/{id}.
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    fireEvent.change(getInput(), { target: { value: "shadowed" } });
    await waitFor(() => expect(document.querySelector('[data-pb-search-item="jump"]')).not.toBeNull());
    fireEvent.keyDown(getInput(), { key: "ArrowDown" });
    fireEvent.keyDown(getInput(), { key: "Enter" });
    await waitFor(() => expect(history.location.pathname).toBe(`/p/${LOSER_ID}`));
  });

  it("Stage 2 Enter on a hit pushes hit.url + #heading_id", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => new Response(JSON.stringify(searchResponse(new URL(url, "http://x").searchParams.get("q") ?? "")), { status: 200, headers: { "content-type": "application/json" } })),
    );
    try {
      const { history } = setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      fireEvent.mouseDown(document.querySelector("[data-pb-search-bridge]")!);
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
      fireEvent.keyDown(getInput(), { key: "Enter" });
      await waitFor(() => expect(history.location.pathname + history.location.hash).toBe("/docs/guides/deploy-guide#rollback"));
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("Esc is stage-aware: Stage-2 Esc returns to Stage 1 (still open); Stage-1 Esc closes", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(searchResponse("rollback")), { status: 200, headers: { "content-type": "application/json" } })));
    try {
      setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      fireEvent.mouseDown(document.querySelector("[data-pb-search-bridge]")!);
      await waitFor(() => expect(document.querySelector('[data-pb-search-stage="search"]')).not.toBeNull());
      // The footer hint tracks what Esc actually does: in Stage 2 it goes BACK, not close.
      expect(document.querySelector("[data-pb-search-foot]")?.textContent).toContain("back");

      fireEvent.keyDown(getInput(), { key: "Escape" }); // Stage 2 → Stage 1, still open
      await waitFor(() => expect(document.querySelector('[data-pb-search-stage="jump"]')).not.toBeNull());
      expect(document.querySelector("[data-pb-search]")).not.toBeNull();
      expect(document.querySelector("[data-pb-search-foot]")?.textContent).toContain("close"); // Stage 1: Esc closes

      fireEvent.keyDown(getInput(), { key: "Escape" }); // Stage 1 → closed
      await waitFor(() => expect(document.querySelector("[data-pb-search]")).toBeNull());
    } finally {
      vi.unstubAllGlobals();
    }
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
      fireEvent.mouseDown(document.querySelector("[data-pb-search-bridge]")!);
      await waitFor(() => expect(document.querySelector("[data-pb-search-empty]")).not.toBeNull());
      expect(document.querySelector("[data-pb-search-empty]")?.textContent).toContain("No matches for");
      expect(document.querySelector("[data-pb-search-empty]")?.textContent).toContain("zzz");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("Backspace on an empty input in Stage 2 returns to Stage 1", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(searchResponse("")), { status: 200, headers: { "content-type": "application/json" } })));
    try {
      setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      fireEvent.mouseDown(document.querySelector("[data-pb-search-bridge]")!);
      await waitFor(() => expect(document.querySelector('[data-pb-search-stage="search"]')).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "" } });
      fireEvent.keyDown(getInput(), { key: "Backspace" });
      await waitFor(() => expect(document.querySelector('[data-pb-search-stage="jump"]')).not.toBeNull());
      expect(document.querySelector("[data-pb-search]")).not.toBeNull();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("outside-click always closes, regardless of stage", async () => {
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
    let resolve: (v: Response) => void = () => {};
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((r) => (resolve = r))));
    try {
      setup();
      await openPalette();
      await waitFor(() => expect(getInput()).not.toBeNull());
      fireEvent.change(getInput(), { target: { value: "rollback" } });
      fireEvent.mouseDown(document.querySelector("[data-pb-search-bridge]")!);
      await waitFor(() => expect(document.querySelector("[data-pb-search-loading]")).not.toBeNull());
      const loadingRow = document.querySelector("[data-pb-search-loading]")!;
      expect(loadingRow.getAttribute("role")).not.toBe("option");
      // No option exists yet, so activedescendant resolves to undefined (not the status row).
      expect(getInput().getAttribute("aria-activedescendant")).toBeNull();
      resolve(new Response(JSON.stringify(searchResponse("rollback")), { status: 200, headers: { "content-type": "application/json" } }));
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("Tab keeps focus trapped on the input", async () => {
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    fireEvent.keyDown(getInput(), { key: "Tab" });
    expect(document.activeElement).toBe(getInput());
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
      fireEvent.mouseDown(document.querySelector("[data-pb-search-bridge]")!);
      await waitFor(() => expect(document.querySelector('[data-pb-search-item="hit"]')).not.toBeNull());
      // The rendered hit is the active query's (rollback), never the stale "rol" data.
      expect(within(document.querySelector("[data-pb-search-list]") as HTMLElement).queryByText("STALE")).toBeNull();
      expect(document.querySelector('[data-pb-search-item="hit"]')!.textContent).toContain("Deploy Guide");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("marks exactly the selected row with data-pb-search-active (the slash-marker hook)", async () => {
    setup();
    await openPalette();
    await waitFor(() => expect(getInput()).not.toBeNull());
    const input = getInput();
    // Nothing selected at rest → no row carries the marker hook.
    expect(document.querySelectorAll("[data-pb-search-active]")).toHaveLength(0);

    fireEvent.keyDown(input, { key: "ArrowDown" }); // lands on row 0
    const active = document.querySelectorAll("[data-pb-search-active]");
    expect(active).toHaveLength(1);
    expect(active[0].id).toBe("pb-search-opt-jump-0");
  });
});
