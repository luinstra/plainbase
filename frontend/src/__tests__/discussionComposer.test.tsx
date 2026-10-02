import { discussionButton, findDiscussionButton } from "./discussionInteractions";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { createAppRouter } from "../router";
import { clearCsrfToken } from "../api/csrf";
import { pageHtmlQuery, pageQuery, sessionQuery, treeQuery } from "../api/queries";
import { pageDiscussionsQuery } from "../api/discussions";

const page = { id: "page", root: "extra", path: "note.md", url: "/extra/note", title: "Note", markdown: "Text", frontmatter: {}, content_hash: "sha256:old", commit: null };
const html = { ...page, html: '<p data-pb-src="0-5">Text</p>', headings: [] };

function mount(fetcher: typeof fetch, strict = false, initialEntry = "/extra/note") {
  vi.stubGlobal("fetch", fetcher);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  client.setQueryData(treeQuery.queryKey, { roots: [{ root: "extra", primary: true, available: true, editable: true,
    tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/extra", page_count: 0, children: [] } }] });
  client.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  const router = createAppRouter(client, createMemoryHistory({ initialEntries: [initialEntry] }));
  const app = <QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>;
  return { ...render(strict ? <StrictMode>{app}</StrictMode> : app), client, history: router.history };
}

afterEach(() => { vi.unstubAllGlobals(); clearCsrfToken(); });

it.each(["live", "retained"])("New discussion uses a %s page selection once, then defaults back to the page", async (kind) => {
  const previews: unknown[] = [];
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/anchor-preview")) {
      previews.push(JSON.parse(String(init?.body)));
      return Response.json({ content_hash: page.content_hash, byte_start: 0, byte_end: 5,
        selection: "narrowed", quote_text: "Text" });
    }
    if (url.includes("/html")) return Response.json(html);
    if (url.includes("/discussions?")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return Response.json(page);
  }) as typeof fetch);
  const start = await screen.findByRole("button", { name: "New discussion" });
  expect(screen.queryByRole("button", { name: "Comment on selection" })).toBeNull();
  const text = screen.getByText("Text", { selector: "p" }).firstChild!;
  const selection = window.getSelection()!;
  const range = document.createRange(); range.selectNodeContents(text);
  selection.removeAllRanges(); selection.addRange(range);
  fireEvent(document, new Event("selectionchange"));
  if (kind === "retained") selection.removeAllRanges(); // Focus transfer must not lose the selected passage.
  fireEvent.click(start);
  expect(await screen.findByText("Text", { selector: "blockquote" })).toBeTruthy();
  expect(previews).toEqual([{ kind: "quote", content_hash: page.content_hash, block_start: 0, block_end: 5, selected_text: "Text" }]);
  expect(selection.toString()).toBe("");
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  fireEvent.click(screen.getByRole("button", { name: "Confirm passage" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "A comment" } });
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", false);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  const next = screen.getByRole("button", { name: "New discussion" });
  await waitFor(() => expect(next).toBe(document.activeElement));
  fireEvent.click(next);
  expect(await screen.findByRole("heading", { name: "New page discussion" })).toBeTruthy();
  expect(previews).toHaveLength(1);
});

it.each(["absent", "collapsed", "cleared by page click", "invalid", "multiple ranges"])("New discussion distinguishes an %s selection from a passage", async (kind) => {
  let previews = 0;
  mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/anchor-preview")) { previews++; throw new Error("No preview expected"); }
    if (url.includes("/html")) return Response.json(kind === "invalid" ? { ...html, html: "<p>Text</p>" } : html);
    if (url.includes("/discussions?")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return Response.json(page);
  }) as typeof fetch);
  const start = await screen.findByRole("button", { name: "New discussion" });
  const text = screen.getByText("Text", { selector: "p" }).firstChild!;
  const selection = window.getSelection()!;
  selection.removeAllRanges();
  if (kind !== "absent") {
    const range = document.createRange(); range.selectNodeContents(text); selection.addRange(range);
    fireEvent(document, new Event("selectionchange"));
    if (kind === "collapsed") {
      selection.collapse(text, 0);
      fireEvent(document, new Event("selectionchange"));
    }
    if (kind === "cleared by page click") {
      fireEvent.pointerDown(text.parentElement!);
      selection.removeAllRanges();
      fireEvent(document, new Event("selectionchange"));
    }
  }
  const ranges = kind === "multiple ranges" ? vi.spyOn(selection, "rangeCount", "get").mockReturnValue(2) : null;
  fireEvent.click(start);
  ranges?.mockRestore();
  if (kind === "invalid" || kind === "multiple ranges") {
    expect(screen.getByRole("alert").textContent).toContain("Select text in the page");
    expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Discuss the whole page instead" }));
  }
  expect(await screen.findByRole("heading", { name: "New page discussion" })).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Confirm passage" })).toBeNull();
  expect(previews).toBe(0);
});

