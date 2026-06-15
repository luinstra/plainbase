import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, pageQuery, treeQuery } from "../api/queries";
import type { PageHtmlResponse, PageResponse, TreeFolder, TreePage, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

/**
 * Folder landing views (ADR-0003): the by-path 404 fallthrough resolves the location
 * against the tree's folder `url`s — README-preference (`index` > `readme`) renders the
 * child page AT the folder URL, otherwise the generated listing in tree order; a page
 * owning the URL always shadows the folder. Queries are primed; by-path 404s come from a
 * stubbed fetch.
 */

const PAGE_ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
const README_ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b01";
const INDEX_ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b02";
const LOSER_ID = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99";

function pageNode(id: string, path: string, title: string, url: string | null, updated: string | null = null): TreePage {
  const slug = path.slice(path.lastIndexOf("/") + 1).replace(/\.md$/, "");
  return { type: "page", id, title, slug, path, url, status: "active", updated };
}

function tree(guidesChildren: TreeFolder["children"]): TreeResponse {
  const pageCount = guidesChildren.filter((c) => c.type === "page").length;
  return {
    root: {
      type: "folder",
      name: "",
      title: null,
      description: null,
      path: "",
      url: "/docs",
      page_count: 0,
      children: [
        { type: "folder", name: "guides", title: "Guides", description: null, path: "guides", url: "/docs/guides", page_count: pageCount, children: guidesChildren },
      ],
    },
  };
}

function htmlResponse(id: string, title: string): PageHtmlResponse {
  return {
    id,
    path: "guides/x.md",
    slug: "x",
    url: null,
    title,
    html: `<h1 id="t">${title}</h1>`,
    content_hash: "h",
    commit: null,
    headings: [{ id: "t", level: 1, text: title }],
    citation: { page_id: id, heading_id: null, path: "guides/x.md", content_hash: "h", commit: null, uri: `plainbase://${id}@h` },
  };
}

function pageResponse(id: string, url: string | null, title: string): PageResponse {
  return {
    id,
    path: "guides.md",
    slug: "guides",
    url,
    title,
    markdown: "# x",
    frontmatter: {},
    content_hash: "h",
    id_materialized: true,
    commit: null,
    citation: { page_id: id, heading_id: null, path: "guides.md", content_hash: "h", commit: null, uri: `plainbase://${id}@h` },
  };
}

/** Stubs fetch so any by-path lookup 404s — the server's answer for a folder URL. */
function stubNotFound() {
  const envelope = { error: { code: "page_not_found", message: "No page at that path" } };
  const spy = vi.fn(async () => new Response(JSON.stringify(envelope), { status: 404, headers: { "content-type": "application/json" } }));
  vi.stubGlobal("fetch", spy);
  return spy;
}

function renderAt(initialPath: string, treeData: TreeResponse, prime: (qc: QueryClient) => void = () => {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, treeData);
  prime(queryClient);
  const history = createMemoryHistory({ initialEntries: [initialPath] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { history, view };
}

afterEach(() => vi.unstubAllGlobals());

describe("folder landing views (ADR-0003)", () => {
  it("renders a README child's content at the folder URL — address bar unchanged, fetched by id", async () => {
    stubNotFound();
    const readmeTree = tree([
      pageNode(README_ID, "guides/README.md", "Guides Overview", "/docs/guides/readme"),
      pageNode(PAGE_ID, "guides/deploy-guide.md", "Deploy Guide", "/docs/guides/deploy-guide"),
    ]);
    const { history, view } = renderAt("/docs/guides", readmeTree, (qc) => {
      qc.setQueryData(pageHtmlQuery(README_ID).queryKey, htmlResponse(README_ID, "Guides Overview"));
      qc.setQueryData(pageQuery(README_ID).queryKey, pageResponse(README_ID, null, "Guides Overview"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Guides Overview"));
    expect(history.location.pathname).toBe("/docs/guides"); // a real view, not a redirect
    expect(view.container.querySelector("[data-pb-folder]")).toBeNull();
  });

  it("renders a collision-LOSER README child (url null) at the folder URL — the by-id fetch never consults url", async () => {
    stubNotFound();
    const loserReadmeTree = tree([
      pageNode(README_ID, "guides/README.md", "Guides Overview", null),
      pageNode(PAGE_ID, "guides/deploy-guide.md", "Deploy Guide", "/docs/guides/deploy-guide"),
    ]);
    const { history, view } = renderAt("/docs/guides", loserReadmeTree, (qc) => {
      qc.setQueryData(pageHtmlQuery(README_ID).queryKey, htmlResponse(README_ID, "Guides Overview"));
      qc.setQueryData(pageQuery(README_ID).queryKey, pageResponse(README_ID, null, "Guides Overview"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Guides Overview"));
    expect(history.location.pathname).toBe("/docs/guides");
    expect(view.container.querySelector("[data-pb-folder]")).toBeNull(); // README content, not the listing
  });

  it("a landing child whose frontmatter fetch fails still renders — rail degrades, doc never blanks", async () => {
    // The landing child is the one path where PageContent fetches the page by id itself; a 404 on
    // that frontmatter fetch must degrade the rail (no chip), never error the whole document.
    stubNotFound(); // both the by-path probe AND the un-primed pageQuery(README_ID) 404
    const readmeTree = tree([
      pageNode(README_ID, "guides/README.md", "Guides Overview", "/docs/guides/readme"),
      pageNode(PAGE_ID, "guides/deploy-guide.md", "Deploy Guide", "/docs/guides/deploy-guide"),
    ]);
    // Prime ONLY html (gates the view); leave pageQuery(README_ID) un-primed so its fetch 404s.
    const { view } = renderAt("/docs/guides", readmeTree, (qc) => {
      qc.setQueryData(pageHtmlQuery(README_ID).queryKey, htmlResponse(README_ID, "Guides Overview"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Guides Overview"));
    expect(view.container.querySelector("[data-pb-rail]")).not.toBeNull(); // doc rendered, rail present
    expect(view.container.querySelector(".pb-chip")).toBeNull(); // …but no status: frontmatter never loaded
  });

  it("prefers index over readme when both exist — web-native beats repo-native", async () => {
    stubNotFound();
    const bothTree = tree([
      pageNode(README_ID, "guides/README.md", "Readme Title", "/docs/guides/readme"),
      pageNode(INDEX_ID, "guides/Index.md", "Index Title", "/docs/guides/index"),
    ]);
    const { view } = renderAt("/docs/guides", bothTree, (qc) => {
      qc.setQueryData(pageHtmlQuery(INDEX_ID).queryKey, htmlResponse(INDEX_ID, "Index Title"));
      qc.setQueryData(pageQuery(INDEX_ID).queryKey, pageResponse(INDEX_ID, null, "Index Title"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Index Title"));
  });

  it("renders the generated listing in TREE ORDER within groups when no README/index child exists", async () => {
    stubNotFound();
    // Deliberately not alphabetical: the listing must follow the tree response order WITHIN each
    // group. The card/list rebuild groups subfolders (cards) ahead of pages (rows); each group's
    // own sequence is the tree order — so 'advanced' (the lone folder) leads, then the two pages
    // in their tree order (Zeta before the shadowed loser).
    const listingTree = tree([
      pageNode(PAGE_ID, "guides/zeta.md", "Zeta Page", "/docs/guides/zeta"),
      { type: "folder", name: "advanced", title: null, description: null, path: "guides/advanced", url: "/docs/guides/advanced", page_count: 0, children: [] },
      pageNode(LOSER_ID, "guides/shadowed.md", "Shadowed Page", null),
    ]);
    const { view } = renderAt("/docs/guides", listingTree);

    await waitFor(() => expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-folder] h1")?.textContent).toBe("Guides"); // _folder.yaml title
    // Read each child's primary label (folder name `.fn`, page title `.pt`), not the full card text.
    const items = [...view.container.querySelectorAll("[data-pb-folder-child]")];
    expect(items.map((li) => li.querySelector(".fn, .pt")?.textContent?.trim())).toEqual(["advanced", "Zeta Page", "Shadowed Page"]);
    expect(view.container.querySelector('a[href="/docs/guides/zeta"]')).not.toBeNull();
    expect(view.container.querySelector('a[href="/docs/guides/advanced"]')).not.toBeNull();
    expect(view.container.querySelector(`a[href="/p/${LOSER_ID}"]`)).not.toBeNull(); // loser via permalink
    // The folder trail is "docs / Guides" — the root crumb links home, the current crumb stays inert.
    expect(view.container.querySelector('.pb-breadcrumbs a[href="/docs"]')?.textContent).toBe("docs");
  });

  it("renders a folder card's description + `path · N pages` meta, and a page row's date only when present", async () => {
    stubNotFound();
    const richTree = tree([
      { type: "folder", name: "advanced", title: "Advanced", description: "Deep operational topics.", path: "guides/advanced", url: "/docs/guides/advanced", page_count: 3, children: [] },
      pageNode(PAGE_ID, "guides/dated.md", "Dated Page", "/docs/guides/dated", "2026-05-30"),
      pageNode(LOSER_ID, "guides/undated.md", "Undated Page", "/docs/guides/undated"),
    ]);
    const { view } = renderAt("/docs/guides", richTree);

    await waitFor(() => expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    // Scope to the listing — the sidebar nav renders the same page links without listing markup.
    const listing = view.container.querySelector("[data-pb-folder]")!;
    // Folder card: description line + the page_count-driven meta.
    const card = listing.querySelector('[data-pb-folder-child="folder"]')!;
    expect(card.querySelector(".fm")?.textContent).toContain("Deep operational topics.");
    expect(card.querySelector(".fc")?.textContent).toContain("guides/advanced");
    expect(card.querySelector(".fc")?.textContent).toContain("3 pages");
    // Page rows: the dated row shows its verbatim date; the undated row has no date element.
    const dated = listing.querySelector(`a[href="/docs/guides/dated"]`)!;
    expect(dated.querySelector(".pdate")?.textContent).toBe("2026-05-30");
    const undated = listing.querySelector(`a[href="/docs/guides/undated"]`)!;
    expect(undated.querySelector(".pdate")).toBeNull();
  });

  it("a folder with a single direct page renders `· 1 page` (singular)", async () => {
    stubNotFound();
    const singularTree = tree([
      { type: "folder", name: "solo", title: null, description: null, path: "guides/solo", url: "/docs/guides/solo", page_count: 1, children: [] },
    ]);
    const { view } = renderAt("/docs/guides", singularTree);
    await waitFor(() => expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    const listing = view.container.querySelector("[data-pb-folder]")!;
    expect(listing.querySelector('[data-pb-folder-child="folder"] .fc')?.textContent).toContain("1 page");
  });

  it("renders the ROOT listing at bare /docs — 'docs' fallback heading, grouped tree order, non-link root crumb", async () => {
    const rootTree: TreeResponse = {
      root: {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/docs",
        page_count: 1,
        children: [
          pageNode(PAGE_ID, "welcome.md", "Welcome", "/docs/welcome"),
          { type: "folder", name: "guides", title: "Guides", description: null, path: "guides", url: "/docs/guides", page_count: 0, children: [] },
        ],
      },
    };
    const { view } = renderAt("/docs", rootTree);

    await waitFor(() => expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-folder] h1")?.textContent).toBe("docs");
    // Folders (cards) group ahead of pages (rows): 'Guides' card then 'Welcome' row.
    const items = [...view.container.querySelectorAll("[data-pb-folder-child]")];
    expect(items.map((li) => li.querySelector(".fn, .pt")?.textContent?.trim())).toEqual(["Guides", "Welcome"]);
    expect(view.container.querySelector('a[href="/docs/welcome"]')).not.toBeNull();
    expect(view.container.querySelector('a[href="/docs/guides"]')).not.toBeNull();
    // On the root landing the trail is JUST the non-link "docs" crumb.
    const breadcrumbs = view.container.querySelector(".pb-breadcrumbs")!;
    expect(breadcrumbs.textContent?.trim()).toBe("docs");
    expect(breadcrumbs.querySelector("a")).toBeNull();
  });

  it("page shadows folder: by-path resolves FIRST and the folder view is never consulted", async () => {
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 500 }));
    vi.stubGlobal("fetch", fetchSpy);
    // The tree carries a folder at /docs/guides WITH a README child — but a page owns the URL.
    const shadowedTree = tree([pageNode(README_ID, "guides/README.md", "Guides Overview", "/docs/guides/readme")]);
    const { view } = renderAt("/docs/guides", shadowedTree, (qc) => {
      qc.setQueryData(pageByPathQuery("guides").queryKey, pageResponse(PAGE_ID, "/docs/guides", "Guides The Page"));
      qc.setQueryData(pageHtmlQuery(PAGE_ID).queryKey, htmlResponse(PAGE_ID, "Guides The Page"));
      qc.setQueryData(pageQuery(PAGE_ID).queryKey, pageResponse(PAGE_ID, "/docs/guides", "Guides The Page"));
    });

    await waitFor(() => expect(view.container.querySelector(".pb-prose h1")?.textContent).toContain("Guides The Page"));
    expect(view.container.querySelector("[data-pb-folder]")).toBeNull();
    expect(fetchSpy).not.toHaveBeenCalled(); // the README's html was never fetched
  });

  it("still 404s when the location matches no folder url in the tree", async () => {
    stubNotFound();
    const { view } = renderAt("/docs/nope/never-existed", tree([]));
    await waitFor(() => expect(view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-folder]")).toBeNull();
  });

  it("breadcrumb ancestor crumbs link to their folder landing urls", async () => {
    stubNotFound();
    const crumbTree = tree([pageNode(PAGE_ID, "guides/deploy-guide.md", "Deploy Guide", "/docs/guides/deploy-guide")]);
    const { view } = renderAt("/docs/guides/deploy-guide", crumbTree, (qc) => {
      qc.setQueryData(pageByPathQuery("guides/deploy-guide").queryKey, {
        ...pageResponse(PAGE_ID, "/docs/guides/deploy-guide", "Deploy Guide"),
        path: "guides/deploy-guide.md",
      });
      qc.setQueryData(pageHtmlQuery(PAGE_ID).queryKey, { ...htmlResponse(PAGE_ID, "Deploy Guide"), path: "guides/deploy-guide.md" });
      qc.setQueryData(pageQuery(PAGE_ID).queryKey, { ...pageResponse(PAGE_ID, "/docs/guides/deploy-guide", "Deploy Guide"), path: "guides/deploy-guide.md" });
    });

    await waitFor(() => expect(view.container.querySelector(".pb-breadcrumbs")).not.toBeNull());
    const crumb = view.container.querySelector('.pb-breadcrumbs a[href="/docs/guides"]');
    expect(crumb).not.toBeNull();
    expect(crumb!.textContent).toBe("Guides");
    // The trail opens with the root crumb — "docs / Guides / Deploy Guide", both ancestors clickable.
    const rootCrumb = view.container.querySelector('.pb-breadcrumbs a[href="/docs"]');
    expect(rootCrumb).not.toBeNull();
    expect(rootCrumb!.textContent).toBe("docs");
  });
});
