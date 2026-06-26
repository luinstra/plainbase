import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, sessionQuery, treeQuery } from "../api/queries";
import type { CommitDto, PageResponse, TreeResponse } from "../api/types";
import { MAX_DIFF_RENDER_CHARS } from "../lib/unifiedDiff";
import { createAppRouter } from "../router";

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };

/**
 * W7 history UI. The AFFORDANCE tests render the READ view and assert ONLY on `PageResponse.commit`,
 * proving the read view fires NO `/history` subprocess (MF-1). The VIEW tests open `?mode=history` and
 * switch on `git_enabled`. Mirrors the W6 editor harness: a `retry:false` QueryClient, `treeQuery` +
 * `pageByPathQuery` primed, memory history, and a per-test `fetch` stub.
 */

const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
const PATH = "guides/deploy-guide";
const URL = "/docs/guides/deploy-guide";
const HASH = "sha256:5df17ea6dababd5ad54c0f365a1a1cbf02f304c48db492b8046f2c0d2341534e";

function pageResponse(commit: string | null): PageResponse {
  return {
    id: ID,
    path: "guides/deploy-guide.md",
    slug: "deploy-guide",
    url: URL,
    title: "Deploy Guide",
    markdown: "# Deploy Guide\n",
    frontmatter: { updated: "2026-01-01" },
    content_hash: HASH,
    id_materialized: true,
    commit,
    citation: { page_id: ID, heading_id: null, path: "guides/deploy-guide.md", content_hash: HASH, commit, uri: `plainbase://${ID}@${HASH}` },
  };
}

const NEWER: CommitDto = {
  sha: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  author_name: "Ada Lovelace",
  author_email: "ada@example.com",
  author_time: "2026-02-02T10:00:00Z",
  committer_name: "Ada Lovelace",
  committer_email: "ada@example.com",
  committer_time: "2026-02-02T10:00:00Z",
  message: "Newer change",
};
const OLDER: CommitDto = {
  sha: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  author_name: "Grace Hopper",
  author_email: "grace@example.com",
  author_time: "2026-01-01T10:00:00Z",
  committer_name: "Grace Hopper",
  committer_email: "grace@example.com",
  committer_time: "2026-01-01T10:00:00Z",
  message: "Older change",
};
const OLDEST: CommitDto = {
  sha: "cccccccccccccccccccccccccccccccccccccccc",
  author_name: "Alan Turing",
  author_email: "alan@example.com",
  author_time: "2025-12-01T10:00:00Z",
  committer_name: "Alan Turing",
  committer_email: "alan@example.com",
  committer_time: "2025-12-01T10:00:00Z",
  message: "Oldest change",
};

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

/** A minimal `PageHtmlResponse` so the READ view (PageContent → Breadcrumbs/Prose) renders without crashing. */
function htmlResponse() {
  return jsonResponse({ id: ID, path: "guides/deploy-guide.md", slug: "deploy-guide", url: URL, title: "Deploy Guide", html: "<h1>Deploy Guide</h1>", content_hash: HASH, commit: null, headings: [], citation: { page_id: ID, heading_id: null, path: "guides/deploy-guide.md", content_hash: HASH, commit: null, uri: `plainbase://${ID}@${HASH}` } });
}

function urlOf(input: RequestInfo | URL): string {
  return typeof input === "string" ? input : input.toString();
}

function renderAt(initialPath: string, prime: (qc: QueryClient) => void) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  // Prime the Shell's session read (unauthenticated) so it serves from cache — no extra /session fetch.
  queryClient.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
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

afterEach(() => vi.unstubAllGlobals());

