import { createHash } from "node:crypto";
import { undo, undoDepth } from "@codemirror/commands";
import { EditorView } from "@codemirror/view";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { pageByPathQuery, sessionQuery, treeQuery } from "../api/queries";
import type { PageResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
const URL = "/docs/guide";
const tree: TreeResponse = { roots: [{ root: "docs", primary: true, available: true, editable: true,
  tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } }] };
const hashOf = (text: string) => "sha256:" + createHash("sha256").update(text).digest("hex");
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
async function mount(buffer = "---\ntitle: Guide\nstatus: draft\n---\n# Guide\n\nBody.\n", put?: (init: RequestInit) => Promise<Response>) {
  const hash = hashOf(buffer);
  const record: PageResponse = { id: ID, root: "docs", path: "guide.md", slug: "guide", url: URL, title: "Guide", markdown: buffer,
    frontmatter: {}, content_hash: hash, id_materialized: true, commit: null,
    citation: { page_id: ID, heading_id: null, path: "guide.md", content_hash: hash, commit: null, uri: "plainbase://guide" } };
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(treeQuery.queryKey, tree);
  client.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  client.setQueryData(pageByPathQuery("docs/guide").queryKey, record);
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === "PUT") return put ? put(init) : json({ content_hash: hashOf(String(init.body)), commit: null });
    if (String(input).includes("/tree")) return json(tree);
    if (String(input).includes("/by-path/")) return json(record);
    return json({ html: "<p>Preview body</p>", headings: [] });
  });
  vi.stubGlobal("fetch", fetch);
  const history = createMemoryHistory({ initialEntries: [URL + "?mode=edit"] });
  const router = createAppRouter(client, history);
  const view = render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  const cm = await waitFor(() => {
    const node = view.container.querySelector<HTMLElement>(".cm-editor");
    expect(node).not.toBeNull(); return EditorView.findFromDOM(node!)!;
  });
  return { view, cm, fetch, hash };
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("has an explicit Write/Preview switch and keeps source state behind a disabled toolbar", async () => {
  const { view, cm } = await mount();
  expect(view.getByRole("button", { name: "Write" }).getAttribute("aria-pressed")).toBe("true");
  act(() => cm.dispatch({ changes: { from: cm.state.doc.length, insert: "Keep me" }, selection: { anchor: 3 } }));
  const depth = undoDepth(cm.state);
  fireEvent.click(view.getByRole("button", { name: "Preview" }));
  expect(view.getByRole("toolbar")).toBeTruthy();
  expect(view.getByRole("button", { name: "Bold" }).hasAttribute("disabled")).toBe(true);
  expect(cm.dom.closest("[inert]")).not.toBeNull();
  fireEvent.click(view.getByRole("button", { name: "Write" }));
  expect(EditorView.findFromDOM(cm.dom)).toBe(cm);
  expect(cm.state.selection.main.anchor).toBe(3);
  expect(undoDepth(cm.state)).toBe(depth);
  act(() => { undo(cm); });
  expect(cm.state.doc.toString()).not.toContain("Keep me");
});

it.each(["\r\n", "\r", "\r\nsecond\rthird\n"])("preserves original bytes through metadata editing and Discard for %j", async (eol) => {
  const original = `\ufeff---\r\ntitle: Guide\r\nstatus: draft\r\n---\r\n# Guide${eol}Body 😀.${eol}`;
  const { view, cm, fetch, hash } = await mount(original);
  const status = view.container.querySelector<HTMLSelectElement>("[data-pb-field-status]")!;
  act(() => cm.dispatch({ selection: { anchor: 2 } }));
  const depth = undoDepth(cm.state);
  fireEvent.change(status, { target: { value: "review" } });
  expect(cm.state.selection.main.anchor).toBe(2);
  expect(undoDepth(cm.state)).toBe(depth);
  fireEvent.click(view.container.querySelector("[data-pb-save]")!);
  await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true));
  const first = fetch.mock.calls.find(([, init]) => init?.method === "PUT")![1]!;
  const saved = original.replace("status: draft", "status: review");
  expect(first.body).toBe(saved);
  expect((first.headers as Record<string, string>)["if-match"]).toBe(`"${hash}"`);
  await waitFor(() => expect(view.container.querySelector<HTMLButtonElement>("[data-pb-save]")?.disabled).toBe(true));
  act(() => cm.dispatch({ changes: { from: cm.state.doc.length, insert: "Changed" } }));
  vi.stubGlobal("confirm", () => true);
  fireEvent.click(view.getByRole("button", { name: "Discard" }));
  expect(fetch.mock.calls.filter(([, init]) => init?.method === "PUT")).toHaveLength(1);
  fireEvent.change(view.container.querySelector("[data-pb-field-status]")!, { target: { value: "active" } });
  fireEvent.click(view.container.querySelector("[data-pb-save]")!);
  await waitFor(() => expect(fetch.mock.calls.filter(([, init]) => init?.method === "PUT")).toHaveLength(2));
  const second = fetch.mock.calls.filter(([, init]) => init?.method === "PUT")[1][1]!;
  expect(second.body).toBe(original.replace("status: draft", "status: active"));
  expect((second.headers as Record<string, string>)["if-match"]).toBe(`"${hashOf(saved)}"`);
});

