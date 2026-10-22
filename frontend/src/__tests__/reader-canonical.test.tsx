import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { byPathKeyForUrl, pageByPathQuery, pageHtmlQuery, pageQuery, sessionQuery, treeQuery } from "../api/queries";
import type { PageHtmlResponse, PageResponse, SessionResponse, TreeFolder, TreePage, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";
import { folderByUrl, samePathname } from "../lib/tree";
import { primePageDiscussionLists } from "./pageDiscussionFixture";

const READER: SessionResponse = { authenticated: false, auth_mode: "reader" };
const OFF: SessionResponse = { authenticated: false, auth_mode: "off" };

function record(tail: string, path: string, title: string, url: string | null): PageResponse {
  const id = `01970000-0000-7000-8000-00000000a1${tail}`;
  return {
    id, root: "docs", path, slug: path.split("/").at(-1)!.replace(/\.md$/, ""), title, url,
    markdown: `# ${title}\n\n${title} unique sentence.\n`, frontmatter: {}, content_hash: "h",
    id_materialized: true, commit: null,
    citation: { page_id: id, heading_id: null, path, content_hash: "h", commit: null, uri: `plainbase://docs/${id}@h` },
  };
}

const guides = record("20", "guides/index.md", "Guide landing", "/docs/guides/index");
const shadowOwner = record("21", "shadow.md", "Shadow owner", "/docs/shadow");
const shadow = record("22", "shadow/index.md", "Shadow landing", "/docs/shadow/index");
const reference = record("23", "ref/index.md", "Reference landing", "/docs/ref/index");
const other = record("24", "other.md", "Other owner", "/docs/other");
const named = record("25", "named/index.md", "Named landing", "/docs/named/index");
const root = record("26", "index.md", "Root landing", "/docs/index");
const duplicateOwner = record("27", "dup.md", "Duplicate owner", "/docs/dup");
const duplicate = record("28", "dup/index.md", "Duplicate landing", null);
const lost = record("29", "lost/index.md", "Lost landing", null);
const twin = record("2a", "twin/index.md", "Twin landing", null);
const loose = record("2b", "loose.md", "Loose page", null);
const cafe = record("2c", "café/index.md", "Cafe landing", "/docs/caf%C3%A9/index");
const cafeDetail = record("2d", "café/details.md", "Cafe details", "/docs/caf%C3%A9/details");
const percent = record("2e", "a%41/index.md", "Literal percent landing", "/docs/a%2541/index");
const asciiSibling = record("2f", "aA/index.md", "ASCII sibling landing", "/docs/aA/index");
const escapedQuestion = record("30", "a%3F/index.md", "Literal question escape landing", "/docs/a%253F/index");
const question = record("31", "a?/index.md", "Question landing", "/docs/a%3F/index");
const alpha = record("32", "guides/alpha.md", "Guide alpha", "/docs/guides/alpha");
const records = [guides, shadowOwner, shadow, reference, other, named, root, duplicateOwner, duplicate, lost, twin, loose,
  cafe, cafeDetail, percent, asciiSibling, escapedQuestion, question, alpha];

function node(page: PageResponse): TreePage {
  return { type: "page", id: page.id, path: page.path, slug: page.slug, title: page.title, url: page.url,
    status: "active", updated: null };
}

function folder(path: string, url: string | null, landing: PageResponse): TreeFolder {
  return { type: "folder", name: path, title: null, description: null, path, url, page_count: 1, children: [node(landing)] };
}

const tree: TreeResponse = { roots: [{
  root: "docs", primary: true, available: true, editable: false, discussionsEnabled: false,
  tree: {
    type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: records.length,
    children: [
      node(root), { ...folder("guides", "/docs/guides", guides), page_count: 2, children: [node(guides), node(alpha)] },
      node(shadowOwner), folder("shadow", "/docs/shadow", shadow),
      folder("ref", "/docs/ref", reference), node(other), folder("named", "/docs/named", named), node(duplicateOwner),
      folder("dup", "/docs/dup", duplicate), folder("lost", null, lost), folder("twin", "/docs/twin", twin), node(loose),
      { ...folder("café", "/docs/caf%C3%A9", cafe), page_count: 2, children: [node(cafe), node(cafeDetail)] },
      folder("a%41", "/docs/a%2541", percent), folder("aA", "/docs/aA", asciiSibling),
      folder("a%3F", "/docs/a%253F", escapedQuestion), folder("a?", "/docs/a%3F", question),
    ],
  },
}] };

function html(page: PageResponse): PageHtmlResponse {
  return {
    id: page.id, root: page.root, path: page.path, slug: page.slug, url: page.url, title: page.title,
    html: `<h1>${page.title}</h1><p>${page.title} unique sentence.</p>`, content_hash: page.content_hash,
    commit: null, headings: [], citation: page.citation,
  };
}

interface RenderOptions {
  editable?: boolean;
  session?: SessionResponse;
  seedSession?: boolean;
  seedTree?: boolean;
  seedAddress?: boolean;
  probes?: Record<string, PageResponse>;
  responses?: Record<string, () => Response | Promise<Response>>;
}

function renderAt(path: string, addressed: PageResponse, options: RenderOptions = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  if (options.seedTree !== false) queryClient.setQueryData(treeQuery.queryKey,
    options.editable ? { roots: tree.roots.map((root) => ({ ...root, editable: true })) } : tree);
  if (options.seedSession !== false) queryClient.setQueryData(sessionQuery.queryKey, options.session ?? READER);
  for (const page of records) {
    queryClient.setQueryData(pageQuery(page.id, page.root).queryKey, page);
    queryClient.setQueryData(pageHtmlQuery(page.id, page.root).queryKey, html(page));
  }
  if (options.seedAddress !== false) {
    queryClient.setQueryData(pageByPathQuery(byPathKeyForUrl(new URL(path, "http://test").pathname)!).queryKey, addressed);
  }
  for (const [key, page] of Object.entries(options.probes ?? {})) {
    queryClient.setQueryData(pageByPathQuery(key).queryKey, page);
  }
  primePageDiscussionLists(queryClient);
  if (options.seedTree === false) queryClient.removeQueries({ queryKey: treeQuery.queryKey, exact: true });
  const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
    const pathname = new URL(String(input), "http://test").pathname;
    return options.responses?.[pathname]?.() ?? Response.json(
      { error: { code: "page_not_found", message: "No page at that path" } }, { status: 404 },
    );
  });
  vi.stubGlobal("fetch", fetchSpy);
  const history = createMemoryHistory({ initialEntries: [path] });
  const replace = vi.spyOn(history, "replace");
  const router = createAppRouter(queryClient, history);
  const view = render(<QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>);
  return { view, history, router, replace, queryClient, fetchSpy };
}