describe("W7 history affordance (read view, MF-1)", () => {
  it("the history affordance is gated on commit, not a history fetch", async () => {
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => (urlOf(input).includes("/html") ? htmlResponse() : jsonResponse({ html: "", headings: [] })));
    vi.stubGlobal("fetch", fetchSpy);

    // commit:null → no affordance, and the read view NEVER fetches /history.
    const nullCommit = renderAt(URL, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(null)));
    await waitFor(() => expect(nullCommit.view.container.querySelector("[data-pb-docfoot]")).not.toBeNull());
    expect(nullCommit.view.container.querySelector("[data-pb-history-page]")).toBeNull();
    expect(fetchSpy.mock.calls.some(([input]) => urlOf(input).includes("/history"))).toBe(false);
    nullCommit.view.unmount();

    fetchSpy.mockClear();

    // commit:"<sha>" → the affordance appears, STILL no /history fetch on the read view.
    const withCommit = renderAt(URL, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));
    await waitFor(() => expect(withCommit.view.container.querySelector("[data-pb-history-page]")).not.toBeNull());
    expect(fetchSpy.mock.calls.some(([input]) => urlOf(input).includes("/history"))).toBe(false);
  });

  it("git-off renders no history affordance (commit:null) and the git-off view copy", async () => {
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      const url = urlOf(input);
      if (url.includes("/history")) return jsonResponse({ git_enabled: false, commits: [] });
      if (url.includes("/html")) return htmlResponse();
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);

    // Read view: a git-off page has commit:null → no affordance.
    const read = renderAt(URL, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(null)));
    await waitFor(() => expect(read.view.container.querySelector("[data-pb-docfoot]")).not.toBeNull());
    expect(read.view.container.querySelector("[data-pb-history-page]")).toBeNull();
    read.view.unmount();

    // Direct ?mode=history with git_enabled:false → the git-off copy, no list.
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(null)));
    await waitFor(() => expect(view.container.querySelector("[data-pb-history-disabled]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-commit]")).toBeNull();
  });
});