it.each(["network", "server", "malformed success"])("keeps Refresh beside uncertain creation (%s) and retains the draft while inspecting the page list", async (failure) => {
  let listReads = 0; let posts = 0;
  const item = { id: "created", page: { id: "page", path: "note.md", resolution: "by_id" },
    state: "page_level", status: "open", reason: null, range: null, candidates: null, placement: null, quote: null,
    comment_count: 1, starter: null, created: null, updated: null };
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (init?.method === "POST") {
      posts++;
      if (failure === "network") throw new TypeError("connection lost");
      if (failure === "malformed success") return Response.json({ id: null }, { status: 201 });
      return Response.json({ error: { code: "internal_error", message: "failed" } }, { status: 503 });
    }
    if (url.includes("/html")) return Response.json(html);
    if (url.includes("/discussions/created?")) return Response.json({ discussion: { ...item,
      anchor: { kind: "page", content_hash: page.content_hash, commit: null }, reattachment: null },
      comments: [], next: null, discussions_available: true, reason: null });
    if (url.includes("/discussions?")) {
      listReads++;
      return Response.json({ discussions: posts ? [item] : [], next: null, discussions_available: true, reason: null });
    }
    return Response.json(page);
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  const field = screen.getByRole("textbox", { name: "Comment" });
  fireEvent.change(field, { target: { value: "Keep my first comment" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  const notice = await screen.findByRole("alert");
  expect(notice.textContent).toContain("outcome is unclear");
  fireEvent.keyDown(field, { key: "Escape" });
  expect(notice.textContent).toContain("outcome is unclear");
  const before = listReads;
  fireEvent.click(within(notice).getByRole("button", { name: "Refresh" }));
  await waitFor(() => expect(listReads).toBeGreaterThan(before));
  expect(field).toHaveProperty("value", "Keep my first comment");
  fireEvent.click(screen.getByRole("button", { name: /About this page/ }));
  await screen.findByRole("heading", { name: "Discussion on note.md" });
  fireEvent.click(screen.getByRole("button", { name: "Close discussion" }));
  expect(await screen.findByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep my first comment");
  expect(posts).toBe(1);
});

it.each(["empty", "server validation", "preview"])("keeps ordinary creation errors (%s) separate from uncertain-outcome inspection", async (failure) => {
  let posts = 0;
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (init?.method === "POST") {
      posts++;
      return Response.json({ error: { code: failure === "preview" ? "invalid_anchor" : "comment_empty", message: "invalid" } }, { status: 400 });
    }
    if (url.includes("/html")) return Response.json(html);
    if (url.includes("/discussions?")) return Response.json({ discussions: [{ id: "existing", quote: "An existing conversation",
      page: { id: "page", path: "note.md", resolution: "by_id" }, state: "exact", status: "open", comment_count: 1 }],
      next: null, discussions_available: true, reason: null });
    return Response.json(page);
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  if (failure !== "empty") fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "My comment" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  await screen.findByRole("alert");
  expect(screen.queryByRole("button", { name: "Refresh" })).toBeNull();
  expect(screen.queryByRole("button", { name: /An existing conversation/ })).toBeNull();
  expect(posts).toBe(failure === "empty" ? 0 : 1);
});

it("returns creation Cancel to visible Refresh when availability removed the initiating control", async () => {
  let available = true;
  const view = mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/html")) return Response.json(html);
    if (url.includes("/discussions?")) return Response.json({ discussions: [], next: null, discussions_available: available, reason: null });
    return Response.json(page);
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  available = false;
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageDiscussionsQuery("extra", "page").queryKey }); });
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.queryByRole("button", { name: "New discussion" })).toBeNull();
  await waitFor(() => expect(screen.getByRole("button", { name: "Refresh" })).toBe(document.activeElement));
});

it("returns passage Cancel to Refresh when source failure disables New discussion", async () => {
  let sourceFailed = false; let previews = 0;
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (init?.method === "POST") {
      previews++;
      return Response.json({ content_hash: page.content_hash, byte_start: 0, byte_end: 5, selection: "narrowed", quote_text: "Text" });
    }
    if (url.includes("/html")) return sourceFailed
      ? Response.json({ error: { code: "content_unreadable", message: "unreadable" } }, { status: 503 }) : Response.json(html);
    if (url.includes("/discussions?")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return Response.json(page);
  }) as typeof fetch);
  const text = (await screen.findByText("Text", { selector: "p" })).firstChild!;
  const selection = window.getSelection()!;
  const range = document.createRange(); range.selectNodeContents(text); selection.removeAllRanges(); selection.addRange(range);
  fireEvent(document, new Event("selectionchange"));
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  await screen.findByRole("button", { name: "Confirm passage" });
  sourceFailed = true;
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageHtmlQuery("page", "extra").queryKey }); });
  await waitFor(() => expect(screen.getByRole("button", { name: "Reselect" })).toHaveProperty("disabled", true));
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.getByRole("button", { name: "New discussion" })).toHaveProperty("disabled", true);
  await waitFor(() => expect(screen.getByRole("button", { name: "Refresh" })).toBe(document.activeElement));
  expect(previews).toBe(1);
});

