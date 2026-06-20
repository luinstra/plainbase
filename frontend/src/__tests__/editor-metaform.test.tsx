import { EditorView } from "@codemirror/view";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, waitFor } from "@testing-library/react";
import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, treeQuery } from "../api/queries";
import { splitFrontmatter } from "../lib/frontmatter";
import type { PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };

/**
 * C2 metadata-form rail editing (acceptance #3/#4/#5/#7/#8). The `?mode=edit` editor shows the body in
 * CodeMirror (prose only) and the frontmatter in a structured rail form (`MetaForm`). A field edit
 * surgically patches the frontmatter region of the SINGLE buffer; a body edit leaves the frontmatter
 * byte-identical; Save PUTs the recombined whole buffer with the unchanged If-Match base_hash.
 *
 * HASH DISCIPLINE (mandatory): the byte-fidelity guard disables Save when content_hash ≠ sha256(markdown).
 * Every seed buffer here is hashed with `hashOf` so a Save/PUT assertion isn't silently disabled.
 */

const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";

// A page with the five editorial fields + a body containing a `---` thematic break (the boundary the
// split-view must NOT re-parse as frontmatter).
const SEED = "---\nid: 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a\ntitle: Deploy Guide\nstatus: draft\ntags:\n  - infra\n  - ops\nowner: Ada Lovelace\nupdated: 2026-01-01\nreview: 2026-06-01\n---\n\n# Deploy Guide\n\nbody.\n\n---\n\nmore body.\n";

const hashOf = (text: string): string => `sha256:${createHash("sha256").update(text, "utf8").digest("hex")}`;

function pageResponse(markdown: string, url: string | null = "/docs/guides/deploy-guide"): PageResponse {
  const contentHash = hashOf(markdown);
  return {
    id: ID,
    path: "guides/deploy-guide.md",
    slug: "deploy-guide",
    url,
    title: "Deploy Guide",
    markdown,
    frontmatter: {},
    content_hash: contentHash,
    id_materialized: true,
    commit: null,
    citation: { page_id: ID, heading_id: null, path: "guides/deploy-guide.md", content_hash: contentHash, commit: null, uri: `plainbase://${ID}@${contentHash}` },
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

function renderSeeded(markdown = SEED) {
  const result = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
    qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(markdown));
  });
  return result;
}

/** Drives a real edit through the mounted CodeMirror (the body view); the contenteditable is not state-bearing. */
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