it("uses one guarded shortcut save and keeps newer edits unsaved while a request settles", async () => {
  let complete!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { complete = resolve; });
  const { view, cm, fetch } = await mount(undefined, () => pending);
  expect(view.getByRole("status", { name: "Save state" }).textContent).toBe("Saved");
  act(() => cm.dispatch({ changes: { from: cm.state.doc.length, insert: "First" } }));
  expect(view.getByRole("status", { name: "Save state" }).textContent).toBe("Unsaved changes");
  cm.focus();
  fireEvent.keyDown(cm.contentDOM, { key: "s", ctrlKey: true });
  fireEvent.keyDown(cm.contentDOM, { key: "s", ctrlKey: true });
  await waitFor(() => expect(fetch.mock.calls.filter(([, init]) => init?.method === "PUT")).toHaveLength(1));
  expect(view.getByRole("status", { name: "Save state" }).textContent).toBe("Saving");
  expect(view.getByRole("button", { name: "Discard" }).hasAttribute("disabled")).toBe(true);
  act(() => cm.dispatch({ changes: { from: cm.state.doc.length, insert: "Second" } }));
  await act(async () => complete(json({ content_hash: hashOf("sent"), commit: null })));
  await waitFor(() => expect(view.getByRole("status", { name: "Save state" }).textContent).toBe("Unsaved changes"));
});

it("edits metadata from chips below the bar and flushes a pending tag through the save shortcut", async () => {
  const { view, cm, fetch } = await mount();
  expect(view.container.querySelector("[data-pb-edit-rail]")).toBeNull();
  const trigger = view.getByText("Tags", { selector: "summary span" });
  fireEvent.click(trigger);
  const input = view.getByRole("textbox", { name: "Add tag" });
  input.focus();
  fireEvent.change(input, { target: { value: "pending-tag" } });
  fireEvent.keyDown(input, { key: "s", metaKey: true });
  await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true));
  expect(String(fetch.mock.calls.find(([, init]) => init?.method === "PUT")![1]!.body)).toContain("pending-tag");
  expect(EditorView.findFromDOM(cm.dom)).toBe(cm);
});

it("automatically checks the current Write buffer and clears stale diagnostics before debounce", async () => {
  const { view, cm, fetch } = await mount("Text [lost](missing.md)\n");
  fetch.mockImplementation(async (input) => {
    if (String(input).includes("/preview")) return json({ html: '<p><span data-pb-link-error="not_found" data-pb-link-src="5-23" data-pb-link-target="missing.md">lost</span></p>', headings: [] });
    return json(tree);
  });
  act(() => cm.dispatch({ changes: { from: cm.state.doc.length, insert: " " } }));
  await waitFor(() => expect(view.getByRole("status", { name: "Link checks" }).textContent).toBe("1 line needs attention"));
  await waitFor(() => expect(view.getByRole("button", { name: "Broken link: missing.md" })).toBeTruthy());
  expect(view.container.querySelector("[data-pb-preview]")).toBeNull();
  act(() => cm.dispatch({ changes: { from: 0, to: cm.state.doc.length, insert: "No link" } }));
  expect(view.queryByRole("button", { name: "Broken link: missing.md" })).toBeNull();
  expect(view.getByRole("status", { name: "Link checks" }).textContent).toBe("Checking links…");
});

