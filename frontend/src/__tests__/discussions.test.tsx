import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createAppRouter } from "../router";
import { sessionQuery, treeQuery } from "../api/queries";

const states = ["page_level", "exact", "moved", "ambiguous", "changed", "orphaned", "unavailable", "unreadable", "incomplete"];
const item = (state: string, id = state) => ({
  id, page: { id: state === "incomplete" ? null : "page-id", path: state === "incomplete" ? null : "notes/test.md", resolution: "unknown" },
  status: state === "incomplete" || state === "unreadable" ? null : "open", state,
  reason: state === "unreadable" ? "read_failure" : null, range: null,
  candidates: state === "ambiguous" ? { count: 3, items: [{ byte_start: 1, byte_end: 4 }], truncated: true } : null,
  placement: state === "changed" ? { kind: "heading", id: "context", line: null } : null,
  quote: state === "incomplete" || state === "unreadable" ? null : "Original quote", comment_count: 1,
  starter: state === "incomplete" || state === "unreadable" ? null : { key: "opaque", kind: "agent", label: "Helper" },
  created: null, updated: null,
});

function mount(at: string, withTree = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  if (withTree) client.setQueryData(treeQuery.queryKey, { roots: [
    { root: "docs", primary: true, available: true, editable: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } },
    { root: "extra", primary: false, available: true, editable: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/extra", page_count: 0, children: [] } },
  ] });
  const router = createAppRouter(client, createMemoryHistory({ initialEntries: [at] }));
  return { ...render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>), history: router.history };
}

afterEach(() => vi.unstubAllGlobals());

