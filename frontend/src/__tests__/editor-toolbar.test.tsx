import { EditorSelection } from "@codemirror/state";
import { EditorView, runScopeHandlers } from "@codemirror/view";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, treeQuery } from "../api/queries";
import { splitFrontmatter } from "../lib/frontmatter";
import type { PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };

/**
 * C3 formatting toolbar + keymap integration (acceptance #2/#3/#4/#5/#6). Mounts the `?mode=edit` editor,
 * drives the body CodeMirror via `EditorView.findFromDOM`, fires a keymap chord / clicks a toolbar button,
 * and asserts the buffer reflects the markdown. Mirrors `editor-metaform.test.tsx`'s harness, including the
 * HASH DISCIPLINE (`hashOf`/`pageResponse`) so a Save/PUT assertion is never silently disabled.
 */

const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";

// A page whose body's first line is `# Deploy Guide` then `body.` — the format ops act on the body slice.
const SEED = "---\nid: 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a\ntitle: Deploy Guide\nstatus: draft\n---\n\n# Deploy Guide\n\nbody.\n";

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
  return renderEditorAt("/docs/guides/deploy-guide?mode=edit", (qc) => {
    qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, pageResponse(markdown));
  });
}

/** Resolves the mounted body `EditorView`. */
async function bodyView(view: ReturnType<typeof render>): Promise<EditorView> {
  const dom = await waitFor(() => {
    const editor = view.container.querySelector<HTMLElement>("[data-pb-codemirror] .cm-editor");
    expect(editor).not.toBeNull();
    return editor!;
  });
  return EditorView.findFromDOM(dom)!;
}

/** Selects [from, to) in the body view (inside act so React flushes). */
async function select(cm: EditorView, from: number, to: number) {
  await act(async () => {
    cm.dispatch({ selection: EditorSelection.range(from, to) });
  });
}

/**
 * Drives a `Mod-`<key> chord through the registered `editor`-scope handlers — the SAME path CM's DOM
 * keydown listener takes (`@codemirror/view` `keydown` handler → `runScopeHandlers`), so it honors the
 * formatting keymap's prepend-precedence. `Mod` resolves to Cmd on mac, Ctrl elsewhere; jsdom reports a
 * non-mac platform, so the chord carries `ctrlKey`. (A jsdom-synthesized `fireEvent.keyDown` doesn't
 * carry the `keyCode` CM's key-name resolution needs; `runScopeHandlers` is the deterministic equivalent.)
 * `mod` defaults to `ctrlKey` — the only shape that resolves under jsdom; a `metaKey` chord would NOT fire
 * here (CM maps `Mod` to Ctrl off-mac), so the suite asserts the Ctrl shape. The param documents the chord.
 */
async function fireMod(cm: EditorView, key: string, mod: "metaKey" | "ctrlKey" = "ctrlKey") {
  await act(async () => {
    runScopeHandlers(cm, new KeyboardEvent("keydown", { key, keyCode: key.toUpperCase().charCodeAt(0), [mod]: true } as KeyboardEventInit), "editor");
  });
}

