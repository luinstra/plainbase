import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, pageQuery, sessionQuery, treeQuery } from "../api/queries";
import type { DiscussionItem, PageHtmlResponse, PageResponse } from "../api/types";
import { createAppRouter } from "../router";
import { primePageDiscussionLists, emptyDiscussionList } from "./pageDiscussionFixture";
import { pageDiscussionsQuery } from "../api/discussions";

/**
 * Chunk-4 doc reading metadata Rail / footer (addendum §6 acceptance). The Rail renders one
 * row per PRESENT frontmatter key plus the always-present File row; the status chip carries a
 * status-keyed hook; the owner row shows initials. The Rail and footer are app chrome — never
 * descendants of `article.pb-prose`. A pending/errored frontmatter fetch never blanks the doc.
 *
 * `PageContent` is reached via the `/p/$` permalink route, which fetches the page by id and
 * renders `<PageContent>`; we prime BOTH `pageQuery` (frontmatter + permalink resolution) and
 * `pageHtmlQuery` (prose + TOC) with an explicit cached default discussion list.
 */

const PAGE_ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";

function htmlResponse(id: string, headings: PageHtmlResponse["headings"]): PageHtmlResponse {
  const title = "Kubernetes";
  return {
    id,
    root: "docs",
    path: "infra/kubernetes.md",
    slug: "kubernetes",
    url: null,
    title,
    html: `<h1 id="t">${title}</h1>`,
    content_hash: "h",
    commit: null,
    headings,
    citation: { page_id: id, heading_id: null, path: "infra/kubernetes.md", content_hash: "h", commit: null, uri: `plainbase://${id}@h` },
  };
}

function pageResponse(id: string, frontmatter: Record<string, unknown>): PageResponse {
  return {
    id,
    root: "docs",
    path: "infra/kubernetes.md",
    slug: "kubernetes",
    url: null,
    title: "Kubernetes",
    markdown: "# Kubernetes",
    frontmatter,
    content_hash: "h",
    id_materialized: true,
    commit: null,
    citation: { page_id: id, heading_id: null, path: "infra/kubernetes.md", content_hash: "h", commit: null, uri: `plainbase://${id}@h` },
  };
}