it("groups formatting controls and shows source line numbers", async () => {
  const { view } = await mount();
  for (const name of ["Block style", "Inline", "Lists", "Insert"]) expect(view.getByRole("group", { name })).toBeTruthy();
  expect(view.container.querySelector(".cm-lineNumbers")).not.toBeNull();
  expect(view.getByRole("button", { name: "Bold" }).title).toContain("⌘B");
});

it("restores the saved CAS token after a conflict and respects canceled Discard", async () => {
  let calls = 0;
  const original = "# Guide\nOriginal\n";
  const { view, cm, fetch, hash } = await mount(original, async () => ++calls === 1
    ? json({ error: { code: "conflict", reason: "content_changed", message: "Changed elsewhere", current_hash: hashOf("other"), current_content: "other", current_path: null } }, 409)
    : json({ content_hash: hashOf("saved"), commit: null }));
  act(() => cm.dispatch({ changes: { from: cm.state.doc.length, insert: "Unsaved" } }));
  fireEvent.click(view.container.querySelector("[data-pb-save]")!);
  await waitFor(() => expect(view.container.querySelector("[data-pb-conflict]")).not.toBeNull());
  vi.stubGlobal("confirm", () => false);
  fireEvent.click(view.getByRole("button", { name: "Discard" }));
  expect(cm.state.doc.toString()).toContain("Unsaved");
  vi.stubGlobal("confirm", () => true);
  fireEvent.click(view.getByRole("button", { name: "Discard" }));
  expect(cm.state.doc.toString()).toBe(original);
  expect(fetch.mock.calls.filter(([, init]) => init?.method === "PUT")).toHaveLength(1);
  act(() => cm.dispatch({ changes: { from: cm.state.doc.length, insert: "New edit" } }));
  fireEvent.click(view.container.querySelector("[data-pb-save]")!);
  await waitFor(() => expect(fetch.mock.calls.filter(([, init]) => init?.method === "PUT")).toHaveLength(2));
  const second = fetch.mock.calls.filter(([, init]) => init?.method === "PUT")[1][1]!;
  expect((second.headers as Record<string, string>)["if-match"]).toBe(`"${hash}"`);
});

it("flushes a pending tag before deciding whether Discard needs confirmation", async () => {
  const { view, fetch } = await mount();
  fireEvent.click(view.getByText("Tags", { selector: "summary span" }));
  const input = view.getByRole("textbox", { name: "Add tag" });
  input.focus();
  fireEvent.change(input, { target: { value: "draft-tag" } });
  const confirm = vi.fn(() => true);
  vi.stubGlobal("confirm", confirm);
  fireEvent.click(view.getByRole("button", { name: "Discard" }));
  expect(confirm).toHaveBeenCalledOnce();
  expect(view.getByRole("status", { name: "Save state" }).textContent).toBe("Saved");
  expect(view.container.querySelector<HTMLInputElement>("[data-pb-tag-add]")?.value).toBe("");
  expect(fetch.mock.calls.filter(([, init]) => init?.method === "PUT")).toHaveLength(0);
});

it("aborts superseded diagnostics, ignores late results and reports an unavailable check", async () => {
  const { view, cm, fetch } = await mount("[a](x)\n");
  let finish!: (response: Response) => void;
  let signal: AbortSignal | null | undefined;
  fetch.mockImplementation(async (input, init) => {
    if (!String(input).includes("/preview")) return json(tree);
    if (String(init?.body).includes("held")) {
      signal = init?.signal;
      return new Promise<Response>((resolve) => { finish = resolve; });
    }
    return json({ error: { code: "unavailable", message: "Unavailable" } }, 503);
  });
  act(() => cm.dispatch({ changes: { from: cm.state.doc.length, insert: "held" } }));
  await waitFor(() => expect(signal).toBeTruthy());
  act(() => cm.dispatch({ changes: { from: 0, to: cm.state.doc.length, insert: "New source" } }));
  await waitFor(() => expect(signal?.aborted).toBe(true));
  await act(async () => finish(json({ html: '<span data-pb-link-error="not_found" data-pb-link-src="0-6" data-pb-link-target="x">a</span>', headings: [] })));
  await waitFor(() => expect(view.getByRole("status", { name: "Link checks" }).textContent).toBe("Link checks unavailable"));
  expect(view.queryByRole("button", { name: "Broken link: x" })).toBeNull();
  fireEvent.click(view.getByRole("button", { name: "Preview" }));
  expect(view.container.querySelector("[data-pb-preview]")?.textContent).toContain("Preview unavailable");
});