// Unmount synchronously so `useDebounced`'s 300ms effect cleanup (`clearTimeout`) runs before the timer
// can fire — otherwise a leaked debounce setState lands after jsdom teardown ("window is not defined").
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("C3 formatting toolbar + keymap", () => {
  it("renders the stable formatting-toolbar selectors", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();

    await waitFor(() => expect(view.container.querySelector("[data-pb-toolbar]")).not.toBeNull());
    for (const selector of [
      "[data-pb-fmt-heading]",
      "[data-pb-fmt-bold]",
      "[data-pb-fmt-italic]",
      "[data-pb-fmt-code]",
      "[data-pb-fmt-link]",
      "[data-pb-fmt-bullet]",
      "[data-pb-fmt-numbered]",
      "[data-pb-fmt-quote]",
      "[data-pb-fmt-codeblock]",
      "[data-pb-fmt-table]",
    ]) {
      expect(view.container.querySelector(selector), selector).not.toBeNull();
    }
    // The icon buttons carry an accessible label (no visible text label any more).
    expect(view.container.querySelector("[data-pb-fmt-bold]")?.getAttribute("aria-label")).toBe("Bold");
    // The ⌘S hint lives on the toolbar row, pushed to the far right.
    expect(view.container.querySelector("[data-pb-save-hint]")?.textContent).toContain("to save");
  });

  it("Mod-b on a body selection wraps it in ** and the buffer reflects it", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();
    const cm = await bodyView(view);

    // Select `body` on its line (the body slice begins at the first body char).
    const bodyText = cm.state.doc.toString();
    const from = bodyText.indexOf("body");
    await select(cm, from, from + "body".length);

    await fireMod(cm, "b");
    expect(cm.state.doc.toString()).toContain("**body**");

    // A second chord on a fresh selection — the binding fires again (Mod- resolves to Ctrl on this platform).
    const next = cm.state.doc.toString();
    const wfrom = next.indexOf("Deploy");
    await select(cm, wfrom, wfrom + "Deploy".length);
    await fireMod(cm, "b");
    expect(cm.state.doc.toString()).toContain("**Deploy**");
  });

  it("Mod-i wraps a selection in _ and does NOT trigger selectParentSyntax (the prepend-precedence guard)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();
    const cm = await bodyView(view);

    const bodyText = cm.state.doc.toString();
    const from = bodyText.indexOf("body");
    await select(cm, from, from + "body".length);

    await fireMod(cm, "i");
    // The doc gained `_body_` (toggleItalic ran) — NOT a parent-syntax selection (the default clobber).
    expect(cm.state.doc.toString()).toContain("_body_");
  });

  it("clicking the Bold button wraps the current body selection", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();
    const cm = await bodyView(view);

    const from = cm.state.doc.toString().indexOf("body");
    await select(cm, from, from + "body".length);

    const bold = await waitFor(() => {
      const el = view.container.querySelector<HTMLButtonElement>("[data-pb-fmt-bold]");
      expect(el).not.toBeNull();
      return el!;
    });
    await act(async () => {
      fireEvent.click(bold);
    });
    expect(cm.state.doc.toString()).toContain("**body**");
  });

  it("clicking Heading prefixes the current line with ## ", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();
    const cm = await bodyView(view);

    // Put the cursor on the `body.` line.
    const from = cm.state.doc.toString().indexOf("body.");
    await select(cm, from, from);

    const heading = view.container.querySelector<HTMLButtonElement>("[data-pb-fmt-heading]")!;
    await act(async () => {
      fireEvent.click(heading);
    });
    expect(cm.state.doc.toString()).toContain("## body.");
  });

  it("clicking Code block fences the current line in ``` ", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();
    const cm = await bodyView(view);

    // Select the `body.` line.
    const from = cm.state.doc.toString().indexOf("body.");
    await select(cm, from, from + "body.".length);

    const codeblock = view.container.querySelector<HTMLButtonElement>("[data-pb-fmt-codeblock]")!;
    await act(async () => {
      fireEvent.click(codeblock);
    });
    expect(cm.state.doc.toString()).toContain("```\nbody.\n```");
  });

  it("clicking Insert table inserts a GFM table skeleton at the cursor", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "", headings: [] })));
    const { view } = renderSeeded();
    const cm = await bodyView(view);

    // Cursor at the end of the `body.` line (a non-blank line → the table goes in as its own block).
    const at = cm.state.doc.toString().indexOf("body.") + "body.".length;
    await select(cm, at, at);

    const table = view.container.querySelector<HTMLButtonElement>("[data-pb-fmt-table]")!;
    await act(async () => {
      fireEvent.click(table);
    });
    expect(cm.state.doc.toString()).toContain("| Column | Column |\n| --- | --- |\n| Cell | Cell |");
  });

  it("a toolbar format op changes only the body — the frontmatter region is byte-identical", async () => {
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
    const cm = await bodyView(view);

    const from = cm.state.doc.toString().indexOf("body");
    await select(cm, from, from + "body".length);
    await act(async () => {
      fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-fmt-bold]")!);
    });

    const save = await waitFor(() => {
      const btn = view.container.querySelector<HTMLButtonElement>("[data-pb-save]")!;
      expect(btn.disabled).toBe(false);
      return btn;
    });
    fireEvent.click(save);

    await waitFor(() => expect(putBody).not.toBeNull());
    expect(splitFrontmatter(putBody!).frontmatter).toBe(splitFrontmatter(SEED).frontmatter);
    expect(putBody!).toContain("**body**");
  });

  it("the toolbar is hidden while the preview overlay is open", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ html: "<p>rendered</p>", headings: [] })));
    const { view } = renderSeeded();

    const toggle = await waitFor(() => {
      const el = view.container.querySelector<HTMLButtonElement>("[data-pb-preview-toggle]");
      expect(el).not.toBeNull();
      return el!;
    });
    expect(view.container.querySelector("[data-pb-toolbar]")).not.toBeNull();

    fireEvent.click(toggle);
    await waitFor(() => expect(view.container.querySelector("[data-pb-preview]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-toolbar]")).toBeNull();

    fireEvent.click(toggle);
    await waitFor(() => expect(view.container.querySelector("[data-pb-preview]")).toBeNull());
    expect(view.container.querySelector("[data-pb-toolbar]")).not.toBeNull();
  });
});
