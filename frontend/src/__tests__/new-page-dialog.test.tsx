import { EditorView } from "@codemirror/view";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, sessionQuery, treeQuery } from "../api/queries";
import type { PageResponse, TreeResponse } from "../api/types";
import { creationUrlPreview } from "../components/NewPageDialog";
import { createAppRouter } from "../router";

const tree: TreeResponse = { roots: [{ root: "docs", primary: true, available: true, editable: true,
  tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [
    { type: "folder", name: "raw folder", title: "Guides", description: null, path: "raw folder", url: "/docs/handbook", page_count: 0, children: [] },
    { type: "folder", name: "collision", title: null, description: null, path: "collision", url: null, page_count: 0, children: [] },
  ] } }] };
const record: PageResponse = { id: "01900000-0000-7000-8000-000000000001", root: "docs", title: "Current", slug: "current",
  path: "raw folder/current.md", url: "/docs/handbook/current", markdown: "# Current\n\nOriginal body\n", frontmatter: {},
  content_hash: "sha256:" + "0".repeat(64), commit: null, id_materialized: true,
  citation: { page_id: "01900000-0000-7000-8000-000000000001", heading_id: null, path: "raw folder/current.md", content_hash: "sha256:" + "0".repeat(64), commit: null, uri: "plainbase://current" } };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
function mount(at = "/docs", options: { tree?: TreeResponse; delayed?: Promise<Response>; create?: Promise<Response> } = {}) {
  const seeded = options.tree ?? tree;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(treeQuery.queryKey, seeded);
  client.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  client.setQueryData(pageByPathQuery("docs/handbook/current").queryKey, record);
  const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url === "/api/v1/tree") return json(seeded);
    if (url === "/api/v1/pages/by-path/docs/alias") return options.delayed ?? json(record);
    if (init?.method === "POST" && url === "/api/v1/pages") return options.create ?? json({ error: { code: "page_exists", message: "Exists", path: "taken.md" } }, 409);
    if (url.includes("/pages/by-path/docs/handbook/current")) return json(record);
    if (url.includes("/preview")) return json({ html: "<p>Preview</p>", headings: [] });
    return json({ error: { code: "not_found", message: "Not found" } }, 404);
  });
  vi.stubGlobal("fetch", fetchSpy);
  const history = createMemoryHistory({ initialEntries: [at] });
  const router = createAppRouter(client, history);
  const view = render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return { view, history, router, fetchSpy };
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

async function open(view: ReturnType<typeof render>) {
  const action = await waitFor(() => {
    const link = view.container.querySelector<HTMLAnchorElement>("[data-pb-new-page]");
    expect(link?.tagName).toBe("A"); return link!;
  });
  action.focus(); fireEvent.click(action);
  await waitFor(() => expect(view.getByRole("dialog", { name: "New page" })).toBeTruthy());
  return action;
}

