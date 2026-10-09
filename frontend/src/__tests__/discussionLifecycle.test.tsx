import { discussionButton, findDiscussionButton } from "./discussionInteractions";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { clearCsrfToken } from "../api/csrf";
import { discussionDetailQuery, pageDiscussionsQuery } from "../api/discussions";
import { pageByPathQuery, pageHtmlQuery, sessionQuery, treeQuery } from "../api/queries";
import type { DiscussionDetailResponse } from "../api/types";
import { createAppRouter } from "../router";
import { DiscussionReattach } from "../components/DiscussionReattach";

const actor = { key: "opaque", kind: "human" as const, label: "Admin" };
const original = { kind: "quote" as const, content_hash: "sha256:original", commit: null, quote: "Original evidence",
  prefix: "", suffix: "", byte_start: 0, byte_end: 17, body_start: 0, line: 1, selection: "narrowed" as const, heading_path: [] };
function envelope(): DiscussionDetailResponse {
  return { discussion: { id: "thread", page: { id: "page", path: "note.md", resolution: "by_id" }, status: "open",
    state: "exact", reason: null, range: null, candidates: null, placement: null, quote: "Original evidence",
    comment_count: 1, starter: actor, created: null, updated: null, anchor: original, reattachment: null },
  comments: [{ id: "comment", author: actor, created: "2026-09-29T00:00:00Z", edited_at: null, retracted: false,
    markdown: "**Raw Markdown** 😀", html: "<p><strong>Rendered body</strong> 😀</p>" }],
  next: null, discussions_available: true, reason: null };
}
const page = { id: "page", root: "extra", path: "note.md", url: "/extra/note", title: "Note", markdown: "Text",
  frontmatter: {}, content_hash: "sha256:current", commit: null };
const source = { ...page, slug: "note", citation: { page_id: page.id, heading_id: null, path: page.path,
  content_hash: page.content_hash, commit: null, uri: "plainbase://page/page" },
  html: '<p data-pb-src="0-14">banana 😀</p><p data-pb-src="16-26">Next quote</p>', headings: [] };
function mount(fetcher: typeof fetch, at = "/discussions/extra/thread", strict = false, builtin = false, disabled = false, primeTree = true) {
  vi.stubGlobal("fetch", fetcher);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  client.setQueryData(sessionQuery.queryKey, { authenticated: builtin, username: builtin ? "Admin" : null,
    csrf_token: null, auth_mode: builtin ? "builtin" : "off" });
  if (primeTree) client.setQueryData(treeQuery.queryKey, { roots: ["extra", "docs"].map((root) => ({ root, ...(disabled && root === "extra" ? { discussionsEnabled: false } : {}), primary: root === "extra",
    available: true, editable: true, tree: { type: "folder" as const, name: "", title: null, description: null, path: "", url: `/${root}`, page_count: 0, children: [] } })) });
  const router = createAppRouter(client, createMemoryHistory({ initialEntries: [at] }));
  const app = <QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>;
  return { ...render(strict ? <StrictMode>{app}</StrictMode> : app), client, history: router.history };
}
function fixture(response = envelope(), post?: (url: string, init: RequestInit) => Promise<Response>) {
  const calls: { url: string; body: unknown }[] = [];
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url === "/api/v1/session") return Response.json({ csrf_token: null, auth_mode: "off", authenticated: false });
    if (init?.method === "POST") {
      calls.push({ url, body: JSON.parse(String(init.body)) });
      if (post) return post(url, init);
      return Response.json({ id: "thread", comment_id: null, commit: null }, { status: url.includes("/pages/page/discussions?") ? 201 : 200 });
    }
    if (url.includes("/pages/by-path/")) return Response.json(page);
    if (url.includes("/pages/page/html")) return Response.json(source);
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [response.discussion], next: null,
      discussions_available: true, reason: null });
    if (url.includes("/discussions/thread?")) return Response.json(response);
    return new Response(null, { status: 404 });
  }) as typeof fetch;
  return { fetcher, calls };
}
afterEach(() => { vi.unstubAllGlobals(); clearCsrfToken(); window.getSelection()?.removeAllRanges(); });

it.each([null, "next"])("simplifies only a confirmed empty page discussion list with cursor %s", async (next) => {
  const base = fixture().fetcher;
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input).includes("/pages/page/discussions")
    ? Response.json({ discussions: [], next, discussions_available: true, reason: null }) : base(input, init)) as typeof fetch, "/extra/note");
  expect(await screen.findByRole("button", { name: "Start a discussion" })).toBeTruthy();
  expect(screen.getAllByRole("button", { name: "Start a discussion" })).toHaveLength(1);
  if (next) {
    expect(screen.getByRole("group", { name: "Discussion status" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Load more" })).toBeTruthy();
  } else {
    expect(screen.queryByRole("group", { name: "Discussion status" })).toBeNull();
    expect(screen.queryByText("Start the conversation")).toBeNull();
  }
});

it("expands and collapses the full immutable original passage without substituting the latest attachment", async () => {
  const quote = "Before the release, confirm the rollback owner and the last known good version. ".repeat(8);
  const data = envelope();
  data.discussion!.anchor = { ...original, quote };
  data.discussion!.reattachment = { anchor: { ...original, quote: "A newer, shorter passage" }, by: actor, at: "2026-09-30T00:00:00Z" };
  const f = fixture(data); mount(f.fetcher);
  const toggle = await screen.findByRole("button", { name: "Show full passage" });
  const quotation = document.getElementById(toggle.getAttribute("aria-controls")!)!;
  expect(quotation.textContent).toBe(quote);
  expect(toggle.getAttribute("aria-expanded")).toBe("false");
  fireEvent.click(toggle);
  expect(screen.getByRole("button", { name: "Collapse passage" }).getAttribute("aria-expanded")).toBe("true");
  expect(quotation.textContent).toBe(quote);
  fireEvent.click(toggle);
  expect(screen.getByRole("button", { name: "Show full passage" }).getAttribute("aria-expanded")).toBe("false");
  expect(quotation.textContent).toBe(quote);
  fireEvent.click(screen.getByText("Discussion details", { selector: "summary" }));
  expect(screen.getByText("A newer, shorter passage")).toBeTruthy();
  expect(f.calls).toHaveLength(0);
});

it("leaves a short original passage uncluttered", async () => {
  mount(fixture().fetcher);
  await screen.findByText("Original evidence", { selector: "blockquote" });
  expect(screen.queryByRole("button", { name: "Show full passage" })).toBeNull();
});

it.each(["resolved", "unavailable"])("returns reply Cancel to the heading after the thread becomes %s", async (change) => {
  const data = envelope(); const f = fixture(data); const view = mount(f.fetcher);
  fireEvent.click(await screen.findByRole("button", { name: "Reply" }));
  if (change === "resolved") data.discussion!.status = "resolved";
  else data.discussions_available = false;
  await act(async () => { await view.client.invalidateQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.getByRole("heading", { name: "Discussion on note.md" })).toBe(document.activeElement));
  expect(f.calls).toHaveLength(0);
});

it("returns Cancel to the thread heading when the edited comment disappears", async () => {
  const data = envelope(); const f = fixture(data); const view = mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep my unsaved edit" } });
  data.comments = [];
  await act(async () => { await view.client.invalidateQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep my unsaved edit");
  await waitFor(() => expect(screen.getByRole("button", { name: "Save comment" })).toHaveProperty("disabled", true));
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.getByRole("heading", { name: "Discussion on note.md" })).toBe(document.activeElement));
  expect(f.calls).toHaveLength(0);
});

it("closes an unrelated action disclosure with Escape while retaining the active edit", async () => {
  const f = fixture(); mount(f.fetcher);
  const edit = await findDiscussionButton({ name: "Edit comment" });
  fireEvent.click(edit);
  expect(edit.closest("details")).toHaveProperty("open", false);
  const field = screen.getByRole("textbox", { name: "Comment" });
  fireEvent.change(field, { target: { value: "Retain this edit" } });
  const trigger = screen.getByLabelText("Discussion actions");
  fireEvent.click(trigger);
  expect(trigger.parentElement).toHaveProperty("open", true);
  fireEvent.keyDown(trigger, { key: "Escape" });
  expect(trigger.parentElement).toHaveProperty("open", false);
  expect(trigger).toBe(document.activeElement);
  expect(field).toHaveProperty("value", "Retain this edit");
  expect(f.calls).toHaveLength(0);
});

it("returns Cancel to the initiating action when its comment disclosure has been reopened", async () => {
  const f = fixture(); mount(f.fetcher);
  const edit = await findDiscussionButton({ name: "Edit comment" });
  fireEvent.click(edit);
  const trigger = screen.getByLabelText("Actions for Admin's comment");
  fireEvent.click(trigger);
  expect(trigger.parentElement).toHaveProperty("open", true);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(edit).toBe(document.activeElement));
  expect(f.calls).toHaveLength(0);
});

it.each([
  { operation: "reply", server: false }, { operation: "edit", server: false },
  { operation: "reply", server: true }, { operation: "edit", server: true },
])("omits recovery for empty $operation validation (server: $server) while retaining the draft", async ({ operation, server }) => {
  const f = fixture(envelope(), async () => Response.json({ error: { code: "comment_empty", message: "empty" } }, { status: 400 }));
  mount(f.fetcher);
  if (operation === "reply") fireEvent.click(await screen.findByRole("button", { name: "Reply" }));
  else fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  const field = screen.getByRole("textbox", { name: "Comment" });
  const draft = server ? "Keep this unsaved comment" : "   ";
  fireEvent.change(field, { target: { value: draft } });
  fireEvent.click(screen.getByRole("button", { name: operation === "reply" ? "Post reply" : "Save comment" }));
  const notice = await screen.findByRole("alert");
  expect(notice.textContent).toContain(operation === "reply" ? "Enter a comment before posting." : "Enter a comment before saving.");
  expect(within(notice).queryByRole("button", { name: "Refresh" })).toBeNull();
  expect(within(field.closest("form")!).queryByRole("button", { name: "Refresh" })).toBeNull();
  // jsdom exposes buttons in closed native details: none may be an outside recovery control.
  expect(screen.queryAllByRole("button", { name: "Refresh" }).every((button) =>
    button.closest("details") !== null && !button.closest("details")!.open)).toBe(true);
  expect(field).toHaveProperty("value", draft);
  expect(f.calls).toHaveLength(server ? 1 : 0);
});

it.each(["reply", "edit"])("keeps recovery beside a retained uncertain %s notice after a fresh inspection", async (operation) => {
  const f = fixture(envelope(), async () => { throw new TypeError("connection lost"); });
  mount(f.fetcher);
  if (operation === "reply") fireEvent.click(await screen.findByRole("button", { name: "Reply" }));
  else fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  const field = screen.getByRole("textbox", { name: "Comment" });
  fireEvent.change(field, { target: { value: "Keep this reply for inspection" } });
  const submitLabel = operation === "reply" ? "Post reply" : "Save comment";
  fireEvent.click(screen.getByRole("button", { name: submitLabel }));
  const notice = await screen.findByRole("alert");
  expect(notice.textContent).toContain("outcome is unclear");
  fireEvent.click(within(notice).getByRole("button", { name: "Refresh" }));
  await waitFor(() => expect(screen.getByRole("button", { name: submitLabel })).toHaveProperty("disabled", false));
  expect(within(notice).getByRole("button", { name: "Refresh" }).closest("details")).toBeNull();
  expect(field).toHaveProperty("value", "Keep this reply for inspection");
  expect(f.calls).toHaveLength(1);
});

