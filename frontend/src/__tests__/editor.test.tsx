import { EditorView } from "@codemirror/view";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, treeQuery } from "../api/queries";
import type { PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };

/**
 * W6 editor (D-1/D-4/D-5 acceptance #1, #1b, #5). The `?mode=edit` dispatch reaches `<EditorPage>`,
 * a debounced edit POSTs `/api/v1/preview`, a save PUTs `/api/v1/pages/{id}` with the buffer + the
 * `If-Match` base_hash, and the editor surfaces its stable selectors. The buffer is seeded from the
 * primed `pageByPathQuery` cache; only the preview/PUT calls hit the (mocked) network.
 */

const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
const BUFFER = "---\nid: 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a\ntitle: Deploy Guide\n---\n\n# Deploy Guide\n\nbody.\n";
// The byte-fidelity guard recomputes sha256(utf8(markdown)) and compares it to content_hash; for a
// valid page the read path serves both off the SAME bytes, so this is the EXACT hash of BUFFER. The
// fixture must carry it (a wrong hash would trip the guard and disable Save on a normal page).
const HASH = "sha256:5df17ea6dababd5ad54c0f365a1a1cbf02f304c48db492b8046f2c0d2341534e";

function pageResponse(url: string | null): PageResponse {
  return {
    id: ID,
    path: "guides/deploy-guide.md",
    slug: "deploy-guide",
    url,
    title: "Deploy Guide",
    markdown: BUFFER,
    frontmatter: {},
    content_hash: HASH,
    id_materialized: true,
    commit: null,
    citation: { page_id: ID, heading_id: null, path: "guides/deploy-guide.md", content_hash: HASH, commit: null, uri: `plainbase://${ID}@${HASH}` },
  };
}

function renderEditorAt(initialPath: string, prime: (qc: QueryClient) => void) {
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
  return { history, view, queryClient };
}

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

/** Drives a real edit through the mounted CodeMirror view (the contenteditable is not state-bearing). */
async function appendToEditor(view: ReturnType<typeof render>, text: string) {
  const dom = await waitFor(() => {
    const editor = view.container.querySelector<HTMLElement>("[data-pb-codemirror] .cm-editor");
    expect(editor).not.toBeNull();
    return editor!;
  });
  const cm = EditorView.findFromDOM(dom)!;
  await act(async () => {
    cm.dispatch({ changes: { from: cm.state.doc.length, insert: text } });
  });
}

afterEach(() => vi.unstubAllGlobals());