describe("new page modal navigation", () => {
  it("chooses a folder by its display label while keeping the exact submitted path", async () => {
    const { view, fetchSpy } = mount();
    await open(view);
    const folder = view.getByRole("combobox", { name: "Folder" });
    const option = view.getByRole("option", { name: "Guides" }) as HTMLOptionElement;
    fireEvent.change(folder, { target: { value: option.value } });
    fireEvent.change(view.getByRole("textbox", { name: "Title" }), { target: { value: "New guide" } });
    fireEvent.click(view.getByRole("button", { name: "Create page" }));
    await waitFor(() => expect(fetchSpy.mock.calls.some(([, init]) => init?.method === "POST")).toBe(true));
    const payload = JSON.parse(String(fetchSpy.mock.calls.find(([, init]) => init?.method === "POST")![1]?.body));
    expect(payload.folder).toBe("raw folder");
    expect(payload.root).toBe("docs");
    expect(payload.type).toBe("Reference");
  });

  it.each([
    "Type-bearing concepts cannot use index.md; choose another title or supply a non-reserved slug",
    "title must not contain ISO control characters, U+FFFE/U+FFFF, U+2028/U+2029, or unpaired Unicode surrogates",
    "An arbitrary server message passes through unchanged.",
  ])("passes through the mocked typed-create route message: %s", async (message) => {
    const { view, fetchSpy } = mount("/docs", { create: Promise.resolve(json({ error: { code: "invalid_create_request", message } }, 400)) });
    await open(view);
    fireEvent.change(view.getByRole("textbox", { name: "Title" }), { target: { value: "Index" } });
    fireEvent.click(view.getByRole("button", { name: "Create page" }));
    await waitFor(() => expect(view.getByRole("alert").textContent).toBe(message));
    expect(view.getByRole("dialog", { name: "New page" })).toBeTruthy();
    const post = fetchSpy.mock.calls.find(([, init]) => init?.method === "POST")!;
    expect(JSON.parse(String(post[1]?.body)).type).toBe("Reference");
  });

  it("preserves a supplied unknown folder and allows explicit custom folder creation", async () => {
    const { view, fetchSpy } = mount("/new?root=docs&folder=unknown%2Fteam");
    const custom = await waitFor(() => view.getByRole("textbox", { name: "Custom folder path" }));
    expect((custom as HTMLInputElement).value).toBe("unknown/team");
    fireEvent.change(custom, { target: { value: "another/new team" } });
    fireEvent.change(view.getByRole("textbox", { name: "Title" }), { target: { value: "New guide" } });
    fireEvent.click(view.getByRole("button", { name: "Create page" }));
    await waitFor(() => expect(fetchSpy.mock.calls.some(([, init]) => init?.method === "POST")).toBe(true));
    const payload = JSON.parse(String(fetchSpy.mock.calls.find(([, init]) => init?.method === "POST")![1]?.body));
    expect(payload.folder).toBe("another/new team");
  });

  it("disambiguates duplicate display names and keeps a folder named custom selectable", async () => {
    const base = tree.roots[0];
    const folders = ["first", "second", "custom"].map((path) => ({ type: "folder" as const, name: path, title: "Documentation",
      description: null, path, url: `/docs/${path}`, page_count: 0, children: [] }));
    const { view, fetchSpy } = mount("/docs", { tree: { roots: [{ ...base, tree: { ...base.tree, children: folders } }] } });
    await open(view);
    expect(view.getByRole("option", { name: "Documentation (first)" })).toBeTruthy();
    expect(view.getByRole("option", { name: "Documentation (second)" })).toBeTruthy();
    const option = view.getByRole("option", { name: "Documentation (custom)" }) as HTMLOptionElement;
    fireEvent.change(view.getByRole("combobox", { name: "Folder" }), { target: { value: option.value } });
    expect(view.queryByRole("textbox", { name: "Custom folder path" })).toBeNull();
    fireEvent.change(view.getByRole("textbox", { name: "Title" }), { target: { value: "New guide" } });
    fireEvent.click(view.getByRole("button", { name: "Create page" }));
    await waitFor(() => expect(fetchSpy.mock.calls.some(([, init]) => init?.method === "POST")).toBe(true));
    expect(JSON.parse(String(fetchSpy.mock.calls.find(([, init]) => init?.method === "POST")![1]?.body)).folder).toBe("custom");
  });
  it("keeps the background, query and hash mounted and restores focus through Back and Forward", async () => {
    const { view, history } = mount("/docs?mode=history#keep");
    const background = await waitFor(() => { const node = view.container.querySelector("[data-pb-folder]"); expect(node).not.toBeNull(); return node; });
    const action = await open(view);
    expect(history.location.href).toBe("/docs?mode=history#keep");
    expect(view.container.querySelector("[data-pb-folder]")).toBe(background);
    expect(history.length).toBe(2);
    fireEvent.click(action);
    expect(history.length).toBe(2);
    fireEvent.change(view.getByRole("textbox", { name: "Title" }), { target: { value: "Draft" } });
    act(() => history.back());
    await waitFor(() => expect(view.queryByRole("dialog")).toBeNull());
    expect(document.activeElement).toBe(action);
    act(() => history.forward());
    await waitFor(() => expect(view.getByRole("dialog")).toBeTruthy());
    expect((view.getByRole("textbox", { name: "Title" }) as HTMLInputElement).value).toBe("");
    fireEvent.click(view.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(view.queryByRole("dialog")).toBeNull());
    expect(view.container.querySelector("[data-pb-folder]")).toBe(background);
  });

  it("leaves modified links native and excludes search while the dialog is open", async () => {
    const { view, history } = mount();
    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page]")?.tagName).toBe("A"));
    fireEvent.click(view.container.querySelector("[data-pb-new-page]")!, { ctrlKey: true });
    expect(history.length).toBe(1);
    expect(view.queryByRole("dialog")).toBeNull();
    await open(view);
    fireEvent.keyDown(document, { key: "k", ctrlKey: true });
    fireEvent(document, new CustomEvent("pb:search-open"));
    expect(view.queryByRole("dialog", { name: "Search your docs" })).toBeNull();
  });

  it("waits for an alias editor to resolve before opening over its canonical identity", async () => {
    let resolve!: (response: Response) => void;
    const delayed = new Promise<Response>((done) => { resolve = done; });
    const { view, history } = mount("/docs/alias?mode=edit", { delayed });
    await waitFor(() => expect(view.container.querySelector("[data-pb-loading]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-new-page]")?.hasAttribute("disabled")).toBe(true);
    await act(async () => resolve(json(record)));
    await waitFor(() => expect(history.location.pathname).toBe(record.url));
    await open(view);
    expect(history.location.pathname).toBe(record.url);
    expect(view.container.querySelector("[data-pb-editor]")).not.toBeNull();
    expect((view.getByRole("combobox", { name: "Folder" }) as HTMLInputElement).value).toBe(JSON.stringify(["folder", "raw folder"]));
  });

  it("suppresses a successful request after its dialog instance unmounts", async () => {
    let resolve!: (response: Response) => void;
    const create = new Promise<Response>((done) => { resolve = done; });
    const { view, history, fetchSpy } = mount("/docs", { create });
    await open(view);
    fireEvent.change(view.getByRole("textbox", { name: "Title" }), { target: { value: "Late page" } });
    fireEvent.click(view.getByRole("button", { name: "Create page" }));
    await waitFor(() => expect(fetchSpy.mock.calls.some(([, init]) => init?.method === "POST")).toBe(true));
    const replace = vi.spyOn(history, "replace");
    view.unmount();
    await act(async () => { resolve(json({ id: record.id, url: "/docs/late", content_hash: record.content_hash, commit: null }, 201)); });
    expect(replace).not.toHaveBeenCalled();
  });

  it("direct dismissal returns to its own root without inventing history", async () => {
    const { view, history } = mount("/new?root=docs&folder=raw%20folder");
    await waitFor(() => expect(view.getByRole("dialog")).toBeTruthy());
    expect((view.getByLabelText("Folder") as HTMLInputElement).value).toBe(JSON.stringify(["folder", "raw folder"]));
    fireEvent.click(view.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(history.location.pathname).toBe("/docs"));
    expect(history.length).toBe(1);
  });

  it("allows creation on the normalized trailing-slash root landing", async () => {
    const { view, history, fetchSpy } = mount("/docs/");
    await open(view);
    expect(history.location.pathname).toBe("/docs/");
    fireEvent.change(view.getByRole("combobox", { name: "Folder" }), { target: { value: JSON.stringify(["folder", "raw folder"]) } });
    fireEvent.change(view.getByRole("combobox", { name: "Folder" }), { target: { value: JSON.stringify(["folder", ""]) } });
    expect((view.getByRole("combobox", { name: "Folder" }) as HTMLInputElement).value).toBe(JSON.stringify(["folder", ""]));
    fireEvent.change(view.getByRole("textbox", { name: "Title" }), { target: { value: "Root page" } });
    fireEvent.click(view.getByRole("button", { name: "Create page" }));
    await waitFor(() => expect(fetchSpy.mock.calls.some(([, init]) => init?.method === "POST")).toBe(true));
    const payload = JSON.parse(String(fetchSpy.mock.calls.find(([, init]) => init?.method === "POST")![1]?.body));
    expect(payload.root).toBe("docs");
    expect(payload.folder).toBeUndefined();
    expect(payload.title).toBe("Root page");
  });

  it("rejects malformed and mismatched history markers while accepting a valid restored marker", async () => {
    const { view, history } = mount();
    await waitFor(() => expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    for (const marker of [{ sourceHref: "/other", root: "docs", folder: "", sessionKey: "stored" },
      { sourceHref: "/docs", root: "docs", folder: 5, sessionKey: "stored" }]) {
      act(() => history.replace("/docs", { pbNewPage: marker as never }));
      await waitFor(() => expect(view.queryByRole("dialog")).toBeNull());
    }
    act(() => history.push("/docs", { pbNewPage: { sourceHref: "/docs", root: "docs", folder: "raw folder", sessionKey: "stored" } }));
    await waitFor(() => expect(view.getByRole("dialog")).toBeTruthy());
    expect((view.getByLabelText("Folder") as HTMLInputElement).value).toBe(JSON.stringify(["folder", "raw folder"]));
  });

  it("preserves another navigation blocker after a successful create and prevents a second write", async () => {
    const { view, history, fetchSpy } = mount("/docs", { create: Promise.resolve(json({ id: record.id, url: "/docs/new", content_hash: record.content_hash, commit: null }, 201)) });
    await open(view);
    const blocked = vi.fn(() => true);
    const release = history.block({ blockerFn: blocked });
    fireEvent.change(view.getByRole("textbox", { name: "Title" }), { target: { value: "Created once" } });
    fireEvent.click(view.getByRole("button", { name: "Create page" }));
    await waitFor(() => expect(blocked).toHaveBeenCalled());
    expect(history.location.pathname).toBe("/docs");
    await waitFor(() => expect(view.getByRole("status").textContent).toContain("Page created"));
    await waitFor(() => expect(view.getByRole("button", { name: "Close" }).hasAttribute("disabled")).toBe(false));
    expect(view.getByRole("button", { name: "Create page" }).hasAttribute("disabled")).toBe(true);
    expect(view.getByRole("combobox", { name: "Folder" }).hasAttribute("disabled")).toBe(true);
    expect(fetchSpy.mock.calls.filter(([, init]) => init?.method === "POST")).toHaveLength(1);
    release();
  });

  it("retains the same editor and its selection while opening and closing creation", async () => {
    const { view } = mount(record.url + "?mode=edit");
    const dom = await waitFor(() => { const node = view.container.querySelector<HTMLElement>(".cm-content"); expect(node).not.toBeNull(); return node!; });
    const editor = EditorView.findFromDOM(dom)!;
    act(() => editor.dispatch({ selection: { anchor: 3, head: 7 } }));
    await open(view);
    fireEvent.click(view.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(view.queryByRole("dialog")).toBeNull());
    expect(EditorView.findFromDOM(dom)).toBe(editor);
    expect(editor.state.selection.main.anchor).toBe(3);
    expect(editor.state.selection.main.head).toBe(7);
  });

  it.each(["unknown", "readonly", "unavailable"])("keeps %s direct creation disabled without switching roots", async (kind) => {
    const seeded = structuredClone(tree);
    if (kind === "readonly") seeded.roots[0].editable = false;
    if (kind === "unavailable") seeded.roots[0].available = false;
    const { view, fetchSpy } = mount(`/new?root=${kind === "unknown" ? "missing" : "docs"}`, { tree: seeded });
    await waitFor(() => expect(view.getByRole("dialog")).toBeTruthy());
    fireEvent.change(view.getByRole("textbox", { name: "Title" }), { target: { value: "No write" } });
    expect(view.getByRole("button", { name: "Create page" }).hasAttribute("disabled")).toBe(true);
    expect(fetchSpy.mock.calls.filter(([, init]) => init?.method === "POST")).toHaveLength(0);
  });
});

describe("creation URL preview", () => {
  it("uses folder URL overrides and approximates only the unknown tail", () => {
    expect(creationUrlPreview(tree.roots[0], "raw folder", "Hello World", false)).toBe("/docs/handbook/hello-world");
    expect(creationUrlPreview(tree.roots[0], "raw folder/New Folder", "Hello", false)).toBe("/docs/handbook/new-folder/hello");
    expect(creationUrlPreview(tree.roots[0], "raw folder", "ignored", true)).toBe("/docs/handbook");
    expect(creationUrlPreview(tree.roots[0], "collision", "Hello", false)).toBeNull();
  });
});