it.each(["reply", "edit"])("fresh disabled metadata preserves a page-panel %s buffer without reopening its action", async (kind) => {
  const data = envelope();
  const other = envelope(); other.discussion!.id = "other"; other.discussion!.quote = "Other evidence";
  other.comments[0].id = "other-comment"; other.comments[0].markdown = "Other raw comment";
  const f = fixture(data);
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/discussions/other?")) return Response.json(other);
    if (url.includes("/pages/page/discussions")) return Response.json({ discussions: [data.discussion, other.discussion],
      next: null, discussions_available: true, reason: null });
    return f.fetcher(input, init);
  }) as typeof fetch, "/extra/note");
  const open = async (id = "thread") => {
    await waitFor(() => expect(view.container.querySelector(`[data-pb-discussion-id="${id}"]`)).not.toBeNull());
    fireEvent.click(view.container.querySelector(`[data-pb-discussion-id="${id}"]`)!);
    await screen.findByText("Rendered body");
  };
  await open();
  if (kind === "reply") fireEvent.click(await findDiscussionButton({ name: "Reply" }));
  else fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: `Preserve my ${kind}` } });
  const setEnabled = async (enabled: boolean) => {
    await act(async () => { view.client.setQueryData(treeQuery.queryKey, { roots: [{ root: "extra", primary: true,
      available: true, editable: true, discussionsEnabled: enabled, tree: { type: "folder", name: "", title: null,
        description: null, path: "", url: "/extra", page_count: 0, children: [] } }] }); });
  };
  await setEnabled(false);
  await waitFor(() => expect(view.container.querySelector(".pb-margin-discussions")).toBeNull());
  expect(screen.queryByRole("textbox")).toBeNull();
  await setEnabled(true);
  await open();
  expect(screen.queryByRole("textbox")).toBeNull();
  // All discussions is nondestructive; visiting another thread cannot inherit this thread's buffer.
  fireEvent.click(screen.getByRole("button", { name: "Close discussion" }));
  await open("other");
  if (kind === "reply") fireEvent.click(await findDiscussionButton({ name: "Reply" }));
  else fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", kind === "reply" ? "" : "Other raw comment");
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  fireEvent.click(screen.getByRole("button", { name: "Close discussion" }));
  await open();
  expect(screen.queryByRole("textbox")).toBeNull();
  if (kind === "reply") fireEvent.click(await findDiscussionButton({ name: "Reply" }));
  else fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", `Preserve my ${kind}`);
  if (kind === "edit") expect(screen.getByText("Restored unsaved edit. Compare it with the current comment before saving.")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  fireEvent.click(screen.getByRole("button", { name: "Close discussion" }));
  await open();
  if (kind === "reply") fireEvent.click(await findDiscussionButton({ name: "Reply" }));
  else fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", kind === "reply" ? "" : data.comments[0].markdown);
  expect(screen.queryByText("Restored unsaved edit. Compare it with the current comment before saving.")).toBeNull();
  expect(f.calls).toHaveLength(0);
});

it.each(["create", "reply", "edit"])("retires a successful %s draft completed while configured disabled", async (kind) => {
  const data = envelope();
  let finish!: (response: Response) => void;
  const f = fixture(data, async () => new Promise<Response>((resolve) => { finish = resolve; }));
  const view = mount(f.fetcher, "/extra/note", true);
  const open = async () => {
    if (kind !== "create") {
      await waitFor(() => expect(view.container.querySelector('[data-pb-discussion-id="thread"]')).not.toBeNull());
      fireEvent.click(view.container.querySelector('[data-pb-discussion-id="thread"]')!);
      await screen.findByText("Rendered body");
    }
    fireEvent.click(await findDiscussionButton({ name: kind === "create" ? "Start a discussion" : kind === "reply" ? "Reply" : "Edit comment" }));
  };
  const setEnabled = async (enabled: boolean) => {
    await act(async () => { view.client.setQueryData(treeQuery.queryKey, { roots: [{ root: "extra", primary: true,
      available: true, editable: true, discussionsEnabled: enabled, tree: { type: "folder", name: "", title: null,
        description: null, path: "", url: "/extra", page_count: 0, children: [] } }] }); });
    await waitFor(() => expect(view.container.querySelector(".pb-margin-discussions") !== null).toBe(enabled));
  };
  await open();
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: `Submitted ${kind}` } });
  fireEvent.click(screen.getByRole("button", { name: kind === "create" ? "Create discussion" : kind === "reply" ? "Post reply" : "Save comment" }));
  await waitFor(() => expect(f.calls).toHaveLength(1));
  await setEnabled(false);
  const ordinaryLink = screen.getByRole("link", { name: "Edit this page" });
  ordinaryLink.focus();
  if (kind === "edit") data.comments[0].markdown = "Canonical saved edit\n";
  await act(async () => { finish(Response.json({ id: "thread", comment_id: null, commit: null }, { status: kind === "edit" ? 200 : 201 })); });
  await waitFor(() => expect(view.client.isMutating()).toBe(0));
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  expect(view.container.querySelector(".pb-margin-discussions")).toBeNull();
  expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
  expect(document.activeElement).toBe(ordinaryLink);
  await setEnabled(true);
  expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
  await open();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", kind === "edit" ? data.comments[0].markdown : "");
  expect(f.calls).toHaveLength(1);
});

it.each(["create", "reply", "edit"])("preserves a newer %s buffer when the prior request succeeds after another disable", async (kind) => {
  const data = envelope();
  let finish!: (response: Response) => void;
  const f = fixture(data, async () => new Promise<Response>((resolve) => { finish = resolve; }));
  const view = mount(f.fetcher, "/extra/note", true);
  const open = async () => {
    if (kind !== "create") {
      await waitFor(() => expect(view.container.querySelector('[data-pb-discussion-id="thread"]')).not.toBeNull());
      fireEvent.click(view.container.querySelector('[data-pb-discussion-id="thread"]')!);
      await screen.findByText("Rendered body");
    }
    fireEvent.click(await findDiscussionButton({ name: kind === "create" ? "Start a discussion" : kind === "reply" ? "Reply" : "Edit comment" }));
  };
  const setEnabled = async (enabled: boolean) => {
    await act(async () => { view.client.setQueryData(treeQuery.queryKey, { roots: [{ root: "extra", primary: true,
      available: true, editable: true, discussionsEnabled: enabled, tree: { type: "folder", name: "", title: null,
        description: null, path: "", url: "/extra", page_count: 0, children: [] } }] }); });
    await waitFor(() => expect(view.container.querySelector(".pb-margin-discussions") !== null).toBe(enabled));
  };
  await open();
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: `Old ${kind}` } });
  fireEvent.click(screen.getByRole("button", { name: kind === "create" ? "Create discussion" : kind === "reply" ? "Post reply" : "Save comment" }));
  await waitFor(() => expect(f.calls).toHaveLength(1));
  await setEnabled(false);
  await setEnabled(true);
  await open();
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: `New unsaved ${kind}` } });
  await setEnabled(false);
  await act(async () => { finish(Response.json({ id: "thread", comment_id: null, commit: null }, { status: kind === "edit" ? 200 : 201 })); });
  await waitFor(() => expect(view.client.isMutating()).toBe(0));
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  await setEnabled(true);
  expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
  await open();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", `New unsaved ${kind}`);
  expect(f.calls).toHaveLength(1);
});

it.each(["create", "reply", "edit"])("preserves a failed %s submission completed while configured disabled", async (kind) => {
  let finish!: (response: Response) => void;
  const f = fixture(envelope(), async () => new Promise<Response>((resolve) => { finish = resolve; }));
  const view = mount(f.fetcher, "/extra/note", true);
  const open = async () => {
    if (kind !== "create") {
      await waitFor(() => expect(view.container.querySelector('[data-pb-discussion-id="thread"]')).not.toBeNull());
      fireEvent.click(view.container.querySelector('[data-pb-discussion-id="thread"]')!);
      await screen.findByText("Rendered body");
    }
    fireEvent.click(await findDiscussionButton({ name: kind === "create" ? "Start a discussion" : kind === "reply" ? "Reply" : "Edit comment" }));
  };
  const setEnabled = async (enabled: boolean) => {
    await act(async () => { view.client.setQueryData(treeQuery.queryKey, { roots: [{ root: "extra", primary: true,
      available: true, editable: true, discussionsEnabled: enabled, tree: { type: "folder", name: "", title: null,
        description: null, path: "", url: "/extra", page_count: 0, children: [] } }] }); });
    await waitFor(() => expect(view.container.querySelector(".pb-margin-discussions") !== null).toBe(enabled));
  };
  await open();
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: `Failed ${kind}` } });
  fireEvent.click(screen.getByRole("button", { name: kind === "create" ? "Create discussion" : kind === "reply" ? "Post reply" : "Save comment" }));
  await waitFor(() => expect(f.calls).toHaveLength(1));
  await setEnabled(false);
  await act(async () => { finish(Response.json({ error: { code: "discussions_disabled", message: "Disabled" } }, { status: 403 })); });
  await waitFor(() => expect(view.client.isMutating()).toBe(0));
  await setEnabled(true);
  expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
  await open();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", `Failed ${kind}`);
  expect(f.calls).toHaveLength(1);
});