it("resets a hidden bare permalink margin when an unqualified HTML refetch resolves the same id in another root", async () => {
  const id = "01970000-0000-7000-8000-00000000f011";
  let resolvedRoot = "extra";
  let hash = "sha256:old";
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const calls: string[] = [];
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); calls.push(url);
    // url=null keeps this collision loser on /p/{id}; BOTH page reads stay unqualified.
    if (url === `/api/v1/pages/${id}`) return Response.json({ ...page, id, url: null });
    if (url === `/api/v1/pages/${id}/html`) return Response.json({ ...html, id, root: resolvedRoot, content_hash: hash });
    if (url === `/api/v1/pages/${id}/discussions?root=extra` && init?.method === "POST") return pending;
    if (["extra", "docs"].some((root) => url === `/api/v1/pages/${id}/discussions?root=${root}&limit=200`))
      return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    throw new Error(`unexpected fetch: ${url}`);
  }) as typeof fetch, false, `/p/${id}`);
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  fireEvent.change(await screen.findByRole("textbox", { name: "Comment" }), { target: { value: "A draft" } });
  await waitFor(() => expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", false));
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  await screen.findByRole("status", { name: "Posting…" });
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  const toggle = screen.getByRole("button", { name: "Show discussions" });
  const aPanel = view.container.querySelector("[data-pb-discussion-panel]");
  resolvedRoot = "docs";
  // Only HTML refreshes: the parsed address and seeded A metadata do not change.
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageHtmlQuery(id, null).queryKey, exact: true }); });
  await screen.findByRole("region", { name: "Page discussions" });
  expect(view.container.querySelector("[data-pb-discussion-panel]")).not.toBe(aPanel);
  expect(toggle.getAttribute("aria-expanded")).toBe("true");
  expect(toggle).toBe(document.activeElement);
  expect(screen.queryByRole("alert")).toBeNull();
  expect(screen.queryByRole("textbox")).toBeNull();
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  const bDraft = screen.getByRole("textbox", { name: "Comment" });
  fireEvent.change(bDraft, { target: { value: "B draft" } });
  await waitFor(() => expect(bDraft).toBe(document.activeElement));
  await act(async () => { finish(Response.json({ error: { code: "forbidden", message: "late A failure" } }, { status: 403 })); });
  expect(bDraft).toHaveProperty("value", "B draft");
  expect(bDraft).toBe(document.activeElement);
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", false);
  expect(screen.queryByText(/You cannot post here/)).toBeNull();
  const bPanel = view.container.querySelector("[data-pb-discussion-panel]");
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  hash = "sha256:new";
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageHtmlQuery(id, null).queryKey, exact: true }); });
  expect(screen.getByRole("button", { name: "Show discussions" }).getAttribute("aria-expanded")).toBe("false");
  expect(view.container.querySelector("[data-pb-discussion-panel]")).toBe(bPanel);
  fireEvent.click(screen.getByRole("button", { name: "Show discussions" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toBe(bDraft);
  expect(bDraft).toHaveProperty("value", "B draft");
  // Returning to A must not replay its old whole-page fallback nonce into a fresh panel.
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  resolvedRoot = "extra";
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageHtmlQuery(id, null).queryKey, exact: true }); });
  await screen.findByRole("region", { name: "Page discussions" });
  expect(toggle.getAttribute("aria-expanded")).toBe("true");
  expect(toggle).toBe(document.activeElement);
  expect(screen.queryByRole("textbox")).toBeNull();
  expect(screen.queryByRole("alert")).toBeNull();
  expect(view.history.location.pathname).toBe(`/p/${id}`);
  expect(view.client.getQueryData(pageQuery(id, null).queryKey)).toMatchObject({ root: "extra", url: null });
  expect(calls.filter((url) => url.startsWith(`/api/v1/pages/${id}`) && !url.includes("/discussions")))
    .toEqual([`/api/v1/pages/${id}`, ...Array(4).fill(`/api/v1/pages/${id}/html`)]);
});

it.each([false, true])("invalidates bare same-hash root-change selection (live range %s) and isolates late previews", async (liveRange) => {
  const id = "01970000-0000-7000-8000-00000000f012";
  let resolvedRoot = "extra";
  let ambiguous = false;
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const calls: string[] = [];
  const view = mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input); calls.push(url);
    if (url === `/api/v1/pages/${id}`) return Response.json({ ...page, id, url: null });
    if (url === `/api/v1/pages/${id}/html`) return ambiguous
      ? Response.json({ error: { code: "ambiguous_page_id", message: "Bare HTML is ambiguous" } }, { status: 409 })
      : Response.json({ ...html, id, root: resolvedRoot });
    if (url === `/api/v1/pages/${id}/discussions/anchor-preview?root=extra`) return pending;
    if (["extra", "docs"].some((root) => url === `/api/v1/pages/${id}/discussions?root=${root}&limit=200`))
      return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    throw new Error(`unexpected fetch: ${url}`);
  }) as typeof fetch, false, `/p/${id}`);
  const text = (await screen.findByText("Text", { selector: "p" })).firstChild!;
  const selection = window.getSelection()!;
  const range = document.createRange(); range.selectNodeContents(text); selection.removeAllRanges(); selection.addRange(range);
  fireEvent(document, new Event("selectionchange"));
  if (!liveRange) selection.removeAllRanges(); // Capture must use the hook's retained article selection.
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  await screen.findByText("Preparing passage preview…");
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  const toggle = screen.getByRole("button", { name: "Show discussions" });
  resolvedRoot = "docs";
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageHtmlQuery(id, null).queryKey, exact: true }); });
  await screen.findByRole("region", { name: "Page discussions" });
  expect(toggle).toBe(document.activeElement);
  await act(async () => { finish(Response.json({ content_hash: "sha256:old", byte_start: 0, byte_end: 5,
    selection: "narrowed", quote_text: "Text" })); });
  expect(toggle).toBe(document.activeElement);
  expect(screen.queryByRole("button", { name: "Confirm passage" })).toBeNull();
  expect(screen.queryByRole("textbox")).toBeNull();
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  expect(await screen.findByRole("heading", { name: "New page discussion" })).toBeTruthy();
  expect(calls.filter((url) => url.includes("anchor-preview"))).toEqual([`/api/v1/pages/${id}/discussions/anchor-preview?root=extra`]);
  ambiguous = true;
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageHtmlQuery(id, null).queryKey, exact: true }); });
  await screen.findByText("Bare HTML is ambiguous");
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  expect(calls.filter((url) => url.startsWith(`/api/v1/pages/${id}`) && !url.includes("/discussions")))
    .toEqual([`/api/v1/pages/${id}`, ...Array(3).fill(`/api/v1/pages/${id}/html`)]);
});