type App = ReturnType<typeof renderAt>;

async function ready(app: App) {
  await waitFor(() => expect(app.view.container.querySelector("[data-pb-main]")?.textContent?.trim()).toBeTruthy());
  await waitFor(() => expect(app.router.state.isLoading).toBe(false));
  await act(async () => {});
}

async function expectAt(app: App, path: string, page: PageResponse) {
  await ready(app);
  await waitFor(() => {
    expect(app.history.location.pathname).toBe(path);
    const article = app.view.container.querySelector("[data-pb-page-article]");
    expect(article?.getAttribute("data-pb-page-id")).toBe(page.id);
    expect(article?.textContent).toContain(`${page.title} unique sentence.`);
  });
}

function requested(app: App, path: string) {
  return app.fetchSpy.mock.calls.some(([input]) => new URL(String(input), "http://test").pathname === path);
}

function deferredResponse() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>((done) => { resolve = done; });
  return { promise, resolve };
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

interface CanonicalRow {
  name: string;
  path: string;
  page: PageResponse;
  destination: string;
  rendered?: PageResponse;
  options?: RenderOptions;
}

const rows: CanonicalRow[] = [
  { name: "canonicalizes a Unicode reader landing child", path: "/docs/caf%C3%A9/index", page: cafe,
    destination: "/docs/caf%C3%A9" },
  { name: "canonicalizes a Unicode workspace landing child", path: "/docs/caf%C3%A9/index", page: cafe,
    destination: "/docs/caf%C3%A9", options: { session: OFF } },
  { name: "keeps a shadowed landing child", path: "/docs/shadow/index", page: shadow, destination: "/docs/shadow/index",
    options: { probes: { "docs/shadow": shadowOwner } } },
  { name: "keeps a landing whose folder is alias-owned", path: "/docs/ref/index", page: reference, destination: "/docs/ref/index",
    options: { probes: { "docs/ref": other } } },
  { name: "canonicalizes a shadowed landing alias to its own page", path: "/docs/old-shadow", page: shadow,
    destination: "/docs/shadow/index", options: { probes: { "docs/shadow": shadowOwner } } },
  { name: "uses the folder after its by-path miss", path: "/docs/guides/index", page: guides, destination: "/docs/guides" },
  { name: "keeps a landing alias at its own folder URL", path: "/docs/named", page: named, destination: "/docs/named" },
  { name: "preserves workspace shadowed-landing replacement", path: "/docs/shadow/index", page: shadow,
    destination: "/docs/shadow", rendered: shadowOwner, options: { session: OFF, probes: { "docs/shadow": shadowOwner } } },
  { name: "uses a permalink when another page owns a null-URL landing's folder", path: "/docs/old-dup", page: duplicate,
    destination: `/p/docs/${duplicate.id}`, options: { probes: { "docs/dup": duplicateOwner } } },
  { name: "uses a permalink when the landing folder URL is null", path: "/docs/old-lost", page: lost,
    destination: `/p/docs/${lost.id}` },
  { name: "preserves the workspace alias when its landing folder URL is null", path: "/docs/old-lost", page: lost,
    destination: "/docs/old-lost", options: { session: OFF } },
  { name: "uses the folder when its alias returns the same rooted page", path: "/docs/named/index", page: named,
    destination: "/docs/named", options: { probes: { "docs/named": named } } },
  { name: "uses a permalink for a null-URL non-landing page", path: "/docs/old-loose", page: loose,
    destination: `/p/docs/${loose.id}` },
  { name: "preserves the workspace alias for a null-URL non-landing page", path: "/docs/old-loose", page: loose,
    destination: "/docs/old-loose", options: { session: OFF } },
];