it.each(["root", "page"])("detail disablement and its draft stay scoped when the %s owner changes", async (change) => {
  const data = envelope(); const f = fixture(data);
  const next = change === "root" ? { ...page, root: "docs", url: "/docs/note" } : { ...page, id: "other", path: "other.md", url: "/extra/other" };
  const nextDetail = envelope(); nextDetail.discussion!.page = { id: next.id, path: next.path, resolution: "by_id" };
  if (change === "page") nextDetail.discussion!.id = "other-thread";
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url === "/api/v1/pages/page") return Response.json({ ...page, url: null });
    if (url === "/api/v1/pages/page/html") return Response.json({ ...source, url: null });
    if (url.includes("/pages/by-path/extra/other")) return Response.json(next);
    if (url.includes("/pages/other/html")) return Response.json({ ...source, ...next });
    if (url.includes("/pages/other/discussions") || (url.includes("/pages/page/discussions") && url.includes("root=docs")))
      return Response.json({ discussions: [nextDetail.discussion], next: null, discussions_available: true, reason: null });
    if (url.includes("/discussions/thread?") && url.includes("root=docs")) return Response.json(nextDetail);
    if (url.includes("/discussions/other-thread?")) return Response.json(nextDetail);
    return f.fetcher(input, init);
  }) as typeof fetch, change === "root" ? "/p/page" : "/extra/note");
  await waitFor(() => expect(view.container.querySelector('[data-pb-discussion-id="thread"]')).not.toBeNull());
  fireEvent.click(view.container.querySelector('[data-pb-discussion-id="thread"]')!);
  fireEvent.click(await findDiscussionButton({ name: "Reply" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Only the old workspace" } });
  const unavailable = { discussion: null, comments: [], next: null, discussions_available: false, reason: "disabled_by_config" };
  await act(async () => { view.client.setQueryData(discussionDetailQuery("extra", "thread").queryKey,
    { pages: [unavailable], pageParams: [null] }); });
  await waitFor(() => expect(view.container.querySelector(".pb-margin-discussions")).toBeNull());
  if (change === "root") await act(async () => { view.client.setQueryData(pageHtmlQuery("page", null).queryKey, { ...source, ...next }); });
  else act(() => view.history.push(next.url));
  await screen.findByRole("region", { name: "Page discussions" });
  await act(async () => { view.client.setQueryData(discussionDetailQuery("extra", "thread").queryKey,
    { pages: [unavailable], pageParams: [null] }); });
  expect(screen.getByRole("region", { name: "Page discussions" })).toBeTruthy();
  expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
  fireEvent.click(await findDiscussionButton({ name: "Start a discussion" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "");
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(view.container.querySelector(`[data-pb-discussion-id="${nextDetail.discussion!.id}"]`)).not.toBeNull());
  fireEvent.click(view.container.querySelector(`[data-pb-discussion-id="${nextDetail.discussion!.id}"]`)!);
  fireEvent.click(await findDiscussionButton({ name: "Reply" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "");
  expect(f.calls).toHaveLength(0);
});

it.each(["create", "reply", "edit"])("detail-only disablement hides the whole page workspace and retains its %s draft", async (kind) => {
  const data = envelope();
  data.discussion!.range = { byte_start: 0, byte_end: 14 };
  data.discussion!.range_content_hash = source.content_hash;
  const enabledList = { discussions: [data.discussion], next: null, discussions_available: true, reason: null };
  const f = fixture(data);
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input).includes("/pages/page/discussions")
    ? Response.json(enabledList) : f.fetcher(input, init)) as typeof fetch;
  const view = mount(fetcher, "/extra/note");
  const openThread = async () => {
    await waitFor(() => expect(view.container.querySelector('[data-pb-discussion-id="thread"]')).not.toBeNull());
    fireEvent.click(view.container.querySelector('[data-pb-discussion-id="thread"]')!);
    await screen.findByText("Rendered body");
  };
  await openThread();
  await waitFor(() => expect(view.container.querySelector(".pb-discussion-passage")).not.toBeNull());
  if (kind === "create") fireEvent.click(await findDiscussionButton({ name: "Start a discussion" }));
  else fireEvent.click(await findDiscussionButton({ name: kind === "reply" ? "Reply" : "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: `Preserve detail-only ${kind}` } });
  const cachedTree = view.client.getQueryData(treeQuery.queryKey);
  const cachedList = view.client.getQueryData(pageDiscussionsQuery("extra", "page").queryKey);
  const unavailable = { discussion: null, comments: [], next: null, discussions_available: false, reason: "disabled_by_config" };
  if (kind === "create") {
    // A detail answer can reach the cache after returning to creation; its owner remains this workspace.
    await act(async () => { view.client.setQueryData(discussionDetailQuery("extra", "thread").queryKey,
      { pages: [unavailable], pageParams: [null] }); });
  } else {
    Object.assign(data, unavailable);
    await act(async () => { await view.client.invalidateQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  }
  await waitFor(() => expect(view.container.querySelector(".pb-margin-discussions")).toBeNull());
  expect(view.container.querySelector(".pb-discussion-passage")).toBeNull();
  expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Start a discussion" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Close discussion" })).toBeNull();
  expect(screen.getByText("banana 😀")).toBeTruthy();
  expect(screen.getByRole("link", { name: "Edit this page" })).toBeTruthy();
  expect(view.client.getQueryData(treeQuery.queryKey)).toBe(cachedTree);
  expect(view.client.getQueryData(pageDiscussionsQuery("extra", "page").queryKey)).toBe(cachedList);
  const reads = (fetcher as ReturnType<typeof vi.fn>).mock.calls.length;
  fireEvent(window, new Event("focus")); fireEvent(window, new Event("online"));
  const selection = window.getSelection()!;
  const range = document.createRange(); range.selectNodeContents(screen.getByText("banana 😀").firstChild!);
  selection.removeAllRanges(); selection.addRange(range); fireEvent(document, new Event("selectionchange"));
  await act(async () => { await Promise.resolve(); });
  expect((fetcher as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(reads);
  expect(f.calls).toHaveLength(0);
  // A deliberate fresh enabled answer restores eligibility, never the previous active action.
  Object.assign(data, envelope());
  await act(async () => { view.client.setQueryData(discussionDetailQuery("extra", "thread").queryKey,
    { pages: [data], pageParams: [null] }); });
  await screen.findByRole("region", { name: "Page discussions" });
  expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Close discussion" })).toBeNull();
  selection.removeAllRanges();
  if (kind === "create") fireEvent.click(await findDiscussionButton({ name: "Start a discussion" }));
  else {
    await openThread();
    fireEvent.click(await findDiscussionButton({ name: kind === "reply" ? "Reply" : "Edit comment" }));
  }
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", `Preserve detail-only ${kind}`);
  expect(f.calls).toHaveLength(0);
});

it("detail-only disablement also hides the discussion escape link after a same-page HTML failure", async () => {
  let sourceFailed = false;
  const f = fixture();
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) =>
    sourceFailed && String(input).includes("/pages/page/html")
      ? Response.json({ error: { code: "root_unavailable", message: "Source unavailable" } }, { status: 503 })
      : f.fetcher(input, init)) as typeof fetch, "/extra/note");
  await waitFor(() => expect(view.container.querySelector('[data-pb-discussion-id="thread"]')).not.toBeNull());
  fireEvent.click(view.container.querySelector('[data-pb-discussion-id="thread"]')!);
  await screen.findByText("Rendered body");
  await act(async () => { view.client.setQueryData(discussionDetailQuery("extra", "thread").queryKey, { pages: [{
    discussion: null, comments: [], next: null, discussions_available: false, reason: "disabled_by_config",
  }], pageParams: [null] }); });
  await waitFor(() => expect(view.container.querySelector(".pb-margin-discussions")).toBeNull());
  sourceFailed = true;
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageHtmlQuery("page", "extra").queryKey }); });
  await screen.findByText("Source unavailable");
  expect(screen.queryByRole("link", { name: "Discussions in extra" })).toBeNull();
  expect(screen.getByText("File")).toBeTruthy();
});

it("keeps focus on the margin toggle when a delayed edit succeeds while hidden", async () => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const f = fixture(envelope(), async () => pending);
  mount(f.fetcher, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Delayed edit" } });
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await waitFor(() => expect(f.calls).toHaveLength(1));
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  const toggle = screen.getByRole("button", { name: "Show discussions" });
  expect(toggle).toBe(document.activeElement);
  await act(async () => { finish(Response.json({ id: "thread", comment_id: null, commit: null })); });
  await waitFor(() => expect(screen.getByRole("status", { name: "Comment saved", hidden: true })).toBeTruthy());
  expect(toggle).toBe(document.activeElement);
  expect(screen.queryByRole("textbox")).toBeNull();
  fireEvent.click(toggle);
  expect(screen.getByRole("status", { name: "Comment saved" })).toBeTruthy();
  expect(f.calls).toEqual([{ url: "/api/v1/discussions/thread/comments/comment/edit?root=extra", body: { body: "Delayed edit" } }]);
});

it("retains a delayed reattachment preview while hidden without focusing its confirmation", async () => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const f = fixture(envelope(), async () => pending);
  mount(f.fetcher, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  selectText(screen.getByText("banana 😀", { selector: "p" }), 1, 4);
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  await waitFor(() => expect(f.calls).toHaveLength(1));
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  const toggle = screen.getByRole("button", { name: "Show discussions" });
  await act(async () => { finish(Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14,
    selection: "snapped", quote_text: "banana 😀" })); });
  await waitFor(() => expect(discussionButton({ name: "Confirm passage", hidden: true })).toBeTruthy());
  expect(toggle).toBe(document.activeElement);
  expect(f.calls).toHaveLength(1);
  fireEvent.click(toggle);
  await waitFor(() => expect(screen.getByRole("heading", { name: "Select a new passage in the displayed page" })).toBe(document.activeElement));
  fireEvent.click(screen.getByRole("button", { name: "Confirm passage" }));
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toBe(document.activeElement);
  expect(f.calls).toEqual([{ url: "/api/v1/pages/page/discussions/anchor-preview?root=extra",
    body: { kind: "quote", content_hash: "sha256:current", block_start: 0, block_end: 14, selected_text: "ana" } }]);
});

function selectText(element: HTMLElement, start = 0, end = element.textContent!.length) {
  const selection = window.getSelection()!;
  const range = document.createRange(); range.setStart(element.firstChild!, start); range.setEnd(element.firstChild!, end);
  selection.removeAllRanges(); selection.addRange(range); fireEvent(document, new Event("selectionchange"));
}