it.each([
  { route: "/extra/other", root: "extra", id: "other", folder: false },
  { route: "/docs/other", root: "docs", id: "page", folder: false },
  { route: "/docs/guides", root: "docs", id: "page", folder: true },
])("isolates hidden drafts and late A writes when navigating to $route (B id $id)", async ({ route, root, id, folder }) => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const calls: string[] = [];
  const b = { ...page, root, id, path: folder ? "guides/README.md" : "other.md", url: folder ? null : route, title: "Page B" };
  const item = { id: "b-thread", page: { id, path: b.path, resolution: "by_id" }, status: "open", state: "page_level",
    reason: null, range: null, candidates: null, placement: null, quote: null, comment_count: 0,
    starter: null, created: null, updated: null };
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://x");
    calls.push(String(input));
    if (init?.method === "POST" && url.searchParams.get("root") === "extra") return pending;
    if (url.pathname === "/api/v1/pages/by-path/extra/note") return Response.json(page);
    if (url.pathname === `/api/v1/pages/by-path${route}`) return folder
      ? Response.json({ error: { code: "page_not_found", message: "folder" } }, { status: 404 }) : Response.json(b);
    if (url.pathname === `/api/v1/pages/${id}` && url.searchParams.get("root") === root) return Response.json(b);
    if (url.pathname.endsWith("/html")) return Response.json(url.searchParams.get("root") === root && url.pathname === `/api/v1/pages/${id}/html`
      ? { ...html, ...b, html: '<p data-pb-src="0-6">B text</p>' } : html);
    if (url.pathname.endsWith("/discussions") && init?.method !== "POST") return Response.json({
      discussions: url.searchParams.get("root") === root && url.pathname === `/api/v1/pages/${id}/discussions` ? [item] : [],
      next: null, discussions_available: true, reason: null,
    });
    throw new Error(`unexpected fetch: ${input}`);
  }) as typeof fetch);
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  expect(await screen.findByRole("heading", { name: "New page discussion" })).toBeTruthy();
  fireEvent.change(await screen.findByRole("textbox", { name: "Comment" }), { target: { value: "A draft" } });
  await waitFor(() => expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", false));
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  await screen.findByRole("status", { name: "Posting…" });
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  act(() => view.client.setQueryData(treeQuery.queryKey, { roots: ["extra", "docs"].map((name) => ({
    root: name, primary: name === "extra", available: true, editable: true,
    tree: { type: "folder" as const, name: "", title: null, description: null, path: "", url: `/${name}`, page_count: 0,
      children: folder && name === root ? [{ type: "folder" as const, name: "guides", title: "Guides", description: null,
        path: "guides", url: route, page_count: 1, children: [{ type: "page" as const, id, path: b.path, title: b.title,
          slug: "readme", url: null, status: "active", updated: null }] }] : [] },
  })) }));
  act(() => view.history.push(route));
  await screen.findByText("B text", { selector: "p" });
  const bPanel = await screen.findByRole("region", { name: "Page discussions" });
  expect(await within(bPanel).findByRole("button", { name: /About this page/ })).toBeTruthy();
  expect(screen.getByRole("button", { name: "Hide discussions" }).getAttribute("aria-expanded")).toBe("true");
  expect(screen.queryByRole("textbox")).toBeNull();
  expect(screen.queryByRole("alert")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "New discussion" }));
  const bDraft = screen.getByRole("textbox", { name: "Comment" });
  fireEvent.change(bDraft, { target: { value: "B draft" } });
  bDraft.focus();
  await act(async () => { finish(Response.json({ error: { code: "forbidden", message: "late A failure" } }, { status: 403 })); });
  expect(bDraft).toHaveProperty("value", "B draft");
  expect(bDraft).toBe(document.activeElement);
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", false);
  expect(screen.queryByText(/You cannot post here/)).toBeNull();
  expect(view.history.location.pathname).toBe(route);
  expect(calls.filter((url) => url === `/api/v1/pages/${id}/discussions?root=${root}&limit=200`)).toHaveLength(1);
});

it("retains a delayed creation preview while hidden without stealing focus or posting", async () => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const writes: string[] = [];
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (init?.method === "POST") { writes.push(url); return pending; }
    if (url === "/api/v1/pages/page/discussions?root=extra&limit=200")
      return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    throw new Error(`unexpected fetch: ${url}`);
  }) as typeof fetch);
  const text = (await screen.findByText("Text", { selector: "p" })).firstChild!;
  const selection = window.getSelection()!;
  const range = document.createRange(); range.selectNodeContents(text); selection.removeAllRanges(); selection.addRange(range);
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  await screen.findByText("Preparing passage preview…");
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Hidden preview draft" } });
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  const toggle = screen.getByRole("button", { name: "Show discussions" });
  await act(async () => { finish(Response.json({ content_hash: "sha256:old", byte_start: 0, byte_end: 5,
    selection: "narrowed", quote_text: "Text" })); });
  await waitFor(() => expect(discussionButton({ name: "Confirm passage", hidden: true })).toBeTruthy());
  expect(toggle).toBe(document.activeElement);
  expect(writes).toEqual(["/api/v1/pages/page/discussions/anchor-preview?root=extra"]);
  fireEvent.click(toggle);
  await waitFor(() => expect(screen.getByRole("textbox", { name: "Comment" })).toBe(document.activeElement));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Hidden preview draft");
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
});