describe("reader canonical destinations", () => {
  it.each(["", "/"])("takes over a decomposed Unicode folder landing with suffix '%s'", async (suffix) => {
    const address = `/docs/cafe%CC%81${suffix}`;
    const app = renderAt(address, cafe, { seedAddress: false });
    await ready(app);
    expect(app.router.state.location.pathname).toBe(`/docs/cafe\u0301${suffix}`);
    await expectAt(app, address, cafe);
    expect(app.view.container.querySelector("[data-pb-not-found]")).toBeNull();
    const label = app.view.container.querySelector('aside a[href="/docs/caf%C3%A9"]');
    expect(label?.getAttribute("aria-current")).toBe("page");
    expect(app.replace).not.toHaveBeenCalled();
  });

  it.each([
    { name: "reader", session: READER, suffix: "" },
    { name: "workspace read", session: OFF, suffix: "" },
    { name: "workspace edit", session: OFF, suffix: "?mode=edit#draft" },
  ])("replaces a decomposed $name page exactly once to its composed URL", async ({ session, suffix }) => {
    const app = renderAt(`/docs/cafe%CC%81/details${suffix}`, cafeDetail, { session, editable: true });
    await ready(app);
    await waitFor(() => expect(app.history.location.href).toBe(`/docs/caf%C3%A9/details${suffix}`));
    if (suffix) {
      expect(app.view.container.querySelector("[data-pb-editor]")).not.toBeNull();
    } else {
      await expectAt(app, "/docs/caf%C3%A9/details", cafeDetail);
    }
    await act(async () => {});
    expect(app.replace).toHaveBeenCalledTimes(1);
    expect(app.queryClient.getQueryData(pageByPathQuery("docs/café/details").queryKey)).toEqual(cafeDetail);
  });

  it.each([
    { name: "landing child", address: "/docs/guides/index/", page: guides, folder: "guides" },
    { name: "page alias", address: "/docs/old-alias/", page: alpha, folder: "guides" },
    { name: "decomposed page", address: "/docs/cafe%CC%81/details/", page: cafeDetail, folder: "café" },
  ])("opens workspace creation after soft navigation to a slashed $name", async ({ address, page, folder }) => {
    const routeKey = byPathKeyForUrl(address.slice(0, -1))!;
    const app = renderAt("/docs/guides/alpha", alpha, { seedSession: false, editable: true, probes: { [routeKey]: page } });
    await expectAt(app, "/docs/guides/alpha", alpha);
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error"));
    await act(async () => app.history.push(address));
    await expectAt(app, address, page);
    expect(app.queryClient.getQueryState(pageByPathQuery(routeKey).queryKey)?.status).toBe("success");
    const control = app.view.container.querySelector<HTMLElement>("[data-pb-new-page]");
    expect(control).not.toBeNull();
    expect(control?.hasAttribute("disabled")).toBe(false);
    fireEvent.click(control!);
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    expect(app.view.container.querySelector<HTMLSelectElement>("[data-pb-new-folder]")?.value).toBe(JSON.stringify(["folder", folder]));
    expect(app.history.location.pathname).toBe(address);
    expect(app.replace).not.toHaveBeenCalled();
  });

  it.each(["/docs", "/docs/"])("marks the root home row current at %s", async (address) => {
    const app = renderAt(address, root, { seedAddress: false });
    await expectAt(app, address, root);
    const home = app.view.container.querySelector('aside a[href="/docs"]');
    expect(home).not.toBeNull();
    expect(home?.getAttribute("aria-current")).toBe("page");
    expect(app.replace).not.toHaveBeenCalled();
  });

  it.each(["pending", "settled"])("keeps a slashed alias at its own folder with the disabled probe %s", async (state) => {
    // The empty by-path key is the disabled ownership probe's fallback, seeded to prove its explicit folder comparison.
    const app = renderAt("/docs/named/", named, { seedAddress: false, probes: {
      "docs/named": named, ...(state === "settled" ? { "": other } : {}),
    } });
    await expectAt(app, "/docs/named/", named);
    expect(app.queryClient.getQueryState(pageByPathQuery("").queryKey)?.fetchStatus).toBe("idle");
    expect(app.replace).not.toHaveBeenCalled();
    expect(app.fetchSpy.mock.calls.some(([input]) => String(input).includes("/api/v1/pages/by-path/"))).toBe(false);
    expect(app.view.container.querySelector("[data-pb-not-found]")).toBeNull();
  });

  it("reads a trailing-slash page without a not-found view", async () => {
    const app = renderAt("/docs/guides/alpha/", alpha, { seedAddress: false, responses: {
      "/api/v1/pages/by-path/docs/guides/alpha": () => Response.json(alpha),
    } });
    await ready(app);
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-page-article]")?.getAttribute("data-pb-page-id")).toBe(alpha.id));
    expect(app.view.container.querySelector("[data-pb-page-article]")?.textContent).toContain("Guide alpha unique sentence.");
    expect(app.view.container.querySelector("[data-pb-not-found]")).toBeNull();
    expect(requested(app, "/api/v1/pages/by-path/docs/guides/alpha")).toBe(true);
  });

  it.each([
    { path: "/docs/guides/", destination: "/docs/guides/", page: guides },
    { path: "/docs/guides/alpha/", destination: "/docs/guides/alpha", page: alpha },
  ])("keeps the reader content served at $path", async ({ path, destination, page }) => {
    const app = renderAt(path, page, { seedAddress: false, responses: {
      "/api/v1/pages/by-path/docs/guides/alpha": () => Response.json(alpha),
      "/api/v1/pages/by-path/docs/guides/alpha/": () => Response.json(
        { error: { code: "invalid_path", message: "Trailing API segment" } }, { status: 400 },
      ),
    } });
    await expectAt(app, destination, page);
    expect(app.view.container.querySelector("[data-pb-not-found]")).toBeNull();
  });

  it("keeps the existing bare-root trailing-slash address", async () => {
    const app = renderAt("/docs/", root, { seedAddress: false });
    await expectAt(app, "/docs/", root);
    expect(app.replace).not.toHaveBeenCalled();
    expect(app.view.container.querySelector("[data-pb-not-found]")).toBeNull();
  });

  it.each(["/new/", "/admin/", "/review/", "/discussions/"])("keeps non-content route %s not found", async (path) => {
    const app = renderAt(path, alpha);
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
    expect(app.view.container.querySelector("[data-pb-page-article]")).toBeNull();
    expect(app.replace).not.toHaveBeenCalled();
  });

  it.each([
    { url: "/docs/a%253F", page: escapedQuestion },
    { url: "/docs/a%3F", page: question },
  ])("resolves the reserved-escape sibling $url to itself in the real router", async ({ url, page }) => {
    const app = renderAt(url, page, { seedAddress: false });
    await ready(app);
    expect(app.router.state.location.pathname).toBe(url);
    await expectAt(app, url, page);
    expect(folderByUrl(tree.roots, app.router.state.location.pathname)?.folder.path).toBe(page.path.replace(/\/index.md$/, ""));
    expect(app.replace).not.toHaveBeenCalled();
    expect(requested(app, `/api/v1/pages/by-path${url}`)).toBe(true);
  });

  it.each(["500", "transport"])("keeps the served shadowed landing after a settled session %s", async (failure) => {
    const app = renderAt("/docs/shadow/index", shadow, { seedSession: false, probes: { "docs/shadow": shadowOwner }, responses: {
      "/api/v1/session": () => {
        if (failure === "transport") throw new TypeError("Connection lost");
        return Response.json({ error: { code: "unavailable", message: "Session unavailable" } }, { status: 500 });
      },
    } });
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error"));
    await expectAt(app, "/docs/shadow/index", shadow);
    expect(app.replace).not.toHaveBeenCalled();
  });

  it("unlocks OFF landing canonicalization only after the session 404", async () => {
    const app = renderAt("/docs/shadow/index", shadow, { seedSession: false, probes: { "docs/shadow": shadowOwner } });
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error"));
    await expectAt(app, "/docs/shadow", shadowOwner);
  });

  it("canonicalizes a Unicode workspace alias while editing and keeps its search and hash", async () => {
    const app = renderAt("/docs/caf%C3%A9/old?mode=edit#draft", cafeDetail, { session: OFF, editable: true });
    await ready(app);
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-editor]")).not.toBeNull());
    await waitFor(() => expect(app.history.location.href).toBe("/docs/caf%C3%A9/details?mode=edit#draft"));
    expect(app.router.state.location.pathname).toBe("/docs/café/details");
    expect(app.view.container.querySelector("[data-pb-editor]")).not.toBeNull();
    expect(app.queryClient.getQueryData(pageByPathQuery("docs/café/details").queryKey)).toEqual(cafeDetail);
  });

  it("marks a Unicode folder active and expands its sidebar children", async () => {
    const app = renderAt("/docs/caf%C3%A9", cafe, { seedAddress: false });
    await expectAt(app, "/docs/caf%C3%A9", cafe);
    const link = app.view.container.querySelector('aside a[href="/docs/caf%C3%A9"]');
    expect(link).not.toBeNull();
    expect(link?.getAttribute("aria-current")).toBe("page");
    expect(app.view.container.querySelector('aside a[href="/docs/caf%C3%A9/details"]')).not.toBeNull();
  });

  it("expands a Unicode page's ancestors and highlights it", async () => {
    const app = renderAt("/docs/caf%C3%A9/details", cafeDetail, { session: OFF, editable: true });
    await expectAt(app, "/docs/caf%C3%A9/details", cafeDetail);
    const link = app.view.container.querySelector('aside a[href="/docs/caf%C3%A9/details"]');
    expect(link).not.toBeNull();
    expect(link?.getAttribute("aria-current")).toBe("page");
  });

  it("enables header creation on a canonical Unicode workspace page", async () => {
    const app = renderAt("/docs/caf%C3%A9/details", cafeDetail, { session: OFF, editable: true });
    await expectAt(app, "/docs/caf%C3%A9/details", cafeDetail);
    const creation = app.view.container.querySelector("[data-pb-new-page]");
    expect(creation).not.toBeNull();
    expect(creation?.hasAttribute("disabled")).toBe(false);
  });

  it("keeps a literal percent escape distinct from its ASCII sibling in the real router", async () => {
    const app = renderAt("/docs/a%2541", percent, { seedAddress: false });
    await expectAt(app, "/docs/a%2541", percent);
    expect(app.router.state.location.pathname).toBe("/docs/a%2541");
    expect(samePathname(app.router.state.location.pathname, "/docs/aA")).toBe(false);
    expect(folderByUrl(tree.roots, app.router.state.location.pathname)?.folder.path).toBe("a%41");
    expect(folderByUrl(tree.roots, "/docs/aA")?.folder.path).toBe("aA");
  });

  it.each([{ label: "reader", session: READER }, { label: "workspace", session: OFF }])(
    "renders a Unicode folder landing in a $label after the by-path miss",
    async ({ session }) => {
      const app = renderAt("/docs/caf%C3%A9", cafe, { session, seedAddress: false });
      await ready(app);
      expect(app.router.state.location.pathname).toBe("/docs/café");
      await expectAt(app, "/docs/caf%C3%A9", cafe);
    },
  );

  it.each(rows)("$name", async (row) => {
    const app = renderAt(row.path, row.page, row.options);
    await expectAt(app, row.destination, row.rendered ?? row.page);
    for (const [key, page] of Object.entries(row.options?.probes ?? {})) {
      expect(app.queryClient.getQueryData(pageByPathQuery(key).queryKey)).toEqual(page);
    }
    if (row.destination !== row.path && row.destination === row.page.url) {
      expect(app.queryClient.getQueryData(pageByPathQuery(row.destination.slice(1)).queryKey)).toEqual(row.page);
    }
  });

  it("keeps the landing child when its folder claim is unverified", async () => {
    const app = renderAt("/docs/ref/index", reference, { responses: {
      "/api/v1/pages/by-path/docs/ref": () => Response.json(
        { error: { code: "absence_unverified", message: "Alias absence is unverified" } }, { status: 503 },
      ),
    } });
    await ready(app);
    await waitFor(() => expect(app.queryClient.getQueryState(pageByPathQuery("docs/ref").queryKey)?.status).toBe("error"));
    expect(requested(app, "/api/v1/pages/by-path/docs/ref")).toBe(true);
    await expectAt(app, "/docs/ref/index", reference);
  });

  it("uses the root folder without probing its by-path key", async () => {
    const app = renderAt("/docs/index", root);
    await expectAt(app, "/docs", root);
    expect(requested(app, "/api/v1/pages/by-path/docs")).toBe(false);
  });

  it("waits for the session before replacing or probing a shadowed landing", async () => {
    const held = deferredResponse();
    const app = renderAt("/docs/shadow/index", shadow, { seedSession: false, responses: {
      "/api/v1/session": () => held.promise,
      "/api/v1/pages/by-path/docs/shadow": () => Response.json(shadowOwner),
    } });
    try {
      await ready(app);
      await waitFor(() => expect(requested(app, "/api/v1/session")).toBe(true));
      expect(app.history.location.pathname).toBe("/docs/shadow/index");
      expect(app.replace).not.toHaveBeenCalled();
      expect(requested(app, "/api/v1/pages/by-path/docs/shadow")).toBe(false);
      await act(async () => held.resolve(Response.json(READER)));
      await waitFor(() => expect(requested(app, "/api/v1/pages/by-path/docs/shadow")).toBe(true));
      await expectAt(app, "/docs/shadow/index", shadow);
    } finally {
      await act(async () => held.resolve(Response.json(READER)));
    }
  });

  it.each([
    { name: "null-URL landing aliased at its folder", path: "/docs/twin", page: twin },
    { name: "landing aliased at its folder", path: "/docs/named", page: named },
  ])("waits for the tree for a $name", async ({ path, page }) => {
    const held = deferredResponse();
    const app = renderAt(path, page, { seedTree: false, responses: { "/api/v1/tree": () => held.promise } });
    try {
      await ready(app);
      await waitFor(() => expect(requested(app, "/api/v1/tree")).toBe(true));
      expect(app.view.container.querySelector("[data-pb-page-article]")?.getAttribute("data-pb-page-id")).toBe(page.id);
      expect(app.replace).not.toHaveBeenCalled();
      expect(app.history.location.pathname).toBe(path);
      await act(async () => held.resolve(Response.json(tree)));
      await expectAt(app, path, page);
      expect(app.replace).not.toHaveBeenCalled();
    } finally {
      await act(async () => held.resolve(Response.json(tree)));
    }
  });
});