it("reattaches only after explicit preview and confirmation using rooted source and the original capture, preserving original evidence twice", async () => {
  const data = envelope();
  let sourceReads = 0;
  const f = fixture(data, async (url, init) => {
    const body = JSON.parse(String(init.body));
    if (url.includes("anchor-preview")) return Response.json({ content_hash: "sha256:current", byte_start: 1, byte_end: 4,
      selection: "snapped", quote_text: body.selected_text === "ana" ? "banana 😀" : "Next quote" });
    data.discussion!.reattachment = { by: { key: "opaque", label: "Admin" }, at: "2026-09-29T01:00:00Z",
      anchor: { ...original, content_hash: "sha256:current", quote: body.anchor.selected_text === "ana" ? "banana 😀" : "Next quote" } };
    return Response.json({ id: "thread", comment_id: null, commit: null });
  });
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).includes("/html")) sourceReads++;
    return f.fetcher(input, init);
  }) as typeof fetch, undefined, true);
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  expect(await screen.findByRole("heading", { name: "Select a new passage" })).toBe(document.activeElement);
  selectText(await screen.findByText("banana 😀", { selector: "p" }), 1, 4);
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  expect(await screen.findByText("Whole block selected")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  await waitFor(() => expect(screen.getByRole("button", { name: "Confirm passage" })).toBe(document.activeElement));
  // Selection alone must not change the captured passage.
  selectText(screen.getByText("Next quote", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Confirm passage" }));
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toBe(document.activeElement);
  fireEvent.click(screen.getByRole("button", { name: "Reattach discussion" }));
  expect(await screen.findByRole("status", { name: "Discussion reattached" })).toBeTruthy();
  await waitFor(() => expect(screen.getByRole("heading", { name: "Discussion on note.md" })).toBe(document.activeElement));
  expect(f.calls).toEqual([
    { url: "/api/v1/pages/page/discussions/anchor-preview?root=extra", body: { kind: "quote", content_hash: "sha256:current", block_start: 0, block_end: 14, selected_text: "ana" } },
    { url: "/api/v1/discussions/thread/reattach?root=extra", body: { anchor: { kind: "quote", content_hash: "sha256:current", block_start: 0, block_end: 14, selected_text: "ana" } } },
  ]);
  fireEvent.click(discussionButton({ name: "Reattach" }));
  selectText(await screen.findByText("Next quote", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  fireEvent.click(screen.getByRole("button", { name: "Reattach discussion" }));
  await screen.findByRole("status", { name: "Discussion reattached" });
  fireEvent.click(screen.getByText("Discussion details", { selector: "summary" }));
  await waitFor(() => expect(screen.getByRole("heading", { name: "Latest reattachment" }).closest("section")?.textContent).toContain("Next quote"));
  expect(document.querySelector(".pb-discussion-context")?.textContent).toContain("Original evidence");
  expect(sourceReads).toBe(1);
});

it("edits returned Markdown on a resolved thread, retains a dirty buffer on refresh and restores focus on cancel", async () => {
  const data = envelope(); data.discussion!.status = "resolved";
  const { fetcher, calls } = fixture(data);
  mount(fetcher);
  const edit = await findDiscussionButton({ name: "Edit comment" });
  fireEvent.click(edit);
  const text = screen.getByRole("textbox", { name: "Comment" });
  expect(text).toHaveProperty("value", "**Raw Markdown** 😀");
  expect(text).toBe(document.activeElement);
  fireEvent.change(text, { target: { value: "  **My dirty edit** 😀  " } });
  data.comments[0].markdown = "Someone else's edit";
  fireEvent(window, new Event("focus"));
  await waitFor(() => expect(text).toHaveProperty("value", "  **My dirty edit** 😀  "));
  expect(discussionButton({ name: "Reopen discussion" })).toHaveProperty("disabled", true);
  fireEvent.keyDown(text, { key: "Escape" });
  expect(text).toHaveProperty("value", "  **My dirty edit** 😀  ");
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  expect(await screen.findByRole("status", { name: "Comment saved" })).toBeTruthy();
  await waitFor(() => expect(screen.getByRole("heading", { name: "Discussion on note.md" })).toBe(document.activeElement));
  expect(calls).toEqual([{ url: "/api/v1/discussions/thread/comments/comment/edit?root=extra", body: { body: "  **My dirty edit** 😀  " } }]);
  const refreshedEdit = await findDiscussionButton({ name: "Edit comment" });
  await waitFor(() => expect(refreshedEdit).toHaveProperty("disabled", false));
  fireEvent.click(refreshedEdit);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.getByLabelText("Actions for Admin's comment")).toBe(document.activeElement));
});

it.each(["retract", "purge"] as const)("requires explicit %s confirmation and suppresses old content after a confirmed write with failed refresh", async (action) => {
  const data = envelope(); data.discussion!.status = "resolved";
  let failed = false;
  const f = fixture(data, async () => { failed = true; return Response.json({ id: "thread", comment_id: null, commit: null }); });
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failed && init?.method !== "POST" && String(input).includes("/discussions/thread?")
    ? Response.json({ error: { code: "content_unreadable", message: "refresh unavailable" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch);
  const trigger = await findDiscussionButton({ name: action === "purge" ? "Purge comment" : "Retract comment" });
  fireEvent.click(trigger);
  const confirmation = screen.getByRole("region", { name: action === "purge" ? "Confirm purge" : "Confirm retraction" });
  expect(within(confirmation).getByText(action === "purge" ? "Remove this comment file. Earlier Git history may still contain it." :
    "Replace this comment with a final retraction notice. It cannot be edited or restored here.")).toBeTruthy();
  expect(within(confirmation).getByRole("button", { name: "Cancel" })).toBe(document.activeElement);
  fireEvent.keyDown(confirmation, { key: "Escape" });
  expect(f.calls).toHaveLength(0);
  await waitFor(() => expect(trigger.closest("details")?.querySelector("summary")).toBe(document.activeElement));
  fireEvent.click(trigger.closest("details")!.querySelector("summary")!);
  fireEvent.click(trigger);
  fireEvent.click(within(screen.getByRole("region", { name: action === "purge" ? "Confirm purge" : "Confirm retraction" }))
    .getByRole("button", { name: action === "purge" ? "Purge comment" : "Retract comment" }));
  expect(await screen.findByRole("status", { name: action === "purge" ? "Comment removed" : "Comment retracted" })).toBeTruthy();
  expect(await screen.findByText(/but the view could not refresh/)).toBeTruthy();
  expect(screen.queryByText("Rendered body")).toBeNull();
  expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Edit comment" })).toBeNull();
  expect(f.calls).toHaveLength(1);
  expect(screen.getByRole("heading", { name: "Discussion on note.md" })).toBe(document.activeElement);
});

it("uses the refreshed final tombstone and still allows explicit purge, with no edit or retract", async () => {
  const data = envelope();
  const f = fixture(data, async (url) => {
    if (url.includes("/retract?")) Object.assign(data.comments[0], { retracted: true, markdown: "retracted by Admin\n", html: "<p>retracted by Admin</p>" });
    if (url.includes("/purge?")) data.comments = [];
    return Response.json({ id: "thread", comment_id: null, commit: null });
  });
  mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Retract comment" }));
  fireEvent.click(within(screen.getByRole("region", { name: "Confirm retraction" })).getByRole("button", { name: "Retract comment" }));
  await screen.findByText("retracted by Admin");
  expect(screen.queryByRole("button", { name: "Edit comment" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Retract comment" })).toBeNull();
  fireEvent.click(discussionButton({ name: "Purge comment" }));
  fireEvent.click(within(screen.getByRole("region", { name: "Confirm purge" })).getByRole("button", { name: "Purge comment" }));
  await screen.findByRole("status", { name: "Comment removed" });
  await waitFor(() => expect(screen.queryByText("retracted by Admin")).toBeNull());
});

it.each([{ status: 403, code: "forbidden", message: /cannot edit this comment/ },
  { status: 403, code: "root_not_editable", message: /root is configured read-only/i },
  { status: 403, code: "discussions_unsupported", message: /discussions are unavailable here/i },
  { status: 404, code: "comment_not_found", message: /comment no longer exists/i },
  { status: 404, code: "discussion_not_found", message: /discussion no longer exists/i },
  { status: 401, code: "unauthenticated", message: /session needs attention/ },
  { status: 409, code: "stale_discussion", message: /discussion changed/i },
  { status: 503, code: "content_unreadable", message: /outcome is unclear/i }])(
  "retains edits on $status $code without replaying or guessing ownership from Admin labels", async ({ status, code, message }) => {
    const data = envelope();
    const f = fixture(data, async () => Response.json({ error: { code, message: "refused" } }, { status }));
    mount(f.fetcher, undefined, true, true);
    fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep this edit" } });
    fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
    expect(await screen.findByText(message)).toBeTruthy();
    expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep this edit");
    expect(f.calls).toHaveLength(1);
    if (status === 503) {
      expect(screen.getByRole("button", { name: "Save comment" })).toHaveProperty("disabled", true);
      fireEvent.click(discussionButton({ name: "Refresh" }));
      await waitFor(() => expect(screen.getByRole("button", { name: "Save comment" })).toHaveProperty("disabled", false));
      expect(f.calls).toHaveLength(1);
    }
  });

it("retains an edit when an external retraction arrives and returns Cancel to the visible comment disclosure", async () => {
  const data = envelope(); const f = fixture(data); const view = mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Keep this text for copying" } });
  data.comments[0].retracted = true;
  await act(async () => { await view.client.invalidateQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Keep this text for copying");
  await waitFor(() => expect(screen.getByRole("button", { name: "Save comment" })).toHaveProperty("disabled", true));
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.getByLabelText("Actions for Admin's comment")).toBe(document.activeElement));
  expect(f.calls).toHaveLength(0);
});

it.each([{ kind: "retract", removed: false }, { kind: "retract", removed: true }, { kind: "purge", removed: true }])(
  "describes an unavailable $kind target without inventing unsaved text (removed=$removed)", async ({ kind, removed }) => {
    const data = envelope(); const f = fixture(data); const view = mount(f.fetcher);
    fireEvent.click(await findDiscussionButton({ name: kind === "purge" ? "Purge comment" : "Retract comment" }));
    const confirmation = screen.getByRole("region", { name: kind === "purge" ? "Confirm purge" : "Confirm retraction" });
    if (removed) data.comments = []; else data.comments[0].retracted = true;
    await act(async () => { await view.client.invalidateQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
    expect(await within(confirmation).findByText(removed ? /not in the loaded view/ : /comment was retracted/)).toBeTruthy();
    expect(confirmation.textContent).not.toMatch(/Your text|unsaved text/);
    expect(within(confirmation).getByRole("button", { name: kind === "purge" ? "Purge comment" : "Retract comment" })).toHaveProperty("disabled", true);
    expect(within(confirmation).getByRole("button", { name: "Cancel" })).toHaveProperty("disabled", false);
    expect(f.calls).toHaveLength(0);
  });

it("uses the ordinary refresh error for a later automatic failure after the confirmed edit refreshed successfully", async () => {
  let failDetail = false;
  const f = fixture();
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failDetail && init?.method !== "POST" && String(input).includes("/discussions/thread?")
    ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch);
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByRole("status", { name: "Comment saved" });
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  await waitFor(() => expect(discussionButton({ name: "Edit comment" })).toHaveProperty("disabled", false));
  expect(screen.queryByText(/but the view could not refresh/)).toBeNull();
  failDetail = true;
  fireEvent(window, new Event("focus"));
  await screen.findByText(/Refresh failed. Showing earlier discussion content/);
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  expect(screen.queryByText(/but the view could not refresh/)).toBeNull();
  expect(screen.getByRole("status", { name: "Comment saved" })).toBeTruthy();
  expect(f.calls).toHaveLength(1);
});

it.each([{ kind: "purge", code: "comment_not_found" }, { kind: "resolve", code: "discussion_not_found" }])(
  "retains a refused $kind action without inventing an edit buffer on $code", async ({ kind, code }) => {
    const f = fixture(envelope(), async () => Response.json({ error: { code, message: "gone" } }, { status: 404 }));
    mount(f.fetcher);
    fireEvent.click(await findDiscussionButton({ name: kind === "purge" ? "Purge comment" : "Resolve discussion" }));
    if (kind === "purge") fireEvent.click(within(screen.getByRole("region", { name: "Confirm purge" })).getByRole("button", { name: "Purge comment" }));
    const notice = await screen.findByRole("alert");
    expect(notice.textContent).toMatch(/no longer exists/);
    expect(notice.textContent).not.toMatch(/unsaved text/);
    expect(f.calls).toHaveLength(1);
  });

it.each(["root_unavailable", "absence_unverified"])("describes %s during pure reattachment preview as source availability", async (code) => {
  const f = fixture(envelope(), async () => Response.json({ error: { code, message: "offline" } }, { status: 503 }));
  mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  selectText(await screen.findByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  const notice = await screen.findByRole("alert");
  expect(notice.textContent).toMatch(/root is unavailable/i);
  expect(notice.textContent).not.toMatch(/outcome|change happened|could not be previewed/i);
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  expect(f.calls).toHaveLength(1);
  expect(f.calls[0].url).toContain("anchor-preview");
});

it("retains the rooted page's missing-source error in the panel's disabled HTML observer", async () => {
  let missing = false;
  const f = fixture();
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => missing && String(input).includes("/pages/page/html")
    ? Response.json({ error: { code: "page_not_found", message: "missing" } }, { status: 404 }) : f.fetcher(input, init)) as typeof fetch, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  missing = true;
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageHtmlQuery("page", "extra").queryKey, exact: true }); });
  const reattach = screen.getByRole("region", { name: "Reattach discussion" });
  expect(await within(reattach).findByText("The stored source page could not be found. The discussion remains available.")).toBeTruthy();
  expect(within(reattach).getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  expect(f.calls).toHaveLength(0);
});

it("guards synchronous double clicks and refreshes only the submitted root after navigation to an equal thread ID", async () => {
  const data = envelope(); let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const f = fixture(data, async () => pending);
  const view = mount(f.fetcher);
  const resolve = await findDiscussionButton({ name: "Resolve discussion" });
  act(() => { fireEvent.click(resolve); fireEvent.click(resolve); });
  await waitFor(() => expect(f.calls).toHaveLength(1));
  act(() => view.history.push("/discussions/docs/thread"));
  await screen.findByRole("link", { name: "Discussions in docs" });
  await findDiscussionButton({ name: "Edit comment" });
  fireEvent.click(discussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "New root draft" } });
  const readsBefore = (f.fetcher as ReturnType<typeof vi.fn>).mock.calls.length;
  await act(async () => { finish(Response.json({ id: "thread", comment_id: null, commit: null })); });
  expect(screen.queryByRole("status", { name: "Discussion resolved" })).toBeNull();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "New root draft");
  const lateReads = (f.fetcher as ReturnType<typeof vi.fn>).mock.calls.slice(readsBefore).map(([url]) => String(url));
  expect(lateReads.some((url) => url.includes("root=docs"))).toBe(false);
});

it("retains a panel edit through Hide/reopen and blocks competing creation and Close discussion", async () => {
  const f = fixture(); mount(f.fetcher, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Retain through Hide" } });
  expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("button", { name: "Close discussion" })).toHaveProperty("disabled", true);
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  fireEvent.click(screen.getByRole("button", { name: "Show discussions" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Retain through Hide");
  await waitFor(() => expect(screen.getByRole("textbox", { name: "Comment" })).toBe(document.activeElement));
  expect(f.calls).toHaveLength(0);
});

it("refuses a by-path replacement as panel reattachment source and never looks up page source for ordinary actions", async () => {
  const data = envelope(); data.discussion!.page = { id: "stored-id", path: "note.md", resolution: "by_path" };
  const f = fixture(data); mount(f.fetcher, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  expect(await screen.findByText("Open full discussion to reattach using its stored source page.")).toBeTruthy();
  expect(discussionButton({ name: "Reattach" })).toHaveProperty("disabled", true);
  fireEvent.click(discussionButton({ name: "Reattach" }));
  expect(screen.queryByRole("button", { name: "Preview selected passage" })).toBeNull();
  expect(f.calls).toHaveLength(0);
});

it.each(["orphaned", "unavailable"] as const)("keeps %s discussion actions independent of a failed stored-page lookup", async (state) => {
  const data = envelope(); data.discussion!.state = state;
  const f = fixture(data);
  let sourceReads = 0; let missing = true;
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).includes("/html")) {
      sourceReads++;
      return missing ? Response.json({ error: { code: "page_not_found", message: "missing" } }, { status: 404 }) : Response.json(source);
    }
    return f.fetcher(input, init);
  }) as typeof fetch);
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  expect(sourceReads).toBe(0);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  fireEvent.click(discussionButton({ name: "Reattach" }));
  expect(await screen.findByText(/stored source page could not be found/)).toBeTruthy();
  expect(screen.getByText("Rendered body")).toBeTruthy();
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  missing = false;
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  await screen.findByText("banana 😀", { selector: "p" });
  expect(sourceReads).toBe(2);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(discussionButton({ name: "Resolve discussion" })).toHaveProperty("disabled", false);
});