describe("W7 history view (?mode=history)", () => {
  it("renders the commit list newest-first", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        if (urlOf(input).includes("/history")) return jsonResponse({ git_enabled: true, commits: [NEWER, OLDER] });
        return jsonResponse({ html: "", headings: [] });
      }),
    );
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-commit]").length).toBe(2));
    const rows = view.container.querySelectorAll("[data-pb-commit]");
    // First DOM row = commits[0] = NEWER (display abbreviation in data-pb-commit-sha).
    expect(rows[0].getAttribute("data-pb-commit-sha")).toBe(NEWER.sha.slice(0, 7));
    expect(rows[1].getAttribute("data-pb-commit-sha")).toBe(OLDER.sha.slice(0, 7));
    // The raw ISO rides a dateTime attr (timezone-independent assertion, D-8).
    expect(view.container.querySelector("time")?.getAttribute("datetime")).toBe(NEWER.author_time);
  });

  it("selecting two commits fetches and renders the diff with the FULL shas", async () => {
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      const url = urlOf(input);
      if (url.includes("/history")) return jsonResponse({ git_enabled: true, commits: [NEWER, OLDER] });
      if (url.includes("/diff")) {
        return jsonResponse({
          git_enabled: true,
          from: OLDER.sha,
          to: NEWER.sha,
          path: "guides/deploy-guide.md",
          unified_diff: ["@@ -1 +1 @@", " context", "-removed", "+added"].join("\n"),
        });
      }
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-commit] button").length).toBe(2));
    const buttons = view.container.querySelectorAll<HTMLButtonElement>("[data-pb-commit] button");
    fireEvent.click(buttons[0]);
    fireEvent.click(buttons[1]);

    await waitFor(() => expect(view.container.querySelector("[data-pb-diff]")).not.toBeNull());
    const kinds = [...view.container.querySelectorAll("[data-pb-diff-line]")].map((el) => el.getAttribute("data-pb-diff-line"));
    expect(kinds).toEqual(["meta", "context", "del", "add"]);

    // The /diff URL carries the FULL shas (from = OLDER, to = NEWER) — never a truncated display form.
    const diffCall = fetchSpy.mock.calls.find(([input]) => urlOf(input).includes("/diff"));
    expect(diffCall).toBeDefined();
    const diffUrl = urlOf(diffCall![0]);
    expect(diffUrl).toContain(`from=${OLDER.sha}`);
    expect(diffUrl).toContain(`to=${NEWER.sha}`);
  });

  it("an oversized diff renders the too-large state, not megabytes of rows", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = urlOf(input);
        if (url.includes("/history")) return jsonResponse({ git_enabled: true, commits: [NEWER, OLDER] });
        if (url.includes("/diff")) {
          const huge = `+${"x".repeat(MAX_DIFF_RENDER_CHARS + 10)}`;
          return jsonResponse({ git_enabled: true, from: OLDER.sha, to: NEWER.sha, path: "guides/deploy-guide.md", unified_diff: huge });
        }
        return jsonResponse({ html: "", headings: [] });
      }),
    );
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-commit] button").length).toBe(2));
    const buttons = view.container.querySelectorAll<HTMLButtonElement>("[data-pb-commit] button");
    fireEvent.click(buttons[0]);
    fireEvent.click(buttons[1]);

    await waitFor(() => expect(view.container.querySelector("[data-pb-diff-too-large]")).not.toBeNull());
    expect(view.container.querySelectorAll("[data-pb-diff-line]").length).toBe(0);
  });

  it("switches the SAME Diff mount from an oversized diff to a normal one without a hook-order crash (MF-1)", async () => {
    // The size-cap lives INSIDE the memo so the hook count is stable across renders (Rules of Hooks). Drive the
    // same mounted Diff instance from a too-large pair to a normal pair; React would throw "rendered more hooks
    // than during the previous render" if the cap were an early return BEFORE useMemo.
    const huge = `+${"x".repeat(MAX_DIFF_RENDER_CHARS + 10)}`;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = urlOf(input);
        if (url.includes("/history")) return jsonResponse({ git_enabled: true, commits: [NEWER, OLDER, OLDEST] });
        if (url.includes("/diff")) {
          // The OLDEST↔NEWER pair (rows 0 & 2) is oversized; the OLDER↔NEWER pair (rows 0 & 1) is normal.
          const oversized = url.includes(`from=${OLDEST.sha}`);
          return jsonResponse({
            git_enabled: true,
            from: oversized ? OLDEST.sha : OLDER.sha,
            to: NEWER.sha,
            path: "guides/deploy-guide.md",
            unified_diff: oversized ? huge : ["@@ -1 +1 @@", " context", "-removed", "+added"].join("\n"),
          });
        }
        return jsonResponse({ html: "", headings: [] });
      }),
    );
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-commit] button").length).toBe(3));
    const buttons = view.container.querySelectorAll<HTMLButtonElement>("[data-pb-commit] button");

    // First: NEWER (row 0) + OLDEST (row 2) → oversized → the cap state, no rows.
    fireEvent.click(buttons[0]);
    fireEvent.click(buttons[2]);
    await waitFor(() => expect(view.container.querySelector("[data-pb-diff-too-large]")).not.toBeNull());
    expect(view.container.querySelectorAll("[data-pb-diff-line]").length).toBe(0);

    // Then, on the SAME mount: deselect OLDEST, select OLDER (row 1) → NEWER + OLDER → a normal diff renders.
    fireEvent.click(buttons[2]);
    fireEvent.click(buttons[1]);
    await waitFor(() => expect(view.container.querySelector("[data-pb-diff-too-large]")).toBeNull());
    await waitFor(() => {
      const kinds = [...view.container.querySelectorAll("[data-pb-diff-line]")].map((el) => el.getAttribute("data-pb-diff-line"));
      expect(kinds).toEqual(["meta", "context", "del", "add"]);
    });
  });

  it("highlights diff code for a known extension and renders escaped plaintext for an unknown one", async () => {
    function stubDiff(path: string) {
      return vi.fn(async (input: RequestInfo | URL) => {
        const url = urlOf(input);
        if (url.includes("/history")) return jsonResponse({ git_enabled: true, commits: [NEWER, OLDER] });
        if (url.includes("/diff")) {
          return jsonResponse({
            git_enabled: true,
            from: OLDER.sha,
            to: NEWER.sha,
            path,
            unified_diff: ["@@ -1 +1 @@", "+const answer = 42;"].join("\n"),
          });
        }
        return jsonResponse({ html: "", headings: [] });
      });
    }

    // Known extension (.ts) → hljs token spans.
    vi.stubGlobal("fetch", stubDiff("src/answer.ts"));
    const known = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));
    let buttons = await waitFor(() => {
      const b = known.view.container.querySelectorAll<HTMLButtonElement>("[data-pb-commit] button");
      expect(b.length).toBe(2);
      return b;
    });
    fireEvent.click(buttons[0]);
    fireEvent.click(buttons[1]);
    await waitFor(() => expect(known.view.container.querySelector("[data-pb-diff]")).not.toBeNull());
    await waitFor(() => expect(known.view.container.querySelector("[data-pb-diff] .hljs-keyword, [data-pb-diff] .hljs-number")).not.toBeNull());
    known.view.unmount();

    // Unknown extension → no hljs token spans (escaped plaintext, no rainbow).
    vi.stubGlobal("fetch", stubDiff("notes/answer.weirdext"));
    const unknown = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));
    buttons = await waitFor(() => {
      const b = unknown.view.container.querySelectorAll<HTMLButtonElement>("[data-pb-commit] button");
      expect(b.length).toBe(2);
      return b;
    });
    fireEvent.click(buttons[0]);
    fireEvent.click(buttons[1]);
    await waitFor(() => expect(unknown.view.container.querySelector("[data-pb-diff]")).not.toBeNull());
    expect(unknown.view.container.querySelector("[data-pb-diff] [class*='hljs-']")).toBeNull();
  });

  it("git-on with no commits shows the empty state but no affordance", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = urlOf(input);
        if (url.includes("/history")) return jsonResponse({ git_enabled: true, commits: [] });
        if (url.includes("/html")) return htmlResponse();
        return jsonResponse({ html: "", headings: [] });
      }),
    );
    // VIEW: git_enabled:true + empty commits → the empty state.
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(null)));
    await waitFor(() => expect(view.container.querySelector("[data-pb-history-empty]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-commit]")).toBeNull();
    view.unmount();

    // READ view of the SAME zero-commit page (commit:null) → no affordance (MF-1 behavior change).
    const read = renderAt(URL, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(null)));
    await waitFor(() => expect(read.view.container.querySelector("[data-pb-docfoot]")).not.toBeNull());
    expect(read.view.container.querySelector("[data-pb-history-page]")).toBeNull();
  });

  it("a single commit renders the row but no diff", async () => {
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      if (urlOf(input).includes("/history")) return jsonResponse({ git_enabled: true, commits: [NEWER] });
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-commit]").length).toBe(1));
    expect(view.container.querySelector("[data-pb-history-empty]")).toBeNull();
    expect(view.container.querySelector("[data-pb-diff-hint]")?.textContent).toContain("Select two commits");
    // Even after clicking the one commit, no /diff fires (only one pick possible).
    fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-commit] button")!);
    expect(fetchSpy.mock.calls.some(([input]) => urlOf(input).includes("/diff"))).toBe(false);
  });

  it("a diff 404 re-fetches the commit list, no error page", async () => {
    let historyCalls = 0;
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      const url = urlOf(input);
      if (url.includes("/history")) {
        historyCalls += 1;
        return jsonResponse({ git_enabled: true, commits: [NEWER, OLDER] });
      }
      if (url.includes("/diff")) return jsonResponse({ error: { code: "not_found", message: "No diff between …" } }, 404);
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-commit] button").length).toBe(2));
    const callsBeforeDiff = historyCalls;
    const buttons = view.container.querySelectorAll<HTMLButtonElement>("[data-pb-commit] button");
    fireEvent.click(buttons[0]);
    fireEvent.click(buttons[1]);

    // The transient notice shows, the selection clears, and the commit list refetched — NOT a hard error.
    await waitFor(() => expect(view.container.querySelector("[data-pb-history-notice]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-error]")).toBeNull();
    expect(view.container.querySelector("[data-pb-not-found]")).toBeNull();
    await waitFor(() => expect(historyCalls).toBeGreaterThan(callsBeforeDiff));
    // Selection cleared → back to the "select two" hint, no diff rendered.
    expect(view.container.querySelector("[data-pb-diff]")).toBeNull();
  });

  it("a non-404 diff error renders the error notice, not a perpetual loading state", async () => {
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      const url = urlOf(input);
      if (url.includes("/history")) return jsonResponse({ git_enabled: true, commits: [NEWER, OLDER] });
      if (url.includes("/diff")) return jsonResponse({ error: { code: "internal", message: "git blew up" } }, 500);
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-commit] button").length).toBe(2));
    const buttons = view.container.querySelectorAll<HTMLButtonElement>("[data-pb-commit] button");
    fireEvent.click(buttons[0]);
    fireEvent.click(buttons[1]);

    // The generic diff-error notice appears; we do NOT hang on "Loading diff…", and it's not a 404 (no list refetch / notice).
    await waitFor(() => expect(view.container.querySelector("[data-pb-diff-error]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-loading]")).toBeNull();
    expect(view.container.querySelector("[data-pb-history-notice]")).toBeNull();
    expect(view.container.querySelector("[data-pb-diff]")).toBeNull();
  });

  it("an added diff row exposes an Added/Removed label to assistive tech", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = urlOf(input);
        if (url.includes("/history")) return jsonResponse({ git_enabled: true, commits: [NEWER, OLDER] });
        if (url.includes("/diff")) {
          return jsonResponse({
            git_enabled: true,
            from: OLDER.sha,
            to: NEWER.sha,
            path: "guides/deploy-guide.md",
            unified_diff: ["@@ -1 +1 @@", " ctx", "-removed", "+added"].join("\n"),
          });
        }
        return jsonResponse({ html: "", headings: [] });
      }),
    );
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));

    await waitFor(() => expect(view.container.querySelectorAll("[data-pb-commit] button").length).toBe(2));
    const buttons = view.container.querySelectorAll<HTMLButtonElement>("[data-pb-commit] button");
    fireEvent.click(buttons[0]);
    fireEvent.click(buttons[1]);

    await waitFor(() => expect(view.container.querySelector("[data-pb-diff]")).not.toBeNull());
    const addRow = view.container.querySelector('[data-pb-diff-line="add"]');
    const delRow = view.container.querySelector('[data-pb-diff-line="del"]');
    const ctxRow = view.container.querySelector('[data-pb-diff-line="context"]');
    // The +/- gutter is aria-hidden, so a visually-hidden sr-only span carries the meaning to AT.
    expect(addRow?.querySelector(".sr-only")?.textContent).toContain("Added");
    expect(delRow?.querySelector(".sr-only")?.textContent).toContain("Removed");
    // Context rows carry no label (no add/remove semantics to announce).
    expect(ctxRow?.querySelector(".sr-only")).toBeNull();
  });

  it("shows the truncated hint at exactly the history limit, and not below it", async () => {
    function commitsOf(n: number): CommitDto[] {
      return Array.from({ length: n }, (_, i) => ({ ...NEWER, sha: `${i}`.padStart(40, "0"), message: `commit ${i}` }));
    }
    function stub(n: number) {
      return vi.fn(async (input: RequestInfo | URL) => {
        if (urlOf(input).includes("/history")) return jsonResponse({ git_enabled: true, commits: commitsOf(n) });
        return jsonResponse({ html: "", headings: [] });
      });
    }

    // Exactly 100 (the server's DEFAULT_HISTORY_LIMIT) → the hint renders.
    vi.stubGlobal("fetch", stub(100));
    const full = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));
    await waitFor(() => expect(full.view.container.querySelectorAll("[data-pb-commit]").length).toBe(100));
    expect(full.view.container.querySelector("[data-pb-history-truncated]")).not.toBeNull();
    full.view.unmount();

    // 99 (below the limit) → no hint.
    vi.stubGlobal("fetch", stub(99));
    const partial = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));
    await waitFor(() => expect(partial.view.container.querySelectorAll("[data-pb-commit]").length).toBe(99));
    expect(partial.view.container.querySelector("[data-pb-history-truncated]")).toBeNull();
  });

  it("renders the stable history selectors", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        if (urlOf(input).includes("/history")) return jsonResponse({ git_enabled: true, commits: [NEWER, OLDER] });
        return jsonResponse({ html: "", headings: [] });
      }),
    );
    const { view } = renderAt(`${URL}?mode=history`, (qc) => qc.setQueryData(pageByPathQuery(PATH).queryKey, pageResponse(NEWER.sha)));

    await waitFor(() => expect(view.container.querySelector("[data-pb-commit]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-history]")).not.toBeNull();
    expect(view.container.querySelector("[data-pb-history-back]")).not.toBeNull();
  });
});
