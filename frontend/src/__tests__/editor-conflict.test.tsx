import { EditorView } from "@codemirror/view";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, treeQuery } from "../api/queries";
import type { PageHtmlResponse, PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };

/**
 * W6 conflict UX (D-5 acceptance #2, #3). Core principle: a 409 NEVER discards the user's buffer.
 * content_changed keeps the buffer + shows the server's current_content; page_moved updates the local
 * path; page_deleted offers "save as new" prefilled; 422 surfaces the refusal and stays dirty; and the
 * client never re-derives the hash — base_hash always comes from the server's current_hash.
 */

const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
// The seeded page's content_hash must be the EXACT sha256(utf8(BUFFER)) or the W6 byte-fidelity guard
// trips and disables Save (the read path serves markdown + content_hash off the same on-disk bytes).
const HASH = "sha256:80a06ff002fc5aa30c7ac1744318c638e34f6d1594e4e303c26bfaa39e6d978b";
const CURRENT_HASH = "sha256:2222222222222222222222222222222222222222222222222222222222222222";
const BUFFER = "---\nid: 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a\ntitle: Deploy Guide\n---\n\n# Deploy Guide\n\nmy edits.\n";

function pageResponse(): PageResponse {
  return {
    id: ID,
    path: "guides/deploy-guide.md",
    slug: "deploy-guide",
    url: "/docs/guides/deploy-guide",
    title: "Deploy Guide",
    markdown: BUFFER,
    frontmatter: {},
    content_hash: HASH,
    id_materialized: true,
    commit: null,
    citation: { page_id: ID, heading_id: null, path: "guides/deploy-guide.md", content_hash: HASH, commit: null, uri: `plainbase://${ID}@${HASH}` },
  };
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function renderEditor(putResponses: Response[], postResponse?: Response, prime: (qc: QueryClient) => void = () => {}) {
  let putIndex = 0;
  const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    if (init?.method === "PUT") return putResponses[Math.min(putIndex++, putResponses.length - 1)].clone();
    // The create POST hits /api/v1/pages; the preview POST hits /api/v1/preview (returns {html, headings}).
    if (init?.method === "POST" && url.includes("/api/v1/pages") && postResponse) return postResponse.clone();
    return jsonResponse({ html: "", headings: [] });
  });
  vi.stubGlobal("fetch", fetchSpy);

  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  queryClient.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse());
  prime(queryClient);
  const history = createMemoryHistory({ initialEntries: ["/docs/guides/deploy-guide?mode=edit"] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { view, fetchSpy, queryClient, router };
}

/** Dirties the buffer through the real CodeMirror view (so Save enables) and clicks Save. */
async function editAndSave(view: ReturnType<typeof render>) {
  const dom = await waitFor(() => {
    const editor = view.container.querySelector<HTMLElement>("[data-pb-codemirror] .cm-editor");
    expect(editor).not.toBeNull();
    return editor!;
  });
  const cm = EditorView.findFromDOM(dom)!;
  await act(async () => {
    cm.dispatch({ changes: { from: cm.state.doc.length, insert: "x" } });
  });
  const save = await waitFor(() => {
    const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
    expect(btn.disabled).toBe(false);
    return btn;
  });
  fireEvent.click(save);
}

afterEach(() => vi.unstubAllGlobals());

describe("W6 conflict UX", () => {
  it("content_changed keeps the dirty buffer and shows the server current_content with no merge UI", async () => {
    const { view } = renderEditor([
      jsonResponse(
        { error: { code: "conflict", reason: "content_changed", message: "The page changed on disk since you loaded it.", current_content: "SERVER VERSION", current_hash: CURRENT_HASH, current_path: "guides/deploy-guide.md" } },
        409,
      ),
    ]);
    await editAndSave(view);

    const banner = await waitFor(() => {
      const el = view.container.querySelector('[data-pb-conflict][data-pb-conflict-reason="content_changed"]');
      expect(el).not.toBeNull();
      return el!;
    });
    expect(banner.querySelector("[data-pb-conflict-current]")?.textContent).toContain("SERVER VERSION");
    // The user's buffer is STILL present in the editor (never discarded).
    expect(view.container.querySelector("[data-pb-codemirror]")?.textContent).toContain("# Deploy Guide");
  });

  it("page_moved keeps the buffer and updates the local path to current_path", async () => {
    const { view } = renderEditor([
      jsonResponse(
        { error: { code: "conflict", reason: "page_moved", message: "The page moved on disk since you loaded it.", current_content: "MOVED CONTENT", current_hash: CURRENT_HASH, current_path: "guides/renamed.md" } },
        409,
      ),
    ]);
    await editAndSave(view);

    await waitFor(() => expect(view.container.querySelector('[data-pb-conflict][data-pb-conflict-reason="page_moved"]')).not.toBeNull());
    expect(view.container.querySelector("[data-pb-editor-path]")?.textContent).toBe("guides/renamed.md");
    expect(view.container.querySelector("[data-pb-codemirror]")?.textContent).toContain("# Deploy Guide");
  });

  it("page_deleted offers 'save as new page' prefilled with the buffer (no dead-end)", async () => {
    const { view } = renderEditor([
      jsonResponse({ error: { code: "conflict", reason: "page_deleted", message: "The page no longer exists on disk.", current_content: null, current_hash: null, current_path: null } }, 409),
    ]);
    await editAndSave(view);

    const banner = await waitFor(() => {
      const el = view.container.querySelector('[data-pb-conflict][data-pb-conflict-reason="page_deleted"]');
      expect(el).not.toBeNull();
      return el!;
    });
    expect(banner.querySelector("[data-pb-save-as-new]")).not.toBeNull();
    expect(view.container.querySelector("[data-pb-codemirror]")?.textContent).toContain("# Deploy Guide");
  });

  it("save-as-new sends the BODY ONLY (no stale frontmatter) and the title from the parsed frontmatter (FIX 1)", async () => {
    const NEW_ID = "01900000-0000-7000-8000-000000000099";
    const htmlResponse: PageHtmlResponse = {
      id: ID,
      path: "guides/deploy-guide.md",
      slug: "deploy-guide",
      url: "/docs/guides/deploy-guide",
      title: "Deploy Guide",
      html: "<h1>Deploy Guide</h1>",
      content_hash: HASH,
      commit: null,
      headings: [],
      citation: { page_id: ID, heading_id: null, path: "guides/deploy-guide.md", content_hash: HASH, commit: null, uri: `plainbase://${ID}@${HASH}` },
    };
    const { view, fetchSpy } = renderEditor(
      [jsonResponse({ error: { code: "conflict", reason: "page_deleted", message: "gone", current_content: null, current_hash: null, current_path: null } }, 409)],
      jsonResponse({ id: NEW_ID, url: "/docs/guides/deploy-guide", content_hash: HASH, commit: null }, 201),
      (qc) => qc.setQueryData(pageHtmlQuery(ID).queryKey, htmlResponse),
    );
    await editAndSave(view);

    const banner = await waitFor(() => {
      const el = view.container.querySelector('[data-pb-conflict][data-pb-conflict-reason="page_deleted"]');
      expect(el).not.toBeNull();
      return el!;
    });
    fireEvent.click(banner.querySelector<HTMLButtonElement>("[data-pb-save-as-new]")!);

    const postCall = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(
        ([input, init]) => (init as RequestInit | undefined)?.method === "POST" && (typeof input === "string" ? input : input.toString()).includes("/api/v1/pages"),
      );
      expect(call).toBeDefined();
      return call!;
    });
    const sent = JSON.parse((postCall[1] as RequestInit).body as string);
    // The body carries NO leading frontmatter block and NO stale id — the server prepends its own.
    expect(sent.body.startsWith("---")).toBe(false);
    expect(sent.body).not.toContain("id: 0197a3f2");
    expect(sent.body).toContain("# Deploy Guide");
    // The title is the user's (possibly-edited) frontmatter title, not the filename.
    expect(sent.title).toBe("Deploy Guide");
  });

  it("save-as-new invalidates the destination by-path cache BEFORE navigating (no stale deleted page, FIX 1)", async () => {
    const NEW_ID = "01900000-0000-7000-8000-000000000099";
    // save-as-new reuses the recovered /docs/... URL; its by-path entry would otherwise still point at the
    // deleted old id, so the read route would render the stale id. The invalidation must precede navigate.
    const DEST_URL = "/docs/guides/deploy-guide";
    const { view, queryClient, router } = renderEditor([
      jsonResponse({ error: { code: "conflict", reason: "page_deleted", message: "gone", current_content: null, current_hash: null, current_path: null } }, 409),
    ], jsonResponse({ id: NEW_ID, url: DEST_URL, content_hash: HASH, commit: null }, 201));
    await editAndSave(view);

    const banner = await waitFor(() => {
      const el = view.container.querySelector('[data-pb-conflict][data-pb-conflict-reason="page_deleted"]');
      expect(el).not.toBeNull();
      return el!;
    });

    // Record the call order: the destination by-path key must be invalidated BEFORE router.navigate fires.
    const order: string[] = [];
    const destKey = JSON.stringify(pageByPathQuery("guides/deploy-guide").queryKey);
    vi.spyOn(queryClient, "invalidateQueries").mockImplementation((filters?: { queryKey?: unknown }) => {
      if (JSON.stringify(filters?.queryKey) === destKey) order.push("invalidate-dest");
      return Promise.resolve();
    });
    vi.spyOn(router, "navigate").mockImplementation(async () => {
      order.push("navigate");
    });

    fireEvent.click(banner.querySelector<HTMLButtonElement>("[data-pb-save-as-new]")!);

    await waitFor(() => expect(order).toContain("navigate"));
    expect(order.indexOf("invalidate-dest")).toBeGreaterThanOrEqual(0);
    expect(order.indexOf("invalidate-dest")).toBeLessThan(order.indexOf("navigate"));
  });

  it("the client never re-derives the hash — base_hash always comes from the server", async () => {
    const { view, fetchSpy } = renderEditor([
      jsonResponse({ error: { code: "conflict", reason: "content_changed", message: "changed", current_content: "SERVER", current_hash: CURRENT_HASH, current_path: "guides/deploy-guide.md" } }, 409),
      jsonResponse({ content_hash: "sha256:3333333333333333333333333333333333333333333333333333333333333333", commit: null }),
    ]);
    await editAndSave(view);
    await waitFor(() => expect(view.container.querySelector('[data-pb-conflict-reason="content_changed"]')).not.toBeNull());

    // A deliberate re-save targets the NEW base — the server's current_hash, echoed verbatim.
    const save = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
    fireEvent.click(save);
    await waitFor(() => {
      const puts = fetchSpy.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT");
      expect(puts.length).toBe(2);
      expect((puts[1][1] as RequestInit).headers as Record<string, string>).toMatchObject({ "if-match": `"${CURRENT_HASH}"` });
    });
  });

  it("a 413 after a content_changed conflict clears the stale banner and shows the new error (FIX 2)", async () => {
    const { view } = renderEditor([
      jsonResponse(
        { error: { code: "conflict", reason: "content_changed", message: "The page changed on disk since you loaded it.", current_content: "SERVER VERSION", current_hash: CURRENT_HASH, current_path: "guides/deploy-guide.md" } },
        409,
      ),
      jsonResponse({ error: { code: "body_too_large", message: "too big", max_bytes: 1048576 } }, 413),
    ]);
    await editAndSave(view);

    // First save → the conflict banner.
    await waitFor(() => expect(view.container.querySelector('[data-pb-conflict-reason="content_changed"]')).not.toBeNull());

    // A second save now fails with a 413 — the stale conflict banner must be gone and the new error shown
    // (the notice is render-gated behind `!conflict`, so a stale banner would otherwise mask it).
    const save = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
    fireEvent.click(save);

    await waitFor(() => {
      expect(view.container.querySelector("[data-pb-editor-notice]")?.textContent).toContain("1048576");
      expect(view.container.querySelector("[data-pb-conflict]")).toBeNull();
    });
  });

  it("a tampered id surfaces the 422 id_change_unsupported refusal (not a silent save)", async () => {
    const { view } = renderEditor([
      jsonResponse({ error: { code: "id_change_unsupported", field: "id", message: "Changing id is not allowed — page identity is immutable." } }, 422),
    ]);
    await editAndSave(view);

    const refusal = await waitFor(() => {
      const el = view.container.querySelector('[data-pb-refusal][data-pb-refusal-field="id"]');
      expect(el).not.toBeNull();
      return el!;
    });
    expect(refusal.textContent).toContain("id");
    // The editor stays dirty/unsaved — the buffer survives.
    expect(view.container.querySelector("[data-pb-codemirror]")?.textContent).toContain("# Deploy Guide");
  });
});