it("invalidates a confirmed capture on page_changed, reloads rooted source and requires a fresh Unicode selection", async () => {
  const data = envelope(); let hash = "sha256:current";
  const f = fixture(data, async (url, init) => url.includes("anchor-preview")
    ? Response.json({ content_hash: hash, byte_start: 0, byte_end: 14, selection: "narrowed", quote_text: JSON.parse(String(init.body)).selected_text }) :
      Response.json({ error: { code: "page_changed", message: "changed" } }, { status: 409 }));
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input).includes("/html")
    ? Response.json({ ...source, content_hash: hash }) : f.fetcher(input, init)) as typeof fetch);
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  selectText(await screen.findByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  hash = "sha256:new";
  fireEvent.click(screen.getByRole("button", { name: "Reattach discussion" }));
  expect(await screen.findByText("The page changed. Reload it and reselect the passage.")).toBeTruthy();
  await waitFor(() => expect(screen.queryByRole("button", { name: "Passage confirmed" })).toBeNull());
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  await waitFor(() => expect(view.client.getQueryData<{ content_hash: string }>(["page", "html", "page", "extra"])?.content_hash).toBe("sha256:new"));
  await waitFor(() => expect(screen.queryByText("The page changed. Reload it and reselect the passage.")).toBeNull());
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  selectText(screen.getByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  expect(screen.queryByText("The page changed. Reload it and reselect the passage.")).toBeNull();
  expect(f.calls.at(-1)?.body).toEqual({ kind: "quote", content_hash: "sha256:new", block_start: 0, block_end: 14, selected_text: "banana 😀" });
});