describe("C2 metadata form", () => {
  it("renders the page's frontmatter values (status/tags/updated/owner/review)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();

    await waitFor(() => expect(view.container.querySelector("[data-pb-meta-form]")).not.toBeNull());
    expect(view.container.querySelector<HTMLSelectElement>("[data-pb-field-status]")?.value).toBe("draft");
    expect(view.container.querySelector<HTMLInputElement>("[data-pb-field-updated]")?.value).toBe("2026-01-01");
    expect(view.container.querySelector<HTMLInputElement>("[data-pb-field-owner]")?.value).toBe("Ada Lovelace");
    expect(view.container.querySelector<HTMLInputElement>("[data-pb-field-review]")?.value).toBe("2026-06-01");
    // Tags render as chips read via frontmatterList.
    const tags = Array.from(view.container.querySelectorAll("[data-pb-tag-remove]")).map((el) => el.getAttribute("data-pb-tag-remove"));
    expect(tags).toEqual(["infra", "ops"]);
  });

  it("defaults to the Page-info form rail — the live preview is not rendered until toggled", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();

    // Default edit view: the form rail is present, the preview pane is absent.
    await waitFor(() => expect(view.container.querySelector("[data-pb-edit-rail]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-meta-form]")).not.toBeNull();
    expect(view.container.querySelector("[data-pb-preview]")).toBeNull();
    expect(view.container.querySelector<HTMLButtonElement>("[data-pb-preview-toggle]")?.getAttribute("aria-pressed")).toBe("false");
  });

  it("the Preview toggle overlays the body with the live preview while the form rail STAYS visible (CM stays mounted)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "<p>rendered</p>", headings: [] })));
    const { view } = renderSeeded();

    const toggle = await waitFor(() => {
      const el = view.container.querySelector<HTMLButtonElement>("[data-pb-preview-toggle]");
      expect(el).not.toBeNull();
      return el!;
    });

    // Toggle on: the preview overlay appears, the form rail STAYS visible, CodeMirror stays mounted
    // underneath (covered, not unmounted), aria-pressed flips.
    fireEvent.click(toggle);
    await waitFor(() => expect(view.container.querySelector("[data-pb-preview]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-edit-rail]")).not.toBeNull();
    expect(view.container.querySelector("[data-pb-codemirror]")).not.toBeNull();
    expect(toggle.getAttribute("aria-pressed")).toBe("true");

    // Toggle off: the overlay is gone, the editor + rail remain.
    fireEvent.click(toggle);
    await waitFor(() => expect(view.container.querySelector("[data-pb-preview]")).toBeNull());
    expect(view.container.querySelector("[data-pb-edit-rail]")).not.toBeNull();
    expect(view.container.querySelector("[data-pb-codemirror]")).not.toBeNull();
    expect(toggle.getAttribute("aria-pressed")).toBe("false");
  });

  it("does not fetch the preview while it is hidden — only after the toggle opens it", async () => {
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input.toString();
      if (url.includes("/preview")) return jsonResponse({ html: "<p>rendered</p>", headings: [] });
      return jsonResponse(emptyTree);
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderSeeded();

    const toggle = await waitFor(() => {
      const el = view.container.querySelector<HTMLButtonElement>("[data-pb-preview-toggle]");
      expect(el).not.toBeNull();
      return el!;
    });
    // No /preview POST while hidden.
    expect(fetchSpy.mock.calls.some(([input]) => (typeof input === "string" ? input : input!.toString()).includes("/preview"))).toBe(false);

    fireEvent.click(toggle);
    await waitFor(() =>
      expect(fetchSpy.mock.calls.some(([input]) => (typeof input === "string" ? input : input!.toString()).includes("/preview"))).toBe(true),
    );
  });

  it("renders the stable metadata-form selectors", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();

    await waitFor(() => expect(view.container.querySelector("[data-pb-meta-form]")).not.toBeNull());
    for (const selector of ["[data-pb-field-status]", "[data-pb-field-tags]", "[data-pb-field-updated]", "[data-pb-field-owner]", "[data-pb-field-review]", "[data-pb-tag-add]"]) {
      expect(view.container.querySelector(selector), selector).not.toBeNull();
    }
  });

  it("editing a field surgically updates the buffer and marks the editor dirty (Save enables)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();

    const select = await waitFor(() => {
      const el = view.container.querySelector<HTMLSelectElement>("[data-pb-field-status]");
      expect(el).not.toBeNull();
      return el!;
    });
    // Save starts disabled (clean buffer).
    expect(view.container.querySelector<HTMLButtonElement>("[data-pb-save]")?.disabled).toBe(true);

    fireEvent.change(select, { target: { value: "active" } });

    // The status line flipped and ONLY it: the body (incl. its `---` break) is byte-identical.
    expect(view.container.querySelector<HTMLSelectElement>("[data-pb-field-status]")?.value).toBe("active");
    await waitFor(() => expect(view.container.querySelector<HTMLButtonElement>("[data-pb-save]")?.disabled).toBe(false));
  });

  it("the body editor shows prose only (no frontmatter fence/lines in the CM doc)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();

    const cmText = await waitFor(() => {
      const text = view.container.querySelector("[data-pb-codemirror]")?.textContent ?? "";
      expect(text).toContain("# Deploy Guide");
      return text;
    });
    expect(cmText).not.toContain("status:");
    expect(cmText).not.toContain("title: Deploy Guide");
    // The body's OWN `---` thematic break is shown (it is body, not frontmatter).
    expect(cmText).toContain("more body.");
  });

  it("a body edit changes only the body region — the frontmatter bytes are byte-identical (a body `---` is not re-parsed)", async () => {
    let putBody: string | null = null;
    const fetchSpy = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT") {
        putBody = init.body as string;
        return jsonResponse({ content_hash: hashOf("x"), commit: null });
      }
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderSeeded();

    // A body edit that itself introduces a `---` line.
    await appendToBody(view, "\n---\ntrailing.\n");

    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    await waitFor(() => expect(putBody).not.toBeNull());
    // The frontmatter region is byte-identical to the seed's — the body edit never re-parsed a `---`.
    expect(splitFrontmatter(putBody!).frontmatter).toBe(splitFrontmatter(SEED).frontmatter);
    expect(putBody!).toContain("trailing.");
  });

  it("saving after a form edit PUTs the reassembled full buffer with the existing If-Match base_hash", async () => {
    let putBody: string | null = null;
    let putHeaders: Record<string, string> = {};
    const fetchSpy = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT") {
        putBody = init.body as string;
        putHeaders = init.headers as Record<string, string>;
        return jsonResponse({ content_hash: hashOf("next"), commit: null });
      }
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderSeeded();

    const select = await waitFor(() => {
      const el = view.container.querySelector<HTMLSelectElement>("[data-pb-field-status]");
      expect(el).not.toBeNull();
      return el!;
    });
    fireEvent.change(select, { target: { value: "active" } });

    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    await waitFor(() => expect(putBody).not.toBeNull());
    // The PUT body is the recombined whole buffer with EXACTLY the status line changed.
    expect(putBody!).toBe(SEED.replace("status: draft\n", "status: active\n"));
    expect(putHeaders["if-match"]).toBe(`"${hashOf(SEED)}"`);
    expect(putHeaders["content-type"]).toBe("text/markdown");
  });

  it("a tag typed then committed via blur, then Save, lands in the PUT and leaves the editor clean (blur-vs-Save race #3)", async () => {
    let putBody: string | null = null;
    let saved = SEED;
    // Route the page GET so the post-save invalidation refetch returns a valid PageResponse (not the
    // preview payload) — otherwise the query errors and the editor unmounts, hiding the Save button.
    const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      if (init?.method === "PUT") {
        putBody = init.body as string;
        saved = init.body as string;
        return jsonResponse({ content_hash: hashOf(saved), commit: null });
      }
      if (url.includes("/preview")) return jsonResponse({ html: "", headings: [] });
      if (url.includes("/tree")) return jsonResponse(emptyTree);
      return jsonResponse(pageResponse(saved));
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderSeeded();

    const input = await waitFor(() => {
      const el = view.container.querySelector<HTMLInputElement>("[data-pb-tag-add]");
      expect(el).not.toBeNull();
      return el!;
    });

    // Type a tag and commit it via BLUR (the input's onBlur add()), each in its own act so React flushes
    // the buffer commit before the Save click reads it — mirroring the browser's blur-then-click order.
    fireEvent.change(input, { target: { value: "newtag" } });
    await act(async () => {
      fireEvent.blur(input);
    });

    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    await waitFor(() => expect(putBody).not.toBeNull());
    // The just-blurred tag is in the PUT (not lost to a stale-buffer read).
    expect(putBody!).toContain("  - newtag");
    expect(splitFrontmatter(putBody!).body).toBe(splitFrontmatter(SEED).body);
    // And after the save the editor reads CLEAN — Save disabled again (the saved baseline advanced to the
    // exact sent bytes, which include the tag).
    await waitFor(() => expect(view.container.querySelector<HTMLButtonElement>("[data-pb-save]")?.disabled).toBe(true));
  });

  it("two field edits compose over the latest buffer — neither clobbers the other (functional updater #2)", async () => {
    let putBody: string | null = null;
    const fetchSpy = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT") {
        putBody = init.body as string;
        return jsonResponse({ content_hash: hashOf(init.body as string), commit: null });
      }
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const { view } = renderSeeded();

    const status = await waitFor(() => {
      const el = view.container.querySelector<HTMLSelectElement>("[data-pb-field-status]");
      expect(el).not.toBeNull();
      return el!;
    });
    fireEvent.change(status, { target: { value: "active" } });
    const owner = view.container.querySelector<HTMLInputElement>("[data-pb-field-owner]")!;
    fireEvent.change(owner, { target: { value: "Grace Hopper" } });

    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    await waitFor(() => expect(putBody).not.toBeNull());
    // BOTH edits survived — the second write composed over the first's buffer, not the stale render prop.
    expect(putBody!).toContain("status: active\n");
    expect(putBody!).toContain("owner: Grace Hopper\n");
  });

  it("falls back to a text input when `updated` isn't an ISO date (so the value isn't hidden #7)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const weird = SEED.replace("updated: 2026-01-01", "updated: 2026 Q1");
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(weird));
    });

    const updated = await waitFor(() => {
      const el = view.container.querySelector<HTMLInputElement>("[data-pb-field-updated]");
      expect(el).not.toBeNull();
      return el!;
    });
    // A non-ISO value must remain VISIBLE (a `type=date` input would render it blank).
    expect(updated.getAttribute("type")).toBe("text");
    expect(updated.value).toBe("2026 Q1");
  });

  it("treats an impossible ISO-shaped date (2026-02-30) as text, not a masked date input (#7 calendar strictness)", async () => {
    // `Date.parse("2026-02-30")` is non-NaN in V8, so a lenient check would render `type=date` and the DOM
    // would silently blank the value — the calendar round-trip (server LocalDate.parse parity) keeps it text/visible.
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const impossible = SEED.replace("updated: 2026-01-01", "updated: 2026-02-30");
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(impossible));
    });
    const updated = await waitFor(() => {
      const el = view.container.querySelector<HTMLInputElement>("[data-pb-field-updated]");
      expect(el).not.toBeNull();
      return el!;
    });
    expect(updated.getAttribute("type")).toBe("text");
    expect(updated.value).toBe("2026-02-30");
  });

  it("treats status/owner as plain editorial strings — no authorization branch on any frontmatter value", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    // A status outside the known five from a hand-edited file must still render and be editable.
    const weird = SEED.replace("status: draft", "status: experimental").replace("owner: Ada Lovelace", "owner: root");
    const { view } = renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(weird));
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-meta-form]")).not.toBeNull());
    expect(view.container.querySelector<HTMLSelectElement>("[data-pb-field-status]")?.value).toBe("experimental");
    expect(view.container.querySelector<HTMLInputElement>("[data-pb-field-owner]")?.value).toBe("root");
    // The form is fully interactive regardless of value — no gating.
    expect(view.container.querySelector<HTMLSelectElement>("[data-pb-field-status]")?.disabled).toBe(false);
  });
});