describe("discussion reads", () => {
  it("renders all public states and nullable metadata without inventing a thread", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ discussions: states.map((s) => s === "unreadable" ? { ...item(s), reason: "RowDerivation:notes/failed.md" } : item(s)), next: null, discussions_available: true, reason: null }))));
    mount("/discussions/docs");
    expect(await screen.findByText("More matches exist")).toBeTruthy();
    expect(screen.getByText("Page discussion", { selector: "strong" })).toBeTruthy();
    expect(screen.getByText("Matches several places", { selector: "strong" })).toBeTruthy();
    expect(screen.getByText("Was around here", { selector: "strong" })).toBeTruthy();
    expect(screen.getByText("Page no longer found", { selector: "strong" })).toBeTruthy();
    expect(screen.getByText("Temporarily unavailable", { selector: "strong" })).toBeTruthy();
    expect(screen.getByText("Unreadable discussion", { selector: "strong" })).toBeTruthy();
    expect(screen.getByText("Incomplete discussion", { selector: "strong" })).toBeTruthy();
    expect(within(screen.getByLabelText("Match state")).getByRole("option", { name: "Quote found" })).toBeTruthy();
    expect(screen.queryByText("RowDerivation:notes/failed.md", { selector: "p" })).toBeNull();
    expect(screen.getByText("Discussion details could not be read.")).toBeTruthy();
    expect(screen.getByText("RowDerivation:notes/failed.md", { selector: "code" })).toBeTruthy();
  });

  it("resets the local filter on a direct root switch and keeps requests pinned to each root", async () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      urls.push(url);
      const params = new URL(url, "http://local").searchParams;
      const root = params.get("root");
      return new Response(JSON.stringify({ discussions: [item("exact", `${root}-item`)], next: root === "docs" && !params.has("cursor") ? "docs-next" : null, discussions_available: true, reason: null }));
    }));
    const view = mount("/discussions/docs");
    fireEvent.change(await screen.findByLabelText("Match state"), { target: { value: "exact" } });
    await waitFor(() => expect(urls).toContain("/api/v1/discussions?root=docs&state=exact&limit=50"));
    fireEvent.click(await screen.findByRole("button", { name: "Load more" }));
    await waitFor(() => expect(urls).toContain("/api/v1/discussions?root=docs&state=exact&limit=50&cursor=docs-next"));
    act(() => view.history.push("/discussions/extra"));
    await waitFor(() => expect(screen.getByLabelText("Match state")).toHaveProperty("value", ""));
    await waitFor(() => expect(urls).toContain("/api/v1/discussions?root=extra&limit=50"));
    expect(urls).not.toContain("/api/v1/discussions?root=extra&state=exact&limit=50");
    expect(urls.some((url) => url.includes("root=extra") && url.includes("cursor="))).toBe(false);
    expect(screen.getByRole("heading", { name: "Discussions in extra" })).toBeTruthy();
  });

  it("follows an empty filtered cursor, keeps prior rows on a failed next page, and refreshes", async () => {
    const urls: string[] = [];
    let failNext = false;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input); urls.push(url);
      const cursor = new URL(url, "http://local").searchParams.get("cursor");
      if (cursor === "two" && failNext) return new Response(JSON.stringify({ error: { code: "root_unavailable", message: "offline" } }), { status: 503 });
      const response = cursor === null ? { discussions: [], next: "one" } : cursor === "one" ? { discussions: [item("exact", "shared")], next: "two" } : { discussions: [item("exact", "shared"), item("moved", "other")], next: null };
      return new Response(JSON.stringify({ ...response, discussions_available: true, reason: null }));
    }));
    mount("/discussions/extra");
    fireEvent.change(await screen.findByLabelText("Match state"), { target: { value: "exact" } });
    await waitFor(() => expect(screen.getByRole("button", { name: "Load more" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByText("Original quote")).toBeTruthy();
    failNext = true;
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByText("Original quote")).toBeTruthy();
    failNext = false;
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    await waitFor(() => expect(urls.some((url) => url.includes("root=extra") && url.includes("cursor=two") && url.includes("state=exact"))).toBe(true));
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(urls.filter((url) => url.includes("root=extra") && !url.includes("cursor=")).length).toBeGreaterThan(1));
  });

  it("distinguishes unsupported null detail and a missing discussion", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ discussion: null, comments: [], next: null, discussions_available: false, reason: "read_only_root" }))));
    const view = mount("/discussions/docs/missing");
    expect(await screen.findByText("Discussions are not available on this read-only root")).toBeTruthy();
    view.unmount();
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: { code: "discussion_not_found", message: "No discussion" } }), { status: 404 })));
    mount("/discussions/docs/missing");
    expect(await screen.findByText("Discussion not found")).toBeTruthy();
  });

  it("renders server comment HTML outside prose and keeps original and latest reattachment evidence", async () => {
    const detail = { ...item("moved", "thread"), anchor: { kind: "quote", content_hash: "hash", commit: "original-commit", quote: "Original <text>", prefix: "", suffix: "", byte_start: 0, byte_end: 8, body_start: 0, line: 1, selection: "narrowed", heading_path: [] }, reattachment: { by: { key: "opaque", label: "Alice" }, at: "2026-01-01T00:00:00Z", anchor: { kind: "quote", content_hash: "new", commit: null, quote: "New quote", prefix: "", suffix: "", byte_start: 4, byte_end: 13, body_start: 0, line: 3, selection: "narrowed", heading_path: [] } } };
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ discussion: detail, comments: [{ id: "c", author: { key: "k", kind: "agent", label: "Helper" }, created: "2026-01-01T00:00:00Z", edited_at: "2026-01-02T00:00:00Z", retracted: false, html: "<h2>Server heading</h2><p>&lt;safe&gt;</p>", markdown: "RAW MARKDOWN" }], next: null, discussions_available: true, reason: null }))));
    mount("/discussions/docs/thread");
    expect(await screen.findByText("Original <text>")).toBeTruthy();
    expect(screen.getByText("New quote")).toBeTruthy();
    expect(screen.getByText("Server heading")).toBeTruthy();
    expect(screen.queryByText("RAW MARKDOWN")).toBeNull();
    expect(document.querySelector(".pb-discussion-body .pb-heading-anchor")).toBeNull();
    expect(document.querySelector(".pb-discussion-body.pb-prose")).toBeNull();
    expect(screen.getByText(/Edited/)).toBeTruthy();
    expect(screen.getAllByText(/Agent/).length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { level: 1, name: "Discussion on notes/test.md" })).toBeTruthy();
    expect(screen.getByRole("heading", { level: 2, name: "Original anchor" })).toBeTruthy();
    expect(screen.getByRole("heading", { level: 2, name: "Comments" })).toBeTruthy();
    const sourceEvidence = screen.getAllByText("Source evidence", { selector: "summary" })[0].parentElement as HTMLDetailsElement;
    expect(sourceEvidence.open).toBe(false);
    expect(within(sourceEvidence).getByText(/Source hash hash.*original-commit/)).toBeTruthy();
    const times = Array.from(document.querySelectorAll("time"));
    expect(times).toHaveLength(3);
    expect(times.every((time) => time.hasAttribute("dateTime") && time.hasAttribute("title"))).toBe(true);
  });

  it("loads a standalone rooted thread while tree fails and page endpoints are absent", async () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input); urls.push(url);
      if (url === "/api/v1/tree") return new Response(JSON.stringify({ error: { code: "root_unavailable", message: "tree down" } }), { status: 503 });
      if (url === "/api/v1/discussions/shared?root=extra&limit=50") return new Response(JSON.stringify({
        discussion: { ...item("orphaned", "shared"), anchor: { kind: "page", content_hash: "sha256:old", commit: null }, reattachment: null },
        comments: [], next: null, discussions_available: true, reason: null,
      }));
      return new Response(JSON.stringify({ error: { code: "page_not_found", message: "deleted" } }), { status: 404 });
    }));
    mount("/discussions/extra/shared", false);
    expect(await screen.findByText("Page no longer found")).toBeTruthy();
    expect(urls).toContain("/api/v1/discussions/shared?root=extra&limit=50");
    expect(urls.some((url) => url.startsWith("/api/v1/pages/"))).toBe(false);
  });

  it("loads later comments with the server cursor and deduplicates repeated boundaries", async () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input); urls.push(url);
      const comment = (id: string) => ({ id, author: { key: "k", kind: "human", label: "Alice" }, created: "2026-01-01T00:00:00Z", edited_at: null, retracted: false, html: `<p>Comment ${id}</p>`, markdown: "not rendered" });
      const later = url.includes("cursor=comment-one");
      return new Response(JSON.stringify({ discussion: { ...item("page_level", "thread"), anchor: { kind: "page", content_hash: "h", commit: null }, reattachment: null },
        comments: later ? [comment("comment-one"), comment("comment-two")] : [comment("comment-one")], next: later ? null : "comment-one", discussions_available: true, reason: null }));
    }));
    mount("/discussions/docs/thread");
    fireEvent.click(await screen.findByRole("button", { name: "Load more" }));
    expect(await screen.findByText("Comment comment-two")).toBeTruthy();
    expect(screen.getAllByText("Comment comment-one")).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "Load more" })).toBeNull();
    expect(urls).toContain("/api/v1/discussions/thread?root=docs&limit=50&cursor=comment-one");
  });
});