it("discards late previews after Reselect, Escape and Cancel, and rejects comment selections", async () => {
  const data = envelope(); const finishes: ((value: Response) => void)[] = [];
  const f = fixture(data, async () => new Promise<Response>((resolve) => finishes.push(resolve)));
  mount(f.fetcher, undefined, true);
  const trigger = await findDiscussionButton({ name: "Reattach" });
  fireEvent.click(trigger);
  selectText(await screen.findByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  await waitFor(() => expect(finishes).toHaveLength(1));
  selectText(screen.getByText("Next quote", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Reselect" }));
  await waitFor(() => expect(finishes).toHaveLength(2));
  await act(async () => { finishes[0](Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14, selection: "snapped", quote_text: "Old preview" })); });
  expect(screen.queryByText("Old preview")).toBeNull();
  await act(async () => { finishes[1](Response.json({ content_hash: "sha256:current", byte_start: 16, byte_end: 26, selection: "narrowed", quote_text: "Next quote" })); });
  const surface = screen.getByRole("region", { name: "Reattach discussion" });
  fireEvent.keyDown(surface, { key: "Escape" });
  expect(screen.getByRole("button", { name: "Preview selected passage" })).toBe(document.activeElement);
  expect(screen.queryByRole("button", { name: "Confirm passage" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(trigger.closest("details")?.querySelector("summary")).toBe(document.activeElement));
  fireEvent.click(trigger.closest("details")!.querySelector("summary")!);
  fireEvent.click(trigger);
  selectText(screen.getByText("Rendered body").parentElement!, 0, 0);
  window.getSelection()!.selectAllChildren(screen.getByText("Rendered body"));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  expect(await screen.findByText(/Select text in the page and try again/)).toBeTruthy();
  expect(f.calls).toHaveLength(2);
  selectText(screen.getByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  await waitFor(() => expect(finishes).toHaveLength(3));
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await act(async () => { finishes[2](Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14, selection: "snapped", quote_text: "Canceled preview" })); });
  expect(screen.queryByText("Canceled preview")).toBeNull();
});

it("keeps reattachment intent on an external resolve and invalidates confirmation without auto-submit after reopen", async () => {
  const data = envelope();
  const f = fixture(data, async () => Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14, selection: "narrowed", quote_text: "banana 😀" }));
  const view = mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  selectText(await screen.findByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  data.discussion!.status = "resolved";
  await act(async () => { await view.client.invalidateQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  await screen.findByText("Cancel this action, then reopen the discussion before reattaching.");
  await waitFor(() => expect(screen.queryByRole("button", { name: "Passage confirmed" })).toBeNull());
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(discussionButton({ name: "Reopen discussion" })).toHaveProperty("disabled", false);
  expect(f.calls).toHaveLength(1);
});

it.each(["sha256:current", "sha256:new"])("requires a fresh selection and preserves the ready notice after source reload to %s", async (reloadedHash) => {
  let hash = "sha256:current";
  const f = fixture(envelope(), async () => Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14,
    selection: "narrowed", quote_text: "banana 😀" }));
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input).includes("/html")
    ? Response.json({ ...source, content_hash: hash, html: hash === "sha256:current" ? source.html : source.html.replace("Next quote", "Updated quote") }) :
      f.fetcher(input, init)) as typeof fetch);
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  selectText(await screen.findByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  hash = reloadedHash;
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  if (reloadedHash === "sha256:new") await screen.findByText("Updated quote", { selector: "p" });
  const readyNotice = await screen.findByText("The page is ready. Reselect the passage and preview it again.");
  expect(readyNotice.getAttribute("role")).toBe("status");
  expect(screen.queryByRole("button", { name: "Passage confirmed" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  expect(await screen.findByText(/Select text in the page and try again/)).toBeTruthy();
  expect(screen.queryByText("The page is ready. Reselect the passage and preview it again.")).toBeNull();
  expect(f.calls).toHaveLength(1);
});

it("does not treat source reload as full thread inspection after an uncertain reattachment", async () => {
  const f = fixture(envelope(), async (url) => {
    if (url.includes("anchor-preview")) return Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14,
      selection: "narrowed", quote_text: "banana 😀" });
    throw new TypeError("connection lost");
  });
  const view = mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  selectText(await screen.findByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  fireEvent.click(screen.getByRole("button", { name: "Reattach discussion" }));
  await screen.findByText(/outcome is unclear/i);
  fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
  await screen.findByText("The page is ready. Reselect the passage and preview it again.");
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  expect(screen.getByText("Refresh and inspect whether the change happened before trying again.", { selector: "p" })).toBeTruthy();
  expect(screen.getByRole("button", { name: "Preview selected passage" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.getByRole("button", { name: "Reply" })).toHaveProperty("disabled", true);
  fireEvent.click(discussionButton({ name: "Refresh" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Reply" })).toHaveProperty("disabled", false));
  expect(f.calls).toHaveLength(2);
});

it("rejects an active comment selection even when an earlier article candidate was retained", async () => {
  const f = fixture(envelope(), async () => Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14,
    selection: "narrowed", quote_text: "banana 😀" }));
  mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  selectText(await screen.findByText("banana 😀", { selector: "p" }));
  window.getSelection()!.selectAllChildren(screen.getByText("Rendered body"));
  fireEvent(document, new Event("selectionchange"));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  expect(await screen.findByText(/Select text in the page and try again/)).toBeTruthy();
  expect(f.calls).toHaveLength(0);
});

it.each(["page", "missing_id", "resolved"] as const)("does not start quote reattachment for %s", async (kind) => {
  const data = envelope();
  if (kind === "page") data.discussion!.anchor = { kind: "page", content_hash: "sha256:current", commit: null };
  if (kind === "missing_id") data.discussion!.page.id = null;
  if (kind === "resolved") data.discussion!.status = "resolved";
  const f = fixture(data); mount(f.fetcher);
  await findDiscussionButton({ name: "Edit comment" });
  if (kind === "page") expect(screen.queryByRole("button", { name: "Reattach" })).toBeNull();
  else expect(discussionButton({ name: "Reattach" })).toHaveProperty("disabled", true);
  expect(f.calls).toHaveLength(0);
});

function cacheOtherPage(client: QueryClient) {
  const other = { ...page, id: "other-page", path: "other.md", slug: "other", url: "/extra/other", title: "Other", id_materialized: true,
    citation: { page_id: "other-page", heading_id: null, path: "other.md", content_hash: "sha256:current", commit: null, uri: "plainbase://extra/other-page@sha256:current" } };
  client.setQueryData(pageByPathQuery("extra/other").queryKey, other);
  client.setQueryData(pageHtmlQuery("other-page", "extra").queryKey, { ...source, ...other, html: "<p>Other page</p>" });
}

async function warmRoundTrip(view: ReturnType<typeof mount>) {
  cacheOtherPage(view.client);
  act(() => view.history.push("/extra/other"));
  await screen.findByText("Other page");
  // Both metadata and HTML are cached: PageContent stays mounted while only the keyed panel changes.
  act(() => view.history.push("/extra/note"));
  await screen.findByText("banana 😀", { selector: "p" });
}

it.each([false, true])("releases a mounted panel's active action on warm A→B→A navigation (StrictMode %s)", async (strict) => {
  const f = fixture(); const view = mount(f.fetcher, "/extra/note", strict);
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Old page draft" } });
  expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", true);
  await warmRoundTrip(view);
  await waitFor(() => expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", false));
  expect(screen.queryByRole("button", { name: "Save comment" })).toBeNull();
  expect(f.calls).toHaveLength(0);
});

it("keeps creation busy through refresh when the newly created thread mounts", async () => {
  const f = fixture(); let reads = 0; let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method !== "POST" && String(input).includes("/pages/page/discussions") && ++reads > 1) return pending;
    return f.fetcher(input, init);
  }) as typeof fetch, "/extra/note", true);
  fireEvent.click(await screen.findByRole("button", { name: "Start a discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "New thread" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  const resolve = await findDiscussionButton({ name: "Resolve discussion" });
  await waitFor(() => expect(reads).toBe(2));
  expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", true);
  expect(resolve).toHaveProperty("disabled", true);
  expect(screen.getByRole("button", { name: "Reply" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("button", { name: "Close discussion" })).toHaveProperty("disabled", true);
  fireEvent.click(resolve);
  expect(f.calls).toHaveLength(1);
  await act(async () => { finish(Response.json({ discussions: [envelope().discussion], next: null, discussions_available: true, reason: null })); });
  await waitFor(() => expect(resolve).toHaveProperty("disabled", false));
  expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", false);
});

it("ignores an unmounted creation's late completion while a new same-page writer is pending", async () => {
  const finishes: ((response: Response) => void)[] = [];
  const f = fixture(envelope(), async () => new Promise<Response>((resolve) => finishes.push(resolve)));
  const view = mount(f.fetcher, "/extra/note", true);
  fireEvent.click(await screen.findByRole("button", { name: "Start a discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Old creation" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  await waitFor(() => expect(finishes).toHaveLength(1));
  await warmRoundTrip(view);
  await waitFor(() => expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", false));
  fireEvent.click(await screen.findByRole("button", { name: "Start a discussion" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "New same-page creation" } });
  fireEvent.click(screen.getByRole("button", { name: "Create discussion" }));
  await waitFor(() => expect(finishes).toHaveLength(2));
  await act(async () => { finishes[0](Response.json({ id: "thread", comment_id: null, commit: null }, { status: 201 })); });
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  expect(screen.queryByRole("button", { name: "Start a discussion" })).toBeNull();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "New same-page creation");
  expect(screen.getByRole("button", { name: "Create discussion" })).toHaveProperty("disabled", true);
  await act(async () => { finishes[1](Response.json({ id: "thread", comment_id: null, commit: null }, { status: 201 })); });
  await screen.findByRole("status", { name: "Discussion created" });
});

it("releases a thread writer on warm navigation and keeps a new panel's edit after its late completion", async () => {
  let finish!: (response: Response) => void;
  const f = fixture(envelope(), async () => new Promise<Response>((resolve) => { finish = resolve; }));
  const view = mount(f.fetcher, "/extra/note", true);
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Resolve discussion" }));
  await waitFor(() => expect(f.calls).toHaveLength(1));
  await warmRoundTrip(view);
  await waitFor(() => expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", false));
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "New mounted draft" } });
  await act(async () => { finish(Response.json({ id: "thread", comment_id: null, commit: null })); });
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "New mounted draft");
  expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", true);
  expect(screen.queryByRole("status", { name: "Discussion resolved" })).toBeNull();
});

it.each(["503", "network", "malformed success"])("retains the inspection guard after Cancel of an uncertain edit (%s) until explicit successful Refresh", async (failure) => {
  let failRefresh = false;
  const f = fixture(envelope(), async () => {
    if (failure === "network") throw new TypeError("connection lost");
    if (failure === "malformed success") return Response.json({ id: "thread" });
    return Response.json({ error: { code: "content_unreadable", message: "uncertain" } }, { status: 503 });
  });
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failRefresh && init?.method !== "POST" && String(input).includes("/discussions/thread?")
    ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch, undefined, true);
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Uncertain edit buffer" } });
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByText(/outcome is unclear/i);
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Uncertain edit buffer");
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.getByText("Refresh and inspect whether the change happened before trying again.", { selector: "p" })).toBeTruthy();
  for (const name of ["Edit comment", "Retract comment", "Purge comment", "Reattach", "Resolve discussion", "Reply"]) {
    expect(discussionButton({ name })).toHaveProperty("disabled", true);
    fireEvent.click(discussionButton({ name }));
  }
  expect(f.calls).toHaveLength(1);
  // Background detail success does not replace the user's explicit inspection step.
  await act(async () => { await view.client.invalidateQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  expect(screen.getByRole("button", { name: "Reply" })).toHaveProperty("disabled", true);
  failRefresh = true;
  fireEvent.click(discussionButton({ name: "Refresh" }));
  await screen.findByText(/Refresh failed. Showing earlier discussion content/);
  expect(screen.getByText("Refresh and inspect whether the change happened before trying again.", { selector: "p" })).toBeTruthy();
  failRefresh = false;
  fireEvent.click(discussionButton({ name: "Refresh" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Reply" })).toHaveProperty("disabled", false));
  fireEvent.click(discussionButton({ name: "Edit comment" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "**Raw Markdown** 😀");
  expect(f.calls).toHaveLength(1);
});

it("blocks other mutations after an uncertain reply is canceled", async () => {
  const f = fixture(envelope(), async () => Response.json({ error: { code: "content_unreadable", message: "uncertain" } }, { status: 503 }));
  mount(f.fetcher);
  fireEvent.click(await screen.findByRole("button", { name: "Reply" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Uncertain reply" } });
  fireEvent.click(screen.getByRole("button", { name: "Post reply" }));
  await screen.findByText(/The content could not be read. Refresh and check whether it posted/);
  expect(screen.getByRole("button", { name: "Post reply" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Uncertain reply");
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Refresh" })).toBe(document.activeElement));
  expect(discussionButton({ name: "Resolve discussion" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("button", { name: "Reply" })).toHaveProperty("disabled", true);
  expect(f.calls).toHaveLength(1);
});

it("keeps panel creation and Close discussion blocked after canceling an uncertain thread action", async () => {
  const f = fixture(envelope(), async () => Response.json({ error: { code: "content_unreadable", message: "uncertain" } }, { status: 503 }));
  mount(f.fetcher, "/extra/note", true);
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Uncertain panel edit" } });
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByText(/outcome is unclear/i);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.getByRole("button", { name: "Close discussion" })).toHaveProperty("disabled", true);
  expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", true);
  expect(screen.getAllByText("Finish this action before returning to the list.")).toHaveLength(1);
  fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
  fireEvent.click(screen.getByRole("button", { name: "Show discussions" }));
  expect(screen.getByText("Refresh and inspect whether the change happened before trying again.", { selector: "p" })).toBeTruthy();
  fireEvent.click(discussionButton({ name: "Refresh" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Close discussion" })).toHaveProperty("disabled", false));
  expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", false);
  expect(f.calls).toHaveLength(1);
});

it("does not count a canceled full Refresh followed by Load more as inspecting an uncertain write", async () => {
  const data = envelope(); data.next = "next-window";
  let detailReads = 0; let listReads = 0; let hold = false;
  let finishDetail!: (response: Response) => void; let finishList!: (response: Response) => void;
  const pendingDetail = new Promise<Response>((resolve) => { finishDetail = resolve; });
  const pendingList = new Promise<Response>((resolve) => { finishList = resolve; });
  const f = fixture(data, async () => Response.json({ error: { code: "content_unreadable", message: "uncertain" } }, { status: 503 }));
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (init?.method !== "POST" && url.includes("/pages/page/discussions") && hold && ++listReads === 2) return pendingList;
    if (init?.method !== "POST" && url.includes("/discussions/thread?")) {
      if (url.includes("cursor=next-window")) return Response.json({ ...data, comments: [{ ...data.comments[0], id: "later-comment",
        markdown: "Later Markdown", html: "<p>Later comment</p>" }], next: null });
      if (hold && ++detailReads === 2) return pendingDetail;
    }
    return f.fetcher(input, init);
  }) as typeof fetch, "/extra/note", true);
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  hold = true; detailReads = 1; listReads = 1;
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByText(/outcome is unclear/i);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  fireEvent.click(discussionButton({ name: "Refresh" }));
  await waitFor(() => expect(detailReads).toBe(2));
  expect(screen.getByRole("button", { name: "Load more" })).toHaveProperty("disabled", true);
  await act(async () => { await view.client.cancelQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  await waitFor(() => expect(screen.getByRole("button", { name: "Load more" })).toHaveProperty("disabled", false));
  fireEvent.click(screen.getByRole("button", { name: "Load more" }));
  await screen.findByText("Later comment");
  await act(async () => { finishList(Response.json({ discussions: [data.discussion], next: null, discussions_available: true, reason: null })); });
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  expect(discussionButton({ name: "Resolve discussion" })).toHaveProperty("disabled", true);
  expect(screen.getByText("Refresh and inspect whether the change happened before trying again.", { selector: "p" })).toBeTruthy();
  await act(async () => { finishDetail(Response.json(data)); });
  expect(discussionButton({ name: "Resolve discussion" })).toHaveProperty("disabled", true);
  fireEvent.click(discussionButton({ name: "Refresh" }));
  await waitFor(() => expect(discussionButton({ name: "Resolve discussion" })).toHaveProperty("disabled", false));
  expect(f.calls).toHaveLength(1);
});

it("suppresses stale HTML and Markdown after a confirmed edit with failed detail refresh until fresh detail arrives", async () => {
  const data = envelope(); let failDetail = false;
  const f = fixture(data, async () => {
    Object.assign(data.comments[0], { markdown: "Redacted", html: "<p>Redacted</p>", edited_at: "2026-09-29T01:00:00Z" });
    failDetail = true;
    return Response.json({ id: "thread", comment_id: null, commit: null });
  });
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failDetail && init?.method !== "POST" && String(input).includes("/discussions/thread?")
    ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch);
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Redacted" } });
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByRole("status", { name: "Comment saved" });
  await screen.findByText(/Saved, but the view could not refresh/);
  expect(screen.queryByText("Rendered body")).toBeNull();
  expect(screen.getByText("Comment saved; refresh to update this view.")).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Edit comment" })).toBeNull();
  // Do not render the submitted Markdown as an optimistic persisted record.
  expect(screen.queryByText("Redacted")).toBeNull();
  failDetail = false;
  fireEvent.click(screen.getAllByRole("button", { name: "Refresh" })[0]);
  await screen.findByText("Redacted");
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Redacted");
  expect(f.calls).toHaveLength(1);
});

it("uses fresh detail after a confirmed edit even when the independent page-list refresh fails", async () => {
  const data = envelope(); let failList = false;
  const f = fixture(data, async () => {
    Object.assign(data.comments[0], { markdown: "Fresh server Markdown", html: "<p>Fresh server HTML</p>" });
    failList = true;
    return Response.json({ id: "thread", comment_id: null, commit: null });
  });
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failList && init?.method !== "POST" && String(input).includes("/pages/page/discussions")
    ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Fresh server Markdown" } });
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByText("Fresh server HTML");
  await screen.findByText(/Saved, but the view could not refresh/);
  fireEvent.click(discussionButton({ name: "Edit comment" }));
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Fresh server Markdown");
});

it("retains a saved edit notice when Load more succeeds without refreshing its earlier window", async () => {
  const data = envelope(); data.next = "next-window";
  let failDetail = false;
  const f = fixture(data, async () => { failDetail = true; return Response.json({ id: "thread", comment_id: null, commit: null }); });
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (failDetail && init?.method !== "POST" && String(input).includes("/discussions/thread?")) {
      if (String(input).includes("cursor=next-window")) return Response.json({ ...data, comments: [{ ...data.comments[0], id: "later-comment",
        markdown: "Later Markdown", html: "<p>Later comment</p>" }], next: null });
      return Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 });
    }
    return f.fetcher(input, init);
  }) as typeof fetch);
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Redacted" } });
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByText(/Saved, but the view could not refresh/);
  fireEvent.click(screen.getByRole("button", { name: "Load more" }));
  await screen.findByText("Later comment");
  expect(screen.queryByText("Rendered body")).toBeNull();
  expect(screen.getByText("Comment saved; refresh to update this view.")).toBeTruthy();
  expect(within(screen.getByText("Comment saved; refresh to update this view.").closest("article")!).queryByRole("button", { name: "Edit comment" })).toBeNull();
});

it("keeps a reply writer busy until its detail refresh settles", async () => {
  let hold = false; let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const f = fixture(envelope(), async () => Response.json({ id: "thread", comment_id: "reply", commit: null }, { status: 201 }));
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method !== "POST" && String(input).includes("/discussions/thread?") && hold) return pending;
    return f.fetcher(input, init);
  }) as typeof fetch, "/extra/note", true);
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await screen.findByRole("button", { name: "Reply" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "New reply" } });
  hold = true;
  fireEvent.click(screen.getByRole("button", { name: "Post reply" }));
  await screen.findByRole("status", { name: "Reply posted" });
  expect(discussionButton({ name: "Resolve discussion" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("button", { name: "Close discussion" })).toHaveProperty("disabled", true);
  expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", true);
  fireEvent.click(discussionButton({ name: "Resolve discussion" }));
  expect(f.calls).toHaveLength(1);
  await act(async () => { finish(Response.json(envelope())); });
  await waitFor(() => expect(discussionButton({ name: "Resolve discussion" })).toHaveProperty("disabled", false));
  expect(discussionButton({ name: "Start a discussion" })).toHaveProperty("disabled", false);
});

it.each(["resolve", "reopen", "reattach"] as const)("uses an accurate %s refresh-failure verb", async (kind) => {
  const data = envelope(); if (kind === "reopen") data.discussion!.status = "resolved";
  let failed = false;
  const f = fixture(data, async (url) => {
    if (url.includes("anchor-preview")) return Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14, selection: "narrowed", quote_text: "banana 😀" });
    failed = true; return Response.json({ id: "thread", comment_id: null, commit: null });
  });
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failed && init?.method !== "POST" && String(input).includes("/discussions/thread?")
    ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch);
  if (kind === "reattach") {
    fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
    selectText(await screen.findByText("banana 😀", { selector: "p" }));
    fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
    fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
    fireEvent.click(screen.getByRole("button", { name: "Reattach discussion" }));
  } else fireEvent.click(await findDiscussionButton({ name: kind === "reopen" ? "Reopen discussion" : "Resolve discussion" }));
  const verb = kind === "resolve" ? "Resolved" : kind === "reopen" ? "Reopened" : "Reattached";
  expect(await screen.findByText(new RegExp(`${verb}, but the view could not refresh`))).toBeTruthy();
});

it("keeps the confirmed refresh-failure verb when opening and canceling another action", async () => {
  const data = envelope(); let failList = false;
  const f = fixture(data, async () => {
    data.discussion!.status = "resolved"; failList = true;
    return Response.json({ id: "thread", comment_id: null, commit: null });
  });
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failList && init?.method !== "POST" && String(input).includes("/pages/page/discussions")
    ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Resolve discussion" }));
  await screen.findByText(/Resolved, but the view could not refresh/);
  fireEvent.click(discussionButton({ name: "Edit comment" }));
  expect(screen.getByText(/Resolved, but the view could not refresh/)).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.getByText(/Resolved, but the view could not refresh/)).toBeTruthy();
  expect(f.calls).toHaveLength(1);
});

it("explains a capture/current source hash mismatch and requires reload and reselection without a preview POST", async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const capture = vi.fn(() => ({ kind: "quote" as const, content_hash: "sha256:old", block_start: 0, block_end: 14, selected_text: "banana 😀" }));
  const resetSelection = vi.fn(); const submit = vi.fn();
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  render(<QueryClientProvider client={client}><DiscussionReattach root="extra" pageId="page" originalQuote="Original evidence"
    source={{ root: "extra", pageId: "page", hash: "sha256:current", ready: true, busy: false, capture, resetSelection, reload: async () => true }}
    busy={false} disabled={null} open submit={submit} cancel={() => {}} /></QueryClientProvider>);
  expect(resetSelection).toHaveBeenCalledOnce();
  resetSelection.mockClear();
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  expect(await screen.findByRole("alert")).toHaveProperty("textContent", "The page changed. Reload it and reselect the passage.");
  expect(resetSelection).toHaveBeenCalledOnce();
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  expect(screen.queryByRole("button", { name: "Confirm passage" })).toBeNull();
  expect(fetcher).not.toHaveBeenCalled(); expect(submit).not.toHaveBeenCalled();
  fireEvent.keyDown(screen.getByRole("region", { name: "Reattach discussion" }), { key: "Escape" });
  expect(screen.getByRole("alert")).toHaveProperty("textContent", "The page changed. Reload it and reselect the passage.");
  expect(screen.getByRole("button", { name: "Reload page" })).toBe(document.activeElement);
});

it("releases uncertain-write inspection after a fresh full detail read even if the page list cannot refresh", async () => {
  const data = envelope(); let failList = false;
  const f = fixture(data, async () => Response.json({ error: { code: "content_unreadable", message: "uncertain" } }, { status: 503 }));
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failList && init?.method !== "POST" && String(input).includes("/pages/page/discussions")
    ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByText(/outcome is unclear/i);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  failList = true;
  fireEvent.click(discussionButton({ name: "Refresh" }));
  await waitFor(() => expect(discussionButton({ name: "Resolve discussion" })).toHaveProperty("disabled", false));
  expect(screen.queryByText("Refresh and inspect whether the change happened before trying again.")).toBeNull();
  expect(f.calls).toHaveLength(1);
});

it("disables Refresh while loading another window and retains a saved-action notice after externally canceled full Refresh", async () => {
  const data = envelope(); data.next = "next-window";
  let failDetail = false; let detailReads = 0;
  let finishDetail!: (response: Response) => void; let finishWindow!: (response: Response) => void;
  const pendingDetail = new Promise<Response>((resolve) => { finishDetail = resolve; });
  const pendingWindow = new Promise<Response>((resolve) => { finishWindow = resolve; });
  const f = fixture(data, async () => { failDetail = true; return Response.json({ id: "thread", comment_id: null, commit: null }); });
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (init?.method !== "POST" && url.includes("/discussions/thread?")) {
      if (url.includes("cursor=next-window")) return pendingWindow;
      if (++detailReads === 3) return pendingDetail;
      if (failDetail) return Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 });
    }
    return f.fetcher(input, init);
  }) as typeof fetch);
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByText(/Saved, but the view could not refresh/);
  fireEvent.click(screen.getAllByRole("button", { name: "Refresh" })[0]);
  await waitFor(() => expect(detailReads).toBe(3));
  expect(screen.getByRole("button", { name: "Load more" })).toHaveProperty("disabled", true);
  await act(async () => { await view.client.cancelQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  await waitFor(() => expect(screen.getByRole("button", { name: "Load more" })).toHaveProperty("disabled", false));
  fireEvent.click(screen.getByRole("button", { name: "Load more" }));
  await waitFor(() => expect(screen.getAllByRole("button", { name: "Refresh" }).every((button) => (button as HTMLButtonElement).disabled)).toBe(true));
  await act(async () => { finishWindow(Response.json({ ...data, comments: [{ ...data.comments[0], id: "later-comment", html: "<p>Later comment</p>" }], next: null })); });
  await screen.findByText("Later comment");
  await waitFor(() => expect(view.client.isFetching()).toBe(0));
  expect(screen.getByText(/Saved, but the view could not refresh/)).toBeTruthy();
  expect(screen.queryByText("Rendered body")).toBeNull();
  await act(async () => { finishDetail(Response.json(data)); });
  expect(screen.getByText(/Saved, but the view could not refresh/)).toBeTruthy();
  expect(f.calls).toHaveLength(1);
});

it.each(["discussion_too_large", "ambiguous_page_id", "payload_too_large"])("requires fresh reattachment evidence after %s", async (code) => {
  const f = fixture(envelope(), async (url, init) => url.includes("anchor-preview")
    ? Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14, selection: "narrowed", quote_text: JSON.parse(String(init.body)).selected_text })
    : Response.json({ error: { code, message: "refused" } }, { status: code === "ambiguous_page_id" ? 409 : code === "payload_too_large" ? 413 : 422 }));
  mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  selectText(await screen.findByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  fireEvent.click(screen.getByRole("button", { name: "Reattach discussion" }));
  await screen.findByText(code === "ambiguous_page_id" ? "The stored source page is ambiguous. Reload the page to check its identity." :
    "This reattachment is too large. Select a smaller passage.");
  await waitFor(() => expect(screen.queryByRole("button", { name: "Passage confirmed" })).toBeNull());
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  expect(screen.getByRole("button", { name: "Preview selected passage" })).toHaveProperty("disabled", code === "ambiguous_page_id");
  fireEvent.click(screen.getByRole("button", { name: "Reattach discussion" }));
  expect(f.calls.filter((call) => call.url.includes("/reattach?"))).toHaveLength(1);
});

it("invalidates confirmed panel reattachment after a failed source HTML refetch", async () => {
  let failSource = false;
  const f = fixture(envelope(), async (url, init) => url.includes("anchor-preview")
    ? Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14, selection: "narrowed", quote_text: JSON.parse(String(init.body)).selected_text })
    : Response.json({ id: "thread", comment_id: null, commit: null }));
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failSource && String(input).includes("/html")
    ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  selectText(screen.getByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm passage" }));
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", false);
  failSource = true;
  await act(async () => { await view.client.invalidateQueries({ queryKey: pageHtmlQuery("page", "extra").queryKey, exact: true }); });
  await waitFor(() => expect(screen.queryByRole("button", { name: "Passage confirmed" })).toBeNull());
  expect(screen.getByRole("button", { name: "Reattach discussion" })).toHaveProperty("disabled", true);
  expect(f.calls).toHaveLength(1);
});

it("requires a new page selection when opening reattachment", async () => {
  const f = fixture(envelope(), async () => Response.json({ content_hash: "sha256:current", byte_start: 0, byte_end: 14,
    selection: "narrowed", quote_text: "banana 😀" }));
  mount(f.fetcher, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  selectText(screen.getByText("banana 😀", { selector: "p" }));
  fireEvent.click(await findDiscussionButton({ name: "Reattach" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  await screen.findByText(/Select text in the page and try again/);
  expect(f.calls).toHaveLength(0);
  selectText(screen.getByText("banana 😀", { selector: "p" }));
  fireEvent.click(screen.getByRole("button", { name: "Preview selected passage" }));
  expect(await screen.findByRole("button", { name: "Confirm passage" })).toBeTruthy();
  expect(f.calls).toHaveLength(1);
});

it.each(["edit", "reply"])("retains the prior saved-action refresh warning after a failed follow-up %s", async (kind) => {
  const data = envelope(); let failList = false;
  const f = fixture(data, async () => {
    if (failList) return Response.json({ error: { code: "forbidden", message: "denied" } }, { status: 403 });
    Object.assign(data.comments[0], { markdown: "Saved body", html: "<p>Saved body</p>" });
    failList = true; return Response.json({ id: "thread", comment_id: null, commit: null });
  });
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => failList && init?.method !== "POST" && String(input).includes("/pages/page/discussions")
    ? Response.json({ error: { code: "root_unavailable", message: "offline" } }, { status: 503 }) : f.fetcher(input, init)) as typeof fetch, "/extra/note");
  fireEvent.click(await within(await screen.findByRole("region", { name: "Page discussions" })).findByRole("button", { name: /Original evidence|Saved quote/ }));
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Saved body" } });
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  await screen.findByText(/Saved, but the view could not refresh/);
  const trigger = await findDiscussionButton({ name: kind === "edit" ? "Edit comment" : "Reply" });
  await waitFor(() => expect(trigger).toHaveProperty("disabled", false));
  fireEvent.click(trigger);
  fireEvent.change(screen.getByRole("textbox", { name: "Comment" }), { target: { value: "Rejected follow-up" } });
  fireEvent.click(discussionButton({ name: kind === "edit" ? "Save comment" : "Post reply" }));
  await screen.findByText(kind === "edit" ? /cannot edit this comment/ : /cannot post here/);
  expect(screen.getByText(/Saved, but the view could not refresh/)).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Comment" })).toHaveProperty("value", "Rejected follow-up");
  expect(f.calls).toHaveLength(2);
});

it("describes an externally retracted comment without inventing an edit buffer for a retract action", async () => {
  const f = fixture(envelope(), async () => Response.json({ error: { code: "comment_retracted", message: "gone" } }, { status: 409 }));
  mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Retract comment" }));
  fireEvent.click(within(screen.getByRole("region", { name: "Confirm retraction" })).getByRole("button", { name: "Retract comment" }));
  await screen.findByText("This comment was retracted. Your action is still here for review or canceling.");
  expect(screen.queryByRole("textbox", { name: "Comment" })).toBeNull();
  expect(f.calls).toHaveLength(1);
});

it("keeps the active edit visible when a refreshed envelope becomes unsupported, then validates UTF-8 before saving", async () => {
  const data = envelope(); const f = fixture(data); const view = mount(f.fetcher);
  fireEvent.click(await findDiscussionButton({ name: "Edit comment" }));
  const text = screen.getByRole("textbox", { name: "Comment" });
  fireEvent.change(text, { target: { value: "Preserved buffer" } });
  data.discussions_available = false; data.discussion = null;
  await act(async () => { await view.client.invalidateQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  await waitFor(() => expect(screen.getByRole("button", { name: "Save comment" })).toHaveProperty("disabled", true));
  expect(text).toHaveProperty("value", "Preserved buffer");
  Object.assign(data, envelope());
  await act(async () => { await view.client.invalidateQueries({ queryKey: discussionDetailQuery("extra", "thread").queryKey }); });
  await waitFor(() => expect(screen.getByRole("button", { name: "Save comment" })).toHaveProperty("disabled", false));
  fireEvent.change(text, { target: { value: "😀".repeat(16_385) } });
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  expect(await screen.findByText("This comment is too long. Shorten it before saving.")).toBeTruthy();
  fireEvent.change(text, { target: { value: "malformed\uD800" } });
  fireEvent.click(screen.getByRole("button", { name: "Save comment" }));
  expect(await screen.findByText(/character that cannot be saved/)).toBeTruthy();
  expect(f.calls).toHaveLength(0);
});

it.each(['root', 'page', 'thread'] as const)('does not cancel a held two-window automatic refresh through %s Load more', async (kind) => {
  const data = envelope();
  let hold = false; let started = false; let aborted = false;
  let generation = 'before';
  let finish!: () => void;
  const pending = new Promise<void>((resolve) => { finish = resolve; });
  const cursors: Array<string | null> = [];
  const base = fixture(data).fetcher;
  mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const target = kind === 'root' ? '/api/v1/discussions?' : kind === 'page' ? '/api/v1/pages/page/discussions?' : '/api/v1/discussions/thread?';
    if (!url.startsWith(target)) return base(input, init);
    const cursor = new URL(url, 'http://local').searchParams.get('cursor');
    cursors.push(cursor);
    if (hold && cursor === null) {
      started = true;
      init?.signal?.addEventListener('abort', () => { aborted = true; });
      await pending;
    }
    const position = cursor === null ? 'first' : cursor;
    const label = `${position} ${generation}`;
    const next = cursor === null ? 'second' : cursor === 'second' ? 'third' : null;
    return Response.json(kind === 'thread'
      ? { ...data, comments: [{ ...data.comments[0], id: position, markdown: label, html: `<p>${label}</p>` }], next }
      : { discussions: [{ ...data.discussion, id: position, quote: label }], next, discussions_available: true, reason: null });
  }) as typeof fetch, kind === 'root' ? '/discussions/extra' : kind === 'page' ? '/extra/note' : '/discussions/extra/thread');
  await screen.findByText('first before');
  fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
  await screen.findByText('second before');
  hold = true; generation = 'after';
  fireEvent(window, new Event('focus'));
  await waitFor(() => expect(started).toBe(true));
  const more = screen.getByRole('button', { name: 'Load more' });
  try {
    expect(more).toHaveProperty('disabled', true);
    fireEvent.click(more);
    expect(aborted).toBe(false);
    expect(cursors).toEqual([null, 'second', null]);
  } finally { hold = false; await act(async () => finish()); }
  await screen.findByText('first after');
  await screen.findByText('second after');
  expect(screen.queryByText('first before')).toBeNull();
  expect(screen.queryByText('second before')).toBeNull();
  expect(aborted).toBe(false);
  fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
  await screen.findByText('third after');
  expect(cursors).toEqual([null, 'second', null, 'second', 'third']);
});

it('returns Close discussion focus to the retained filter after resolution removes its row', async () => {
  const data = envelope();
  const f = fixture(data, async () => {
    data.discussion!.status = 'resolved';
    return Response.json({ id: 'thread', comment_id: null, commit: null });
  });
  mount(f.fetcher, '/extra/note');
  const filter = await screen.findByRole('group', { name: 'Discussion status' });
  fireEvent.click(within(filter).getByRole('button', { name: 'Open' }));
  fireEvent.click(document.querySelector('[data-pb-discussion-id="thread"]')!);
  fireEvent.click(await findDiscussionButton({ name: 'Resolve discussion' }));
  await screen.findByRole('status', { name: 'Discussion resolved' });
  const close = screen.getByRole('button', { name: 'Close discussion' }); close.focus();
  fireEvent.click(close);
  await screen.findByText('No open discussions in the loaded results.');
  const selected = within(screen.getByRole('group', { name: 'Discussion status' })).getByRole('button', { name: 'Open' });
  await waitFor(() => expect(document.activeElement).toBe(selected));
  expect(selected.getAttribute('aria-pressed')).toBe('true');
});

it.each(['root', 'page'] as const)('clears a visible passage and aborts held old detail on a %s workspace change', async (change) => {
  const data = envelope();
  data.discussion!.range = { byte_start: 0, byte_end: 14 };
  data.discussion!.range_content_hash = source.content_hash;
  let hold = false; let started = false; let aborted = false;
  let release!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { release = resolve; });
  const base = fixture(data).fetcher;
  const next = change === 'root' ? { ...page, root: 'docs', url: '/docs/note' } : { ...page, id: 'other', path: 'other.md', url: '/extra/other' };
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes(`/pages/by-path/${next.url.slice(1)}`)) return Response.json(next);
    if (url.includes(`/pages/${next.id}/html`) && (change === 'page' || url.includes('root=docs'))) return Response.json({ ...source, ...next });
    if (url.includes('/discussions/thread?') && hold) {
      started = true; init?.signal?.addEventListener('abort', () => { aborted = true; }); return pending;
    }
    return base(input, init);
  }) as typeof fetch, '/extra/note');
  fireEvent.click(await within(await screen.findByRole('region', { name: 'Page discussions' })).findByRole('button', { name: /Original evidence|Saved quote/ }));
  await waitFor(() => expect(document.querySelector('.pb-discussion-passage')?.textContent).toBe('banana 😀'));
  const old = document.querySelector('.pb-discussion-passage')!;
  hold = true;
  fireEvent(window, new Event('focus'));
  await waitFor(() => expect(started).toBe(true));
  act(() => view.history.push(next.url));
  await waitFor(() => expect(aborted).toBe(true));
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Close discussion' })).toBeNull());
  expect(old.classList.contains('pb-discussion-passage')).toBe(false);
  expect(document.querySelector('.pb-discussion-passage')).toBeNull();
  await act(async () => release(Response.json(data)));
  expect(document.querySelector('.pb-discussion-passage')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Close discussion' })).toBeNull();
});