it("keeps a page draft through hide/reopen and sends a rooted hash-bound creation once", async () => {
  const writes: unknown[] = [];
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/pages/page/discussions") && init?.method === "POST") {
      writes.push(JSON.parse(String(init.body)));
      return Response.json({ id: "created", comment_id: null, commit: null }, { status: 201 });
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    if (url.includes("/discussions/created")) return Response.json({ discussion: null, comments: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  const body = await screen.findByRole("textbox", { name: "Comment" });
  fireEvent.change(body, { target: { value: "  Draft 😀  " } });
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  fireEvent.click(screen.getByRole("button", { name: "Show discussions" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "  Draft 😀  ");
  await waitFor(() => expect(screen.getByRole("textbox", { name: "Comment" })).toBe(document.activeElement));
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  await waitFor(() => expect(writes).toEqual([{ anchor: { kind: "page", content_hash: "sha256:old" }, body: "  Draft 😀  " }]));
  expect(await screen.findByRole("status", { name: /Discussion created/i })).toBeTruthy();
});

it("automatically reads availability and keeps unsupported discussions expanded without creation", async () => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const reads: string[] = [];
  mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url === "/api/v1/pages/page/discussions?root=extra&limit=200") { reads.push(url); return pending; }
    throw new Error(`unexpected fetch: ${url}`);
  }) as typeof fetch);
  expect(await screen.findByText("Loading page discussions…")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Hide discussions" })).toHaveProperty("disabled", false);
  expect(screen.queryByRole("button", { name: "New discussion" })).toBeNull();
  await act(async () => { finish(Response.json({ discussions: [], next: null, discussions_available: false, reason: "object_storage" })); });
  expect(await screen.findByText("Discussions are not available for this storage type")).toBeTruthy();
  expect(screen.queryByRole("button", { name: "New discussion" })).toBeNull();
  expect(screen.queryByRole("button", { name: "New discussion" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  fireEvent.click(screen.getByRole("button", { name: "Show discussions" }));
  expect(screen.getByText("Discussions are not available for this storage type")).toBeTruthy();
  expect(reads).toEqual(["/api/v1/pages/page/discussions?root=extra&limit=200"]);
});

it("keeps a page draft accessible and disables posting when its source refetch fails", async () => {
  let sourceFails = false;
  const view = mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return sourceFails
      ? Response.json({ error: { code: "content_unreadable", message: "HTML unavailable" } }, { status: 503 }) : Response.json({ ...html,
        headings: [{ id: "first", level: 2, text: "First" }, { id: "second", level: 2, text: "Second" }] });
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep this draft" } });
  const info = view.container.querySelector("[data-pb-rail-meta]");
  const toc = view.container.querySelector("[data-pb-toc]");
  expect(info?.textContent).toContain("note.md");
  expect(toc?.textContent).toContain("First");
  sourceFails = true;
  await view.client.invalidateQueries({ queryKey: ["page", "html", "page"] });
  expect(await screen.findByText("HTML unavailable")).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep this draft");
  expect(view.container.querySelector("[data-pb-rail-meta]")).toBe(info);
  expect(view.container.querySelector("[data-pb-toc]")).toBe(toc);
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  await waitFor(() => expect(screen.getByRole("textbox", { name: "Comment" })).toBe(document.activeElement));
  const toggle = screen.getByRole("button", { name: "Hide discussions" });
  toggle.focus();
  fireEvent.click(toggle);
  const show = screen.getByRole("button", { name: "Show discussions" });
  show.focus();
  expect(show).toBe(document.activeElement);
  fireEvent.click(show);
  await waitFor(() => expect(screen.getByRole("textbox", { name: "Comment" })).toBe(document.activeElement));
});

it("reserves the margin without a panel or guessed root after initial HTML failure", async () => {
  const reads: string[] = [];
  const view = mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input); reads.push(url);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json({ error: { code: "content_unreadable", message: "Initial source failure" } }, { status: 503 });
    throw new Error(`unexpected fetch: ${url}`);
  }) as typeof fetch);
  await screen.findByText("Initial source failure");
  expect(view.container.querySelector("[data-pb-rail]")).not.toBeNull();
  expect(view.container.querySelector("[data-pb-rail-meta]")).toBeNull();
  expect(view.container.querySelector("[data-pb-discussion-panel]")).toBeNull();
  expect(reads).toEqual(["/api/v1/pages/by-path/extra/note", "/api/v1/pages/page/html?root=extra"]);
});

it("reloads an unreadable page from its visible draft and posts once after same-hash recovery", async () => {
  let htmlReads = 0;
  let finishReload!: (response: Response) => void;
  const pendingReload = new Promise<Response>((resolve) => { finishReload = resolve; });
  const writes: unknown[] = [];
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) {
      htmlReads++;
      if (htmlReads === 2) return Response.json({ error: { code: "content_unreadable", message: "HTML unavailable" } }, { status: 503 });
      return htmlReads === 3 ? pendingReload : Response.json(html);
    }
    if (url.includes("/pages/page/discussions") && init?.method === "POST") {
      writes.push(JSON.parse(String(init.body)));
      return Response.json({ id: "created", comment_id: null, commit: null }, { status: 201 });
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    if (url.includes("/discussions/created")) return Response.json({ discussion: null, comments: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "  Draft after outage  " } });
  await view.client.invalidateQueries({ queryKey: ["page", "html", "page"] });
  expect(await screen.findByText("HTML unavailable")).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "  Draft after outage  ");
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("button", { name: "Hide discussions" }).getAttribute("aria-expanded")).toBe("true");
  expect(document.getElementById(screen.getByRole("button", { name: "Hide discussions" }).getAttribute("aria-controls")!)).not.toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  expect(await screen.findByRole("button", { name: "Reloading page…" })).toBeTruthy();
  expect(screen.getByRole("button", { name: "Hide discussions" }).getAttribute("aria-expanded")).toBe("true");
  fireEvent.click(screen.getByRole("button", { name: "Reloading page…" }));
  expect(htmlReads).toBe(3);
  await act(async () => { finishReload(Response.json(html)); });
  await waitFor(() => expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", false));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "  Draft after outage  ");
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByRole("status", { name: "Discussion created" })).toBeTruthy();
  expect(writes).toEqual([{ anchor: { kind: "page", content_hash: "sha256:old" }, body: "  Draft after outage  " }]);
});