/** Mounts the permalink route at the bare `/p/{PAGE_ID}`, priming html always and (optionally) frontmatter. */
function renderRail(frontmatter: Record<string, unknown> | null, headings: PageHtmlResponse["headings"] = [{ id: "t", level: 1, text: "Kubernetes" }]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // BOTH legs are keyed BARE: the bare permalink parse has no root, and the route passes the parse's
  // answer to both reads rather than re-pinning the second to the first response's root.
  queryClient.setQueryData(pageHtmlQuery(PAGE_ID, null).queryKey, htmlResponse(PAGE_ID, headings));
  if (frontmatter) queryClient.setQueryData(pageQuery(PAGE_ID, null).queryKey, pageResponse(PAGE_ID, frontmatter));
  primePageDiscussionLists(queryClient);
  const history = createMemoryHistory({ initialEntries: [`/p/${PAGE_ID}`] });
  const router = createAppRouter(queryClient, history);
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

/** Reads each Rail row as [KEY-label, value-text] so absence/presence is asserted by label. */
function railRows(container: HTMLElement): Record<string, string> {
  const rows = [...container.querySelectorAll("[data-pb-rail-meta] .pb-meta-row")];
  return Object.fromEntries(
    rows.map((row) => [row.querySelector(".pb-meta-key")?.textContent ?? "", row.querySelector(".pb-meta-val")?.textContent?.trim() ?? ""]),
  );
}

// `stubNotFound` would fire on the un-primed pageQuery case below; instead we leave fetch
// stubbed to reject so a stray network call is loud rather than silently 404-degrading.
afterEach(() => vi.unstubAllGlobals());

describe("doc reading metadata rail (chunk-4)", () => {
  it("counts unique loaded discussions, signals pagination and drops the count after a failed refresh", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    const item: DiscussionItem = { id: "thread", page: { id: PAGE_ID, path: "infra/kubernetes.md", resolution: "by_id" },
      status: "open", state: "page_level", reason: null, range: null, candidates: null, placement: null,
      quote: null, comment_count: 0, starter: null, created: null, updated: null };
    client.setQueryData(pageQuery(PAGE_ID, null).queryKey, pageResponse(PAGE_ID, {}));
    client.setQueryData(pageHtmlQuery(PAGE_ID, null).queryKey, htmlResponse(PAGE_ID, []));
    client.setQueryData(treeQuery.queryKey, { roots: [{ root: "docs", primary: true, available: true, editable: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } }] });
    client.setQueryData(sessionQuery.queryKey, { authenticated: false, auth_mode: "off", username: null, csrf_token: null });
    const queryKey = pageDiscussionsQuery("docs", PAGE_ID).queryKey;
    client.setQueryData(queryKey, { pages: [
      { ...emptyDiscussionList, discussions: [item], next: "page-two" },
      { ...emptyDiscussionList, discussions: [item, { ...item, id: "second" }], next: "page-three" },
    ], pageParams: [null, "page-two"] });
    vi.stubGlobal("fetch", vi.fn(async () => Response.json({ error: "temporarily_unavailable" }, { status: 503 })));
    const router = createAppRouter(client, createMemoryHistory({ initialEntries: [`/p/${PAGE_ID}`] }));
    const { container } = render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    expect((await screen.findByLabelText("2 or more discussions")).textContent).toBe("2+");
    await act(async () => { await client.refetchQueries({ queryKey }); });
    await waitFor(() => expect(container.querySelector(".pb-discussion-count")).toBeNull());
  });
  it("places copyable metadata after the title outside prose, before body and outline-first rail", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
    const { container } = renderRail({ owner: "Ada Lovelace", status: "active" }, [
      { id: "section", level: 2, text: "Section" }, { id: "second", level: 2, text: "Second" },
    ]);
    const strip = await screen.findByRole("group", { name: "Page properties" });
    expect(strip.closest(".pb-prose")).toBeNull();
    expect(container.querySelector("h1")!.compareDocumentPosition(strip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    fireEvent.click(within(strip).getByRole("button", { name: "Copy file path" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("infra/kubernetes.md"));
    expect(container.querySelector("[data-pb-rail]")!.firstElementChild?.getAttribute("data-pb-toc")).not.toBeNull();
  });
  it("keeps Page Info and TOC beside the default loading, list, detail and hidden workspace", async () => {
    let finish!: (response: Response) => void;
    const pending = new Promise<Response>((resolve) => { finish = resolve; });
    const calls: string[] = [];
    const item = { id: "thread", page: { id: PAGE_ID, path: "infra/kubernetes.md", resolution: "by_id" },
      status: "open", state: "page_level", reason: null, range: null, candidates: null, placement: null,
      quote: null, comment_count: 0, starter: null, created: null, updated: null, anchor: null, reattachment: null };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input); calls.push(url);
      if (url === `/api/v1/pages/${PAGE_ID}/discussions?root=docs&limit=200`) return pending;
      if (url === "/api/v1/discussions/thread?root=docs&limit=50") return Response.json({
        discussion: item, comments: [], next: null, discussions_available: true, reason: null,
      });
      throw new Error(`unexpected fetch: ${url}`);
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(pageQuery(PAGE_ID, null).queryKey, pageResponse(PAGE_ID, { owner: "ops", status: "active" }));
    client.setQueryData(pageHtmlQuery(PAGE_ID, null).queryKey, htmlResponse(PAGE_ID, [
      { id: "a", level: 2, text: "Alpha" }, { id: "b", level: 2, text: "Beta" },
    ]));
    client.setQueryData(treeQuery.queryKey, { roots: [{ root: "docs", primary: true, available: true, editable: true, tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } }] });
    client.setQueryData(sessionQuery.queryKey, { authenticated: false, auth_mode: "off", username: null, csrf_token: null });
    const router = createAppRouter(client, createMemoryHistory({ initialEntries: [`/p/${PAGE_ID}`] }));
    const { container } = render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    await screen.findByText("Loading page discussions…");
    const rail = container.querySelector("[data-pb-rail]")!;
    const info = container.querySelector("[data-pb-rail-meta]")!;
    const toc = rail.querySelector("[data-pb-toc]")!;
    expect(container.querySelector("[data-pb-discussions-nav]")).toBeNull();
    expect(container.querySelector(".pb-reading-column [data-pb-discussions-toggle]")).toBeNull();
    expect(info.closest(".pb-prose")).toBeNull();
    expect(router.history.location.pathname).toBe(`/p/${PAGE_ID}`);
    await act(async () => { finish(Response.json({ ...emptyDiscussionList, discussions: [item] })); });
    expect(await screen.findByLabelText("1 discussion")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Start a discussion" })).toBeTruthy();
    const panel = screen.getByRole("region", { name: "Page discussions" });
    const card = await within(panel).findByRole("button", { name: /About this page/ });
    expect(info.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    fireEvent.click(card);
    await screen.findByRole("heading", { name: "Discussion on infra/kubernetes.md" });
    expect(within(panel).queryByRole("link", { name: /Discussions in|All discussions/ })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Hide discussions" }));
    expect(screen.queryByRole("region", { name: "Page discussions" })).toBeNull();
    expect(container.querySelector("[data-pb-rail-meta]")).toBe(info);
    expect(rail.querySelector("[data-pb-toc]")).toBe(toc);
    fireEvent.click(screen.getByRole("button", { name: "Show discussions" }));
    expect(screen.getByRole("button", { name: "Close discussion" })).toBeTruthy();
    expect(calls).toEqual([`/api/v1/pages/${PAGE_ID}/discussions?root=docs&limit=200`, "/api/v1/discussions/thread?root=docs&limit=50"]);
  });
  it("renders one row per present frontmatter key plus the always-present File row, omitting absent keys", async () => {
    const { container } = renderRail({ owner: "ops", status: "active", tags: ["infra", "kubernetes"], updated: "2026-06-11" });

    await waitFor(() => expect(container.querySelector("[data-pb-rail-meta]")).not.toBeNull());
    const rows = railRows(container);
    expect(Object.keys(rows)).toEqual(["Owner", "Status", "Tags", "Updated", "File"]);
    expect(rows.Owner).toContain("ops");
    expect(rows.Tags).toBe("infrakubernetes"); // .pb-tag::before injects the leading # via CSS (not in textContent)
    expect(rows.Updated).toBe("2026-06-11");
    expect(rows.File).toBe("infra/kubernetes.md");
    // The absent `review` key renders no row.
    expect(Object.keys(rows)).not.toContain("Review");
  });

  it("keys the status chip color hook to the status string", async () => {
    const { container } = renderRail({ status: "active" });
    await waitFor(() => expect(container.querySelector(".pb-chip")).not.toBeNull());
    expect(container.querySelector(".pb-chip")?.getAttribute("data-pb-chip-status")).toBe("active");
    expect(container.querySelector(".pb-chip")?.textContent).toContain("active");
  });

  it("keys the chip to a draft status too", async () => {
    const { container } = renderRail({ status: "draft" });
    await waitFor(() => expect(container.querySelector(".pb-chip")).not.toBeNull());
    expect(container.querySelector(".pb-chip")?.getAttribute("data-pb-chip-status")).toBe("draft");
  });

  it("renders an unrecognized status without throwing (neutral chip, hook carries the raw string)", async () => {
    const { container } = renderRail({ status: "experimental" });
    await waitFor(() => expect(container.querySelector(".pb-chip")).not.toBeNull());
    expect(container.querySelector(".pb-chip")?.getAttribute("data-pb-chip-status")).toBe("experimental");
  });

  it("derives the owner avatar initials (ops → OP)", async () => {
    const { container } = renderRail({ owner: "ops" });
    await waitFor(() => expect(container.querySelector(".pb-avatar")).not.toBeNull());
    expect(container.querySelector(".pb-avatar")?.textContent).toBe("OP");
  });

  it("renders the doc footer 'Last updated … by …' line, dropping the by-clause when owner absent", async () => {
    const withOwner = renderRail({ owner: "ops", updated: "2026-06-11" });
    await waitFor(() => expect(withOwner.container.querySelector("[data-pb-docfoot]")).not.toBeNull());
    expect(withOwner.container.querySelector(".pb-docfoot-updated")?.textContent).toBe("Last updated 2026-06-11 by ops");
    withOwner.unmount();

    const noOwner = renderRail({ updated: "2026-06-11" });
    await waitFor(() => expect(noOwner.container.querySelector("[data-pb-docfoot]")).not.toBeNull());
    expect(noOwner.container.querySelector(".pb-docfoot-updated")?.textContent).toBe("Last updated 2026-06-11");

    // No `updated` → no footer line at all.
    const noUpdated = renderRail({ owner: "ops" });
    await waitFor(() => expect(noUpdated.container.querySelector(".pb-prose h1")).not.toBeNull());
    expect(noUpdated.container.querySelector("[data-pb-docfoot]")).toBeNull();
  });

  it("renders the file path as a truncatable button — full path in the title and as text, click toggles expand", async () => {
    const { container } = renderRail({ owner: "ops" });
    await waitFor(() => expect(container.querySelector("[data-pb-path]")).not.toBeNull());
    const pathEl = container.querySelector("[data-pb-path]") as HTMLButtonElement;
    // The full path is the source of truth — available on hover (title) and as text content even
    // though CSS truncates the visible line; collapsed by default.
    expect(pathEl.getAttribute("title")).toBe("infra/kubernetes.md");
    expect(pathEl.textContent).toBe("infra/kubernetes.md");
    expect(pathEl.getAttribute("aria-expanded")).toBe("false");
    // Clicking expands to the full wrapped path.
    fireEvent.click(pathEl);
    expect(pathEl.getAttribute("aria-expanded")).toBe("true");
  });

  it("keeps all chrome OUT of the rendered markdown (.pb-prose), and the rail a non-descendant", async () => {
    const { container } = renderRail({ owner: "ops", status: "active", tags: ["infra"], updated: "2026-06-11" });
    await waitFor(() => expect(container.querySelector("[data-pb-rail]")).not.toBeNull());

    // The guard: no chrome node lives under article.pb-prose.
    expect(container.querySelector(".pb-prose [data-pb-rail]")).toBeNull();
    expect(container.querySelector(".pb-prose .pb-chip")).toBeNull();
    expect(container.querySelector(".pb-prose .pb-avatar")).toBeNull();
    expect(container.querySelector(".pb-prose .pb-tag")).toBeNull();
    expect(container.querySelector(".pb-prose [data-pb-docfoot]")).toBeNull();

    // Conversely the rail exists, as a NON-descendant of .pb-prose.
    const rail = container.querySelector("[data-pb-rail]");
    expect(rail).not.toBeNull();
    expect(rail!.closest(".pb-prose")).toBeNull();
  });

  it("reuses the by-path page for the rail on /docs — no redundant /api/v1/pages/:id fetch", async () => {
    // The /$ route already resolved the page (with frontmatter) via pageByPathQuery and hands
    // it to PageContent, so the rail reads that metadata directly. pageQuery(id, root) is left un-primed
    // and the stub THROWS for every url but the two the chrome legitimately reads (the tree, for
    // Breadcrumbs/Sidebar, and the session, for the Shell's Review gate), so a stray by-id fetch blows up
    // loudly instead of being answered 200 by a permissive stub. (Codex P2.) The urls are recorded anyway:
    // the throw proves SOMETHING unexpected fired, the record names it.
    const calls: string[] = [];
    const emptyRoot = { roots: [{ root: "docs", primary: true, available: true, editable: true,
      tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } }] };
    const session = { authenticated: false, username: null, csrf_token: null, auth_mode: "off" };
    const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      calls.push(url);
      const path = new URL(url, "http://x").pathname;
      if (path === "/api/v1/tree") return json(emptyRoot);
      if (path === "/api/v1/session") return json(session);
      if (path === `/api/v1/pages/${PAGE_ID}/discussions` && new URL(url, "http://x").searchParams.get("root") === "docs") return json(emptyDiscussionList);
      throw new Error(`unexpected fetch: ${url}`);
    }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    queryClient.setQueryData(pageByPathQuery("docs/infra/kubernetes").queryKey, pageResponse(PAGE_ID, { owner: "ops", status: "active" }));
    queryClient.setQueryData(pageHtmlQuery(PAGE_ID, "docs").queryKey, htmlResponse(PAGE_ID, []));
    const history = createMemoryHistory({ initialEntries: ["/docs/infra/kubernetes"] });
    const router = createAppRouter(queryClient, history);
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    // The rail shows the by-path frontmatter…
    await waitFor(() => expect(container.querySelector("[data-pb-rail-meta]")).not.toBeNull());
    expect(railRows(container).Owner).toContain("ops");
    expect(container.querySelector(".pb-chip")?.getAttribute("data-pb-chip-status")).toBe("active");
    // …without the redundant by-id page fetch (/api/v1/pages/:id) Codex flagged. The /html and
    // by-path endpoints are distinct paths, so this only catches the duplicate page load.
    // Compared as a PATHNAME, not as a URL tail: the by-id read carries `?root=docs` now, so a
    // tail match could never fire again while still reporting green.
    await waitFor(() => expect(calls).toContain(`/api/v1/pages/${PAGE_ID}/discussions?root=docs&limit=200`));
    expect(calls.some((u) => new URL(u, "http://x").pathname === `/api/v1/pages/${PAGE_ID}`)).toBe(false);
  });

  it("renders a File-only rail (doc never blank) when the page carries no frontmatter", async () => {
    // The "HTML is primary" contract: an empty/absent frontmatter degrades the rail to its
    // always-present File row, while prose + TOC render unaffected.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const headings = [
      { id: "a", level: 2, text: "Alpha" },
      { id: "b", level: 2, text: "Beta" },
    ];
    queryClient.setQueryData(pageByPathQuery("docs/infra/kubernetes").queryKey, pageResponse(PAGE_ID, {}));
    queryClient.setQueryData(pageHtmlQuery(PAGE_ID, "docs").queryKey, htmlResponse(PAGE_ID, headings));
    primePageDiscussionLists(queryClient);
    const history = createMemoryHistory({ initialEntries: ["/docs/infra/kubernetes"] });
    const router = createAppRouter(queryClient, history);
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(container.querySelector(".pb-prose h1")?.textContent).toContain("Kubernetes"));
    expect(container.querySelector("[data-pb-toc]")).not.toBeNull();
    await waitFor(() => expect(railRows(container)).toEqual({ File: "infra/kubernetes.md" }));
  });
});