describe("W6 editor", () => {
  it("mounting /docs/<path>?mode=edit renders the editor seeded with the page markdown", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse("/docs/guides/deploy-guide"));
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-editor]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-codemirror]")?.textContent).toContain("# Deploy Guide");
  });

  it("renders the stable editor selectors", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse("/docs/guides/deploy-guide"));
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-editor]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-preview]")).not.toBeNull();
    expect(view.container.querySelector("[data-pb-save]")).not.toBeNull();
  });

  it("a debounced edit POSTs /api/v1/preview and renders the returned html in the preview pane", async () => {
    const fetchSpy = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      if (url.includes("/api/v1/preview")) return jsonResponse({ html: "<p>rendered preview</p>", headings: [] });
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse("/docs/guides/deploy-guide"));
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-editor]")).not.toBeNull());
    await waitFor(() => expect(view.container.querySelector("[data-pb-preview] .pb-prose")?.textContent).toContain("rendered preview"));
    const previewCall = fetchSpy.mock.calls.find(([input]) => (typeof input === "string" ? input : input.toString()).includes("/api/v1/preview"));
    expect(previewCall).toBeDefined();
    expect((previewCall![1] as RequestInit).headers).toMatchObject({ "content-type": "text/markdown" });
  });

  it("save PUTs /api/v1/pages/{id} with the buffer body and the If-Match base_hash", async () => {
    const nextHash = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    const fetchSpy = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT") return jsonResponse({ content_hash: nextHash, commit: null });
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse("/docs/guides/deploy-guide"));
    });

    await appendToEditor(view, "more.\n");

    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    const putCall = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PUT");
      expect(call).toBeDefined();
      return call!;
    });
    expect(typeof putCall[0] === "string" ? putCall[0] : putCall[0].toString()).toContain(`/api/v1/pages/${ID}`);
    const headers = (putCall[1] as RequestInit).headers as Record<string, string>;
    expect(headers["if-match"]).toBe(`"${HASH}"`);
    expect(headers["content-type"]).toBe("text/markdown");
  });

  it("a successful save invalidates the by-path URL-splat key, NOT the .md content path (FIX 2)", async () => {
    const nextHash = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    const fetchSpy = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT") return jsonResponse({ content_hash: nextHash, commit: null });
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view, queryClient } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse("/docs/guides/deploy-guide"));
    });
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    await appendToEditor(view, "more.\n");
    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    await waitFor(() => {
      const keys = invalidateSpy.mock.calls.map(([arg]) => JSON.stringify((arg as { queryKey: unknown }).queryKey));
      // The CORRECT key is the URL splat WITHOUT `.md` (matching pageByPathQuery), never the file path.
      expect(keys).toContain(JSON.stringify(pageByPathQuery("guides/deploy-guide").queryKey));
      expect(keys).not.toContain(JSON.stringify(pageByPathQuery("guides/deploy-guide.md").queryKey));
    });
  });

  it("a successful save invalidates the ['search'] namespace so stale full-text results don't linger (FIX 1)", async () => {
    const nextHash = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    const fetchSpy = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT") return jsonResponse({ content_hash: nextHash, commit: null });
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view, queryClient } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse("/docs/guides/deploy-guide"));
    });
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    await appendToEditor(view, "more.\n");
    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    await waitFor(() => {
      const keys = invalidateSpy.mock.calls.map(([arg]) => JSON.stringify((arg as { queryKey: unknown }).queryKey));
      // Full-text results go stale on any content edit — the whole ['search'] namespace is invalidated.
      expect(keys).toContain(JSON.stringify(["search"]));
    });
  });

  it("a successful save resets dirty (Save disables, no redundant PUT) until the buffer is edited again", async () => {
    const nextHash = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      if (init?.method === "PUT") return jsonResponse({ content_hash: nextHash, commit: null });
      // The save invalidates treeQuery + pageByPathQuery → both refetch; return valid bodies so the
      // re-render (Shell sidebar + the editor's own by-path query) doesn't crash on a garbage payload.
      if (url.includes("/api/v1/tree")) return jsonResponse(emptyTree);
      if (url.includes("/api/v1/pages/by-path/")) return jsonResponse(pageResponse("/docs/guides/deploy-guide"));
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse("/docs/guides/deploy-guide"));
    });

    const dom = await waitFor(() => {
      const editor = view.container.querySelector<HTMLElement>("[data-pb-codemirror] .cm-editor");
      expect(editor).not.toBeNull();
      return editor!;
    });
    const cm = EditorView.findFromDOM(dom)!;
    await act(async () => {
      cm.dispatch({ changes: { from: cm.state.doc.length, insert: "more.\n" } });
    });

    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    // After the 200 the dirty baseline advances to the saved buffer → Save re-disables (a disabled
    // button can't be re-clicked, so no redundant PUT for the already-saved buffer).
    await waitFor(() => expect(view.container.querySelector<HTMLButtonElement>("[data-pb-save]")?.disabled).toBe(true));
    expect(fetchSpy.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT").length).toBe(1);

    // A new edit re-enables Save (the buffer now differs from the freshly-saved baseline).
    await act(async () => {
      cm.dispatch({ changes: { from: cm.state.doc.length, insert: "again.\n" } });
    });
    await waitFor(() => expect(view.container.querySelector<HTMLButtonElement>("[data-pb-save]")?.disabled).toBe(false));
  });

  it("a non-UTF-8 page (markdown is a lossy U+FFFD decode) disables Save and shows the byte-fidelity banner", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    // A page whose on-disk bytes weren't valid UTF-8: the read path decoded them lossily (the U+FFFD
    // replacement char) into `markdown`, so sha256(utf8(markdown)) can't match the bytes' content_hash.
    const corrupt = pageResponse("/docs/guides/deploy-guide");
    corrupt.markdown = "# Deploy Guide\n\nbad byte: � here.\n"; // content_hash still the BUFFER's → mismatch
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, corrupt);
    });

    // The page is still readable (editor mounts, buffer seeded) — only Save is blocked.
    await waitFor(() => expect(view.container.querySelector("[data-pb-uneditable]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-codemirror]")?.textContent).toContain("# Deploy Guide");
    expect(view.container.querySelector<HTMLButtonElement>("[data-pb-save]")?.disabled).toBe(true);
  });

  it("a valid UTF-8 page (hash matches the markdown) shows NO banner and leaves Save enabled once dirty (no false positive)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    // pageResponse carries content_hash === sha256(utf8(BUFFER)) — the guard must NOT trip on a normal page.
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse("/docs/guides/deploy-guide"));
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-editor]")).not.toBeNull());
    // No banner, ever (let the async digest settle by also asserting Save enables after an edit).
    expect(view.container.querySelector("[data-pb-uneditable]")).toBeNull();
    await appendToEditor(view, "more.\n");
    await waitFor(() => expect(view.container.querySelector<HTMLButtonElement>("[data-pb-save]")?.disabled).toBe(false));
    expect(view.container.querySelector("[data-pb-uneditable]")).toBeNull();
  });

  it("an alias edit URL canonicalizes the path while preserving ?mode=edit", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const canonical = "/docs/guides/deploy-guide";
    const { history } = renderEditorAt("/docs/old/deployment?mode=edit", (qc) => {
      // The alias by-path response IS the canonical page (its `url` differs from the address).
      qc.setQueryData(pageByPathQuery("old/deployment").queryKey, pageResponse(canonical));
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(canonical));
    });

    // The read-side canonical redirect runs for ?mode=edit too (the editor lives under it); the
    // replace carries window.location.search, so ?mode=edit survives the path canonicalization.
    await waitFor(() => {
      expect(history.location.pathname).toBe(canonical);
      expect(history.location.search).toContain("mode=edit");
    });
  });
});