it("keeps a rejected page draft gated through a failed reload, then accepts an explicit same-hash recovery", async () => {
  let failReload = true;
  let htmlReads = 0;
  const writes: unknown[] = [];
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) {
      htmlReads++;
      return htmlReads > 1 && failReload
        ? Response.json({ error: { code: "content_unreadable", message: "HTML unavailable" } }, { status: 503 })
        : Response.json(html);
    }
    if (url.includes("/pages/page/discussions") && init?.method === "POST") {
      writes.push(JSON.parse(String(init.body)));
      return writes.length === 1
        ? Response.json({ error: { code: "page_changed", message: "changed" } }, { status: 409 })
        : Response.json({ id: "created", comment_id: null, commit: null }, { status: 201 });
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    if (url.includes("/discussions/created")) return Response.json({ discussion: null, comments: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep my page draft" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByText("The page changed. Reload it, review the text, and try again.")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  expect(await screen.findByText("HTML unavailable")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  failReload = false;
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", false));
  expect(screen.queryByText("The page changed. Reload it, review the text, and try again.")).toBeNull();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep my page draft");
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByRole("status", { name: "Discussion created" })).toBeTruthy();
  expect(writes).toEqual([
    { anchor: { kind: "page", content_hash: "sha256:old" }, body: "Keep my page draft" },
    { anchor: { kind: "page", content_hash: "sha256:old" }, body: "Keep my page draft" },
  ]);
});

it("removes stale reload guidance when a newer page source arrives in the page composer", async () => {
  let currentHash = "sha256:old";
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json({ ...html, content_hash: currentHash });
    if (url.includes("/pages/page/discussions") && init?.method === "POST")
      return Response.json({ error: { code: "page_changed", message: "changed" } }, { status: 409 });
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep this page draft" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByText("The page changed. Reload it, review the text, and try again.")).toBeTruthy();
  currentHash = "sha256:new";
  await view.client.invalidateQueries({ queryKey: ["page", "html", "page"] });
  await waitFor(() => expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", false));
  expect(screen.queryByText("The page changed. Reload it, review the text, and try again.")).toBeNull();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep this page draft");
});

it("requires a fresh preview and confirmation after a changed-hash reload of a rejected passage", async () => {
  let currentHash = "sha256:old";
  const previews: string[] = [];
  const writes: Array<{ anchor: { content_hash: string }; body: string }> = [];
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json({ ...html, content_hash: currentHash });
    if (url.includes("/anchor-preview")) {
      const anchor = JSON.parse(String(init?.body)) as { content_hash: string };
      previews.push(anchor.content_hash);
      return Response.json({ content_hash: anchor.content_hash, byte_start: 0, byte_end: 5,
        selection: "narrowed", quote_text: "Text" });
    }
    if (url.includes("/pages/page/discussions") && init?.method === "POST") {
      writes.push(JSON.parse(String(init.body)));
      return writes.length === 1
        ? Response.json({ error: { code: "page_changed", message: "changed" } }, { status: 409 })
        : Response.json({ id: "created", comment_id: null, commit: null }, { status: 201 });
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    if (url.includes("/discussions/created")) return Response.json({ discussion: null, comments: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  const selectText = async () => {
    const text = (await screen.findByText("Text", { selector: "p" })).firstChild!;
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, 4); selection.addRange(range);
    fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  };
  await selectText();
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep my passage draft" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByText("The page changed. Reload it, review the text, and try again.")).toBeTruthy();
  currentHash = "sha256:new";
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  await waitFor(() => expect(screen.queryByRole("button", { name: "Passage confirmed" })).toBeNull());
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep my passage draft");
  await selectText();
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByRole("status", { name: "Discussion created" })).toBeTruthy();
  expect(previews).toEqual(["sha256:old", "sha256:new"]);
  expect(writes.map(({ anchor, body }) => [anchor.content_hash, body])).toEqual([
    ["sha256:old", "Keep my passage draft"], ["sha256:new", "Keep my passage draft"],
  ]);
});

it("keeps a confirmed creation confirmed when discussion refresh fails", async () => {
  let listReads = 0;
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/pages/page/discussions") && init?.method === "POST")
      return Response.json({ id: "created", comment_id: null, commit: null }, { status: 201 });
    if (url.includes("/pages/page/discussions")) {
      listReads++;
      return listReads > 1 ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) :
        Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    }
    if (url.includes("/discussions/created")) return Response.json({ discussion: null, comments: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Posted once" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByRole("status", { name: "Discussion created" })).toBeTruthy();
  expect(await screen.findByText(/Posted, but the view could not refresh/)).toBeTruthy();
  expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
});

it("keeps earlier page rows and shows continuation after a later empty batch", async () => {
  mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/pages/page/discussions")) return Response.json({
      discussions: url.includes("cursor=") ? [] : [{ id: "first", page: { id: "page", path: "note.md", resolution: "by_id" },
        status: "open", state: "page_level", reason: null, range: null, candidates: null, placement: null,
        quote: null, comment_count: 0, starter: null, created: null, updated: null }],
      next: url.includes("cursor=") ? "third" : "second", discussions_available: true, reason: null,
    });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  expect(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /About this page/ })).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Load more" }));
  expect(await screen.findByText("No discussions in this window. Load more to continue.")).toBeTruthy();
  expect(within(screen.getByRole("region", { name: "Page discussions" })).getByRole("button", { name: /About this page/ })).toBeTruthy();
});

