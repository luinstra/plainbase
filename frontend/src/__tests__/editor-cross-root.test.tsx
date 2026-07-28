import { EditorView } from "@codemirror/view";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, waitFor } from "@testing-library/react";
import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, treeQuery } from "../api/queries";
import type { PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

/**
 * Mount identity across roots. Two roots can hold the SAME page id (per-root identity), so a
 * client-side navigation from one root's copy to the other's is a navigation to a DIFFERENT page that
 * an id-only `key` does not remount. The editor then keeps root A's buffer and root A's `baseHash`
 * while its `root` prop has already flipped to B - and on a copied corpus the two files are
 * byte-identical, so the CAS check PASSES and B's page is overwritten with A's edit. Durable loss, not
 * a display bug.
 *
 * HASH DISCIPLINE (mandatory, the house rule from editor-metaform.test.tsx): Save is disabled unless
 * sha256(markdown) equals content_hash, so every seed here is hashed with `hashOf` or the asserted PUT
 * could never be issued at all.
 */

const DUP_ID = "0197c2d1-9f3b-7a4e-8d6c-1b5a7e9c3f21";
const MAIN_URL = "/docs/permalink/hub";
const EXTRA_URL = "/extra/permalink/hub";
const MAIN = "---\ntitle: Permalink Hub\n---\n\n# Permalink Hub\n\nMAIN BODY\n";
const EXTRA = MAIN.replace("MAIN BODY", "EXTRA BODY");
const rootUrl = (root: string) => (root === "docs" ? "/docs" : `/${root}`);

const twoRoots: TreeResponse = {
  roots: ["docs", "extra"].map((root) => ({
    root,
    available: true,
    editable: true,
    primary: root === "docs",
    tree: { type: "folder", name: "", title: null, description: null, path: "", url: rootUrl(root), page_count: 0, children: [] },
  })),
};

const hashOf = (text: string): string => `sha256:${createHash("sha256").update(text, "utf8").digest("hex")}`;

function pageResponse(root: string, url: string, markdown: string): PageResponse {
  const contentHash = hashOf(markdown);
  return {
    id: DUP_ID,
    root,
    path: "permalink/hub.md",
    slug: "hub",
    url,
    title: "Permalink Hub",
    markdown,
    frontmatter: {},
    content_hash: contentHash,
    id_materialized: true,
    commit: null,
    citation: { page_id: DUP_ID, heading_id: null, path: "permalink/hub.md", content_hash: contentHash, commit: null, uri: `plainbase://${root}/${DUP_ID}@${contentHash}` },
  };
}

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

/** Drives a real edit through the mounted CodeMirror: a navigation alone never makes the buffer dirty. */
async function appendToBody(view: ReturnType<typeof render>, text: string) {
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

describe("editor mount identity across roots", () => {
  it("remounts on a cross-root navigation to the same id, so a save cannot carry the primary root's buffer and CAS token into extra", async () => {
    let putUrl: string | null = null;
    let putHeaders: Record<string, string> = {};
    let saved = EXTRA;
    const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      if (init?.method === "PUT") {
        putUrl = url;
        putHeaders = init.headers as Record<string, string>;
        saved = init.body as string;
        return jsonResponse({ content_hash: hashOf(saved), commit: null });
      }
      if (url.includes("/preview")) return jsonResponse({ html: "", headings: [] });
      if (url.includes("/tree")) return jsonResponse(twoRoots);
      // The post-save invalidation refetch must get a valid PageResponse, or the query errors and the
      // editor unmounts with React work landing after jsdom teardown.
      return jsonResponse(pageResponse("extra", EXTRA_URL, saved));
    });
    vi.stubGlobal("fetch", fetchSpy);

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(treeQuery.queryKey, twoRoots);
    // BOTH by-path keys primed: an unprimed second key makes EditorPage render its Loading branch, which
    // unmounts the editor regardless of its key and would make this row pass for the wrong reason.
    queryClient.setQueryData(pageByPathQuery("docs/permalink/hub").queryKey, pageResponse("docs", MAIN_URL, MAIN));
    queryClient.setQueryData(pageByPathQuery("extra/permalink/hub").queryKey, pageResponse("extra", EXTRA_URL, EXTRA));
    const history = createMemoryHistory({ initialEntries: [`${MAIN_URL}?mode=edit`] });
    const router = createAppRouter(queryClient, history);
    const view = render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(view.container.querySelector("[data-pb-codemirror]")?.textContent).toContain("MAIN BODY"));

    // The cross-root hop: same id, different root.
    await act(async () => {
      history.push(`${EXTRA_URL}?mode=edit`);
    });
    await waitFor(() => expect(view.container.querySelector("[data-pb-codemirror]")?.textContent).toContain("EXTRA BODY"));
    expect(view.container.querySelector("[data-pb-codemirror]")?.textContent).not.toContain("MAIN BODY");

    await appendToBody(view, "\nedited.\n");
    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      // If Save never enables the seeds are not hash-disciplined and nothing below means anything.
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    await waitFor(() => expect(putUrl).not.toBeNull());
    // The CAS token is EXTRA's. Under an id-only key it would be the primary root's, against extra's disk, and on
    // byte-identical copies that CAS would pass.
    expect(putHeaders["if-match"]).toBe(`"${hashOf(EXTRA)}"`);
    // The `?root=extra` half is built from the live prop, so it does NOT observe mount identity (that
    // is the write row's job). It is asserted here only to name the disk this PUT lands on.
    expect(putUrl).toBe(`/api/v1/pages/${DUP_ID}?root=extra`);
    // Settle so the post-save invalidation refetch schedules no React work after teardown.
    await waitFor(() => expect(view.container.querySelector<HTMLButtonElement>("[data-pb-save]")?.disabled).toBe(true));
  });
});
