import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { createAppRouter } from "../router";
import { clearCsrfToken } from "../api/csrf";
import { sessionQuery, treeQuery } from "../api/queries";

const page = { id: "page", root: "extra", path: "note.md", url: "/extra/note", title: "Note", markdown: "Text", frontmatter: {}, content_hash: "sha256:old", commit: null };
const html = { ...page, html: '<p data-pb-src="0-5">Text</p>', headings: [] };

function mount(fetcher: typeof fetch, strict = false) {
  vi.stubGlobal("fetch", fetcher);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  client.setQueryData(treeQuery.queryKey, { roots: [{ root: "extra", primary: true, available: true, editable: true,
    tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/extra", page_count: 0, children: [] } }] });
  client.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  const router = createAppRouter(client, createMemoryHistory({ initialEntries: ["/extra/note"] }));
  const app = <QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>;
  return { ...render(strict ? <StrictMode>{app}</StrictMode> : app), client };
}

afterEach(() => { vi.unstubAllGlobals(); clearCsrfToken(); });

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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "New page discussion" }));
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

it("keeps discussion reading available while known unsupported roots hide passage creation", async () => {
  const view = mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(html);
    if (url.includes("/pages/page/discussions")) return Response.json({
      discussions: [], next: null, discussions_available: false, reason: "object_storage",
    });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  expect(await screen.findByRole("button", { name: "Comment on selection" })).toBeTruthy();
  view.client.setQueryData(treeQuery.queryKey, { roots: [{ root: "extra", primary: true, available: true, editable: false,
    tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/extra", page_count: 0, children: [] } }] });
  await waitFor(() => expect(screen.queryByRole("button", { name: "Comment on selection" })).toBeNull());
  expect(screen.getByRole("button", { name: "Show discussions" })).toBeTruthy();
  view.client.setQueryData(treeQuery.queryKey, { roots: [{ root: "extra", primary: true, available: true, editable: true,
    tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/extra", page_count: 0, children: [] } }] });
  await screen.findByRole("button", { name: "Comment on selection" });
  fireEvent.click(screen.getByRole("button", { name: "Show discussions" }));
  expect(await screen.findByText("Discussions are not available for this storage type")).toBeTruthy();
  await waitFor(() => expect(screen.queryByRole("button", { name: "Comment on selection" })).toBeNull());
  expect(screen.getByRole("button", { name: "Hide discussions" })).toBeTruthy();
});

it("keeps a page draft accessible and disables posting when its source refetch fails", async () => {
  let sourceFails = false;
  const view = mount(vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return sourceFails
      ? Response.json({ error: { code: "content_unreadable", message: "HTML unavailable" } }, { status: 503 }) : Response.json(html);
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [], next: null, discussions_available: true, reason: null });
    return new Response(null, { status: 404 });
  }) as typeof fetch);
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "New page discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep this draft" } });
  sourceFails = true;
  await view.client.invalidateQueries({ queryKey: ["page", "html", "page"] });
  expect(await screen.findByText("HTML unavailable")).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep this draft");
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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "New page discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "  Draft after outage  " } });
  await view.client.invalidateQueries({ queryKey: ["page", "html", "page"] });
  expect(await screen.findByText("HTML unavailable")).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "  Draft after outage  ");
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("button", { name: "Hide discussions" }).getAttribute("aria-expanded")).toBe("true");
  expect(screen.getByRole("button", { name: "Hide discussions" }).getAttribute("aria-controls")).toBe("pb-page-discussions");
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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "New page discussion" }));
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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "New page discussion" }));
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
    fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "New page discussion" }));
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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  expect(await screen.findByRole("button", { name: "note.md" })).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Load more" }));
  expect(await screen.findByText("No discussions in this window. Load more to continue.")).toBeTruthy();
  expect(screen.getByRole("button", { name: "note.md" })).toBeTruthy();
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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "New page discussion" }));
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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "New page discussion" }));
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
    fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
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
    fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
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
  fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  const textbox = screen.getByRole("textbox", { name: "Comment" });
  fireEvent.change(textbox, { target: { value: "Draft for First" } });
  textbox.focus();
  fireEvent.submit(textbox.closest("form")!);
  expect(await screen.findByRole("status", { name: "Posting…" })).toBeTruthy();
  expect(textbox).toBe(document.activeElement);
  expect(textbox).toHaveProperty("readOnly", true);
  await select("Second");
  fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
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
  fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
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
  fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "New page discussion" }));
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
  fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
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
  fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep my draft" } });
  selection.collapse(text, 0);
  fireEvent.click(screen.getByRole("button", { name: "Comment on selection" }));
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
  fireEvent.click(await screen.findByRole("button", { name: "Show discussions" }));
  fireEvent.click(await screen.findByRole("button", { name: "note.md" }));
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