it("keeps the complete draft after an ordinary access denial without resubmitting", async () => {
  let posts = 0;
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/pages/page/discussions") && init?.method === "POST") {
      posts++;
      return Response.json({ error: { code: "forbidden", message: "denied" } }, { status: 403 });
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "  Still mine  " } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect((await screen.findByText(/You cannot post here/)).closest("[role=alert]")).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "  Still mine  ");
  expect(posts).toBe(1);
});

it("settles one page creation and one denied retry under StrictMode", async () => {
  let posts = 0;
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/pages/page/discussions") && init?.method === "POST") {
      posts++;
      return posts === 1 ? Response.json({ error: { code: "forbidden", message: "denied" } }, { status: 403 }) :
        Response.json({ id: "created", comment_id: null, commit: null }, { status: 201 });
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    if (url.includes("/discussions/created")) return Response.json({ discussion: null, comments: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch, true);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Strict draft" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByText(/You cannot post here/)).toBeTruthy();
  expect(posts).toBe(1);
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Strict draft");
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByRole("status", { name: "Discussion created" })).toBeTruthy();
  expect(posts).toBe(2);
});

it("settles passage preview failure and success under StrictMode without duplicate previews", async () => {
  let previews = 0;
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/anchor-preview") && init?.method === "POST") {
      previews++;
      return previews === 1 ? Response.json({ error: { code: "invalid_anchor", message: "bad" } }, { status: 422 }) :
        Response.json({ content_hash: "sha256:old", byte_start: 0, byte_end: 5, selection: "narrowed", quote_text: "Text" });
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch, true);
  const selectText = async () => {
    const text = (await screen.findByText("Text", { selector: "p" })).firstChild!;
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, 4); selection.addRange(range);
    fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  };
  await selectText();
  expect(await screen.findByText(/passage could not be found/i)).toBeTruthy();
  expect(previews).toBe(1);
  expect(screen.getByText("Reselect the passage and preview it before posting.")).toBeTruthy();
  expect(screen.queryByText("Confirm the passage before creating a discussion.")).toBeNull();
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep this draft" } });
  fireEvent.click(screen.getByRole("button", { name: "Discuss the whole page instead" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep this draft");
  expect(screen.getByRole("heading", { name: "New page discussion" })).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await selectText();
  expect(await screen.findByRole("button", { name: "Confirm passage" })).toBeTruthy();
  expect(previews).toBe(2);
});

it("requires a source reload and new selection after a mismatched preview hash", async () => {
  let latestHash = "sha256:old";
  let previews = 0;
  mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json({ ...html, content_hash: latestHash });
    if (url.includes("/anchor-preview")) {
      previews++;
      return Response.json({ content_hash: "sha256:new", byte_start: 0, byte_end: 5, selection: "narrowed", quote_text: "Text" });
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  const selectText = async () => {
    const text = (await screen.findByText("Text", { selector: "p" })).firstChild!;
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, 4); selection.addRange(range);
    fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  };
  await selectText();
  expect(await screen.findByText(/page changed\. Reload and reselect/i)).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Confirm passage" })).toBeNull();
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep my quote draft" } });
  latestHash = "sha256:new";
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  expect(await screen.findByText("The page changed. Reselect the passage and preview it again.")).toBeTruthy();
  await selectText();
  expect(await screen.findByRole("button", { name: "Confirm passage" })).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep my quote draft");
  expect(previews).toBe(2);
});

it("holds the confirmed passage and focused draft while a create request is pending", async () => {
  let finishWrite!: (response: Response) => void;
  const pendingWrite = new Promise<Response>((resolve) => { finishWrite = resolve; });
  const writes: unknown[] = [];
  const previews: string[] = [];
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json({ ...html,
      html: '<p data-pb-src="0-5">First</p><p data-pb-src="7-13">Second</p>' });
    if (url.includes("/anchor-preview")) {
      const request = JSON.parse(String(init?.body)) as { selected_text: string };
      previews.push(request.selected_text);
      return Response.json({ content_hash: "sha256:old", byte_start: 0, byte_end: 5,
        selection: "narrowed", quote_text: request.selected_text });
    }
    if (url.includes("/pages/page/discussions") && init?.method === "POST") {
      writes.push(JSON.parse(String(init.body)));
      return pendingWrite;
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  const select = async (value: string) => {
    const text = (await screen.findByText(value, { selector: "p" })).firstChild!;
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, value.length); selection.addRange(range);
  };
  await select("First");
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  const textbox = screen.getByRole("textbox", { name: "Comment" });
  fireEvent.change(textbox, { target: { value: "Draft for First" } });
  textbox.focus();
  fireEvent.submit(textbox.closest("form")!);
  expect(await screen.findByRole("status", { name: "Posting…" })).toBeTruthy();
  expect(textbox).toBe(document.activeElement);
  expect(textbox).toHaveProperty("readOnly", true);
  await select("Second");
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  fireEvent.click(screen.getByRole("button", { name: "Reselect" }));
  fireEvent.keyDown(textbox, { key: "Escape" });
  await act(async () => { await Promise.resolve(); });
  expect(previews).toEqual(["First"]);
  expect(screen.getByText("First", { selector: "blockquote" })).toBeTruthy();
  expect(writes).toEqual([{ anchor: { kind: "quote", content_hash: "sha256:old", block_start: 0, block_end: 5,
    selected_text: "First" }, body: "Draft for First" }]);
  await act(async () => { finishWrite(Response.json({ error: { code: "forbidden", message: "denied" } }, { status: 403 })); });
  expect(await screen.findByText(/You cannot post here/)).toBeTruthy();
  expect(textbox).toBe(document.activeElement);
  expect(textbox).toHaveProperty("readOnly", false);
  expect(textbox).toHaveProperty("value", "Draft for First");
  expect(writes).toHaveLength(1);
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  expect(await screen.findByText("Second", { selector: "blockquote" })).toBeTruthy();
  expect(previews).toEqual(["First", "Second"]);
});

it("does not promise a page discussion after an unexpected preview path refusal", async () => {
  mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/anchor-preview")) return Response.json({ error: {
      code: "discussion_path_refused", message: "path refused",
    } }, { status: 422 });
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  const text = (await screen.findByText("Text", { selector: "p" })).firstChild!;
  const selection = window.getSelection()!;
  selection.removeAllRanges();
  const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, 4); selection.addRange(range);
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  expect(await screen.findByText("The passage could not be previewed. Refresh the page and try again.")).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Discuss the whole page instead" })).toBeNull();
});