it("disabled page hides the entire workspace while cached content and ordinary editing remain", async () => {
  const f = fixture();
  const view = mount(f.fetcher, "/extra/note", false, false, true);
  await screen.findByText("banana 😀");
  expect(view.container.querySelector(".pb-margin-discussions")).toBeNull();
  expect(view.container.querySelector(".pb-discussion-passage")).toBeNull();
  expect(screen.queryByRole("button", { name: "Start a discussion" })).toBeNull();
  expect(screen.getByRole("link", { name: "Edit this page" })).toBeTruthy();
  expect(f.fetcher).not.toHaveBeenCalledWith(expect.stringContaining("/discussions"), expect.anything());
  expect((f.fetcher as ReturnType<typeof vi.fn>).mock.calls.some(([input]) => String(input).includes("/discussions"))).toBe(false);
});

it("bare page waits for tree metadata and activates default-on workspace on that render", async () => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => { finish = resolve; });
  const calls: string[] = [];
  const base = fixture().fetcher;
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); calls.push(url);
    if (url === "/api/v1/tree") return pending;
    if (url === "/api/v1/pages/page") return Response.json({ ...page, url: null });
    if (url === "/api/v1/pages/page/html") return Response.json({ ...source, url: null });
    return base(input, init);
  }) as typeof fetch;
  mount(fetcher, "/p/page", false, false, false, false);
  await screen.findByText("banana 😀");
  expect(calls.some((url) => url.includes("/discussions"))).toBe(false);
  await act(async () => { finish(Response.json({ roots: [{ root: "extra", primary: true, available: true, editable: true,
    tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/extra", page_count: 0, children: [] } }] })); });
  await screen.findByRole("region", { name: "Page discussions" });
  expect(calls.filter((url) => url === "/api/v1/pages/page/html")).toHaveLength(1);
  expect(calls.some((url) => url.includes("/pages/page/discussions?root=extra"))).toBe(true);
});

it.each([false, true])("initial page metadata failure offers retry while preserving explicit-false %s precedence", async (disabled) => {
  let failed = true;
  const base = fixture().fetcher;
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url === "/api/v1/tree") return failed ? new Response(null, { status: 503 }) : Response.json({ roots: [{ root: "extra",
      primary: true, available: true, editable: true, ...(disabled ? { discussionsEnabled: false } : {}),
      tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/extra", page_count: 0, children: [] } }] });
    if (url === "/api/v1/pages/page") return Response.json({ ...page, url: null });
    if (url === "/api/v1/pages/page/html") return Response.json({ ...source, url: null });
    return base(input, init);
  }) as typeof fetch;
  const view = mount(fetcher, "/p/page", false, false, false, false);
  const retry = await screen.findByRole("button", { name: "Retry discussion availability" });
  expect(screen.getByText("banana 😀")).toBeTruthy();
  expect(screen.queryByRole("region", { name: "Page discussions" })).toBeNull();
  expect((fetcher as ReturnType<typeof vi.fn>).mock.calls.some(([input]) => String(input).includes("/discussions"))).toBe(false);
  failed = false; fireEvent.click(retry);
  await waitFor(() => expect(view.client.getQueryState(treeQuery.queryKey)?.status).toBe("success"));
  expect(screen.queryByRole("button", { name: "Retry discussion availability" })).toBeNull();
  if (disabled) {
    expect(screen.queryByRole("region", { name: "Page discussions" })).toBeNull();
    expect((fetcher as ReturnType<typeof vi.fn>).mock.calls.some(([input]) => String(input).includes("/discussions"))).toBe(false);
  } else await screen.findByRole("region", { name: "Page discussions" });
});

it.each([false, true])("cached owning metadata still governs page capability after a tree error (disabled %s)", async (disabled) => {
  const base = fixture().fetcher;
  const view = mount(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input) === "/api/v1/tree"
    ? new Response(null, { status: 503 }) : base(input, init)) as typeof fetch, "/extra/note", false, false, disabled);
  await screen.findByText("banana 😀");
  await act(async () => { await view.client.invalidateQueries({ queryKey: treeQuery.queryKey }); });
  expect(view.client.getQueryState(treeQuery.queryKey)?.status).toBe("error");
  expect(screen.queryByRole("button", { name: "Retry discussion availability" })).toBeNull();
  if (disabled) expect(screen.queryByRole("region", { name: "Page discussions" })).toBeNull();
  else expect(await screen.findByRole("region", { name: "Page discussions" })).toBeTruthy();
});

it("fresh configured-disable metadata hides cached success and a late thread response", async () => {
  const f = fixture(); const view = mount(f.fetcher);
  await screen.findByText("Rendered body");
  await act(async () => { view.client.setQueryData(treeQuery.queryKey, { roots: [{ root: "extra", primary: true,
    available: true, editable: true, discussionsEnabled: false, tree: { type: "folder", name: "", title: null,
      description: null, path: "", url: "/extra", page_count: 0, children: [] } }] }); });
  await screen.findByText("Discussions are disabled for this root");
  await act(async () => { view.client.setQueryData(discussionDetailQuery("extra", "thread").queryKey, { pages: [envelope()], pageParams: [null] }); });
  expect(screen.queryByText("Rendered body")).toBeNull();
  expect(screen.queryByRole("button", { name: "Reply" })).toBeNull();
});

it("configured-disable mutation error retains an old client's reply draft", async () => {
  const f = fixture(envelope(), async () => Response.json({ error: { code: "discussions_disabled", message: "Disabled" } }, { status: 403 }));
  mount(f.fetcher);
  fireEvent.click(await screen.findByRole("button", { name: "Reply" }));
  const field = screen.getByRole("textbox", { name: "Comment" });
  fireEvent.change(field, { target: { value: "Keep my reply" } });
  fireEvent.click(screen.getByRole("button", { name: "Post reply" }));
  await screen.findByText(/Discussions are disabled for this root. Your draft is still here/);
  expect(field).toHaveProperty("value", "Keep my reply");
  expect(f.calls).toHaveLength(1);
});