it("keeps the page draft when its discussion path cannot be written", async () => {
  let writes = 0;
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/pages/page/discussions") && init?.method === "POST") {
      writes++;
      return Response.json({ error: { code: "discussion_path_refused", message: "path refused" } }, { status: 422 });
    }
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "New discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep this draft" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  expect(await screen.findByText("This discussion could not be saved here. Your draft is still here.")).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep this draft");
  expect(writes).toBe(1);
});

it("keeps a confirmed passage through an unreadable source and clears it only when the bytes change", async () => {
  let unreadable = false;
  let changed = false;
  const view = mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return unreadable
      ? Response.json({ error: { code: "content_unreadable", message: "HTML unavailable" } }, { status: 503 }) :
        Response.json({ ...html, content_hash: changed ? "sha256:new" : "sha256:old" });
    if (url.includes("/anchor-preview")) return Response.json({ content_hash: "sha256:old", byte_start: 0, byte_end: 5,
      selection: "narrowed", quote_text: "Text" });
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  const text = (await screen.findByText("Text", { selector: "p" })).firstChild!;
  const selection = window.getSelection()!;
  selection.removeAllRanges();
  const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, 4); selection.addRange(range);
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep this passage draft" } });
  unreadable = true;
  await view.client.invalidateQueries({ queryKey: ["page", "html", "page"] });
  expect(await screen.findByText("HTML unavailable")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Passage confirmed" })).toBeTruthy();
  expect(screen.getByRole("button", { name: "Reselect" })).toHaveProperty("disabled", true);
  expect(screen.getByText("The page could not be read. Reload it before posting.")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  unreadable = false;
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", false));
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  expect(screen.getByRole("button", { name: "Passage confirmed" })).toBeTruthy();
  unreadable = true;
  await view.client.invalidateQueries({ queryKey: ["page", "html", "page"] });
  expect(await screen.findByText("HTML unavailable")).toBeTruthy();
  changed = true;
  unreadable = false;
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  await waitFor(() => expect(screen.queryByRole("button", { name: "Passage confirmed" })).toBeNull());
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep this passage draft");
});

it("opens the page composer from selection help", async () => {
  mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/anchor-preview")) return Response.json({ content_hash: "sha256:old", byte_start: 0, byte_end: 5,
      selection: "narrowed", quote_text: "Text" });
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  const text = (await screen.findByText("Text", { selector: "p" })).firstChild!;
  const selection = window.getSelection()!;
  selection.removeAllRanges();
  const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, 4); selection.addRange(range);
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep my draft" } });
  selection.collapse(text, 0);
  fireEvent.click(await findDiscussionButton({ name: "New discussion" }));
  fireEvent.click(screen.getByRole("button", { name: "Discuss the whole page instead" }));
  expect(await screen.findByRole("heading", { name: "New page discussion" })).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep my draft");
  expect(screen.queryByRole("button", { name: "Passage confirmed" })).toBeNull();
});

it("retries every rooted view after a posted reply refresh fails", async () => {
  let pageReads = 0;
  let failRefresh = true;
  let replies = 0;
  const item = { id: "thread", page: { id: "page", path: "note.md", resolution: "by_id" }, status: "open", state: "page_level",
    reason: null, range: null, candidates: null, placement: null, quote: null, comment_count: 0,
    starter: { key: "human", kind: "human", label: "Alice" }, created: null, updated: null,
    anchor: { kind: "page", content_hash: "sha256:old", commit: null }, reattachment: null };
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/discussions/thread/comments") && init?.method === "POST") {
      replies++;
      return Response.json({ id: "thread", comment_id: null, commit: null }, { status: 201 });
    }
    if (url.includes("/pages/page/discussions")) {
      pageReads++;
      return pageReads > 1 && failRefresh
        ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) :
          Response.json({ discussions: [item], next: null, discussions_available: true, reason: null });
    }
    if (url.includes("/discussions/thread?")) return Response.json({ discussion: item, comments: [], next: null,
      discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /About this page/ }));
  fireEvent.click(await screen.findByRole("button", { name: "Reply" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Posted reply" } });
  fireEvent.click(screen.getByRole("button", { name: "Post reply" }));
  expect(await screen.findByText(/Posted, but the view could not refresh/)).toBeTruthy();
  expect(replies).toBe(1);
  const readsBeforeRetry = pageReads;
  failRefresh = false;
  fireEvent.click(within(screen.getByText(/Posted, but the view could not refresh/).closest("[role=alert]") as HTMLElement)
    .getByRole("button", { name: "Refresh" }));
  await waitFor(() => expect(screen.queryByText(/Posted, but the view could not refresh/)).toBeNull());
  expect(pageReads).toBeGreaterThan(readsBeforeRetry);
  expect(screen.getByRole("status", { name: /Reply posted/ })).toBeTruthy();
  expect(replies).toBe(1);
});
