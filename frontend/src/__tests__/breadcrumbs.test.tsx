import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { treeQuery } from "../api/queries";
import type { TreeResponse } from "../api/types";
import { Breadcrumbs } from "../components/Breadcrumbs";

/**
 * Breadcrumb trail (ADR-0003): the root crumb names the page's ROOT and links to that root's url,
 * ancestor crumbs link to their folder landing views, the leaf is the current page. A section's own
 * index landing must NOT read `<root> / <Title> / <Title>` — the parent crumb and the leaf collapse to
 * one (Phase 5.5 REPLACE landings).
 */

const tree: TreeResponse = {
  roots: [
    {
      root: "docs",
      available: true,
      editable: true,
      primary: true,
      tree: {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/docs",
        page_count: 0,
        children: [
          {
            type: "folder",
            name: "runbooks",
            title: null,
            description: null,
            path: "runbooks",
            url: "/docs/runbooks",
            page_count: 2,
            children: [
              { type: "page", id: "id-index", title: "Runbooks", slug: "index", path: "runbooks/index.md", url: "/docs/runbooks/index", status: "active", updated: null },
              { type: "page", id: "id-deploy", title: "Deploy", slug: "deploy", path: "runbooks/deploy.md", url: "/docs/runbooks/deploy", status: "active", updated: null },
            ],
          },
        ],
      },
    },
    // A second root holding the SAME relative folder path - the lookup must never borrow across roots.
    {
      root: "extra",
      available: true,
      editable: true,
      primary: false,
      tree: {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/extra",
        page_count: 0,
        children: [
          {
            type: "folder",
            name: "runbooks",
            title: "Extra Runbooks",
            description: null,
            path: "runbooks",
            url: "/extra/runbooks",
            page_count: 1,
            children: [
              { type: "page", id: "id-extra", title: "Extra Deploy", slug: "deploy", path: "runbooks/deploy.md", url: "/extra/runbooks/deploy", status: "active", updated: null },
            ],
          },
        ],
      },
    },
  ],
};

function renderCrumbs(path: string, title: string, root = "docs") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, tree);
  return render(
    <QueryClientProvider client={queryClient}>
      <Breadcrumbs root={root} path={path} title={title} />
    </QueryClientProvider>,
  );
}

/** The SINGLE-root install - the shape every legacy `CONTENT_DIR` deployment actually runs. */
function renderSoloCrumbs(path: string, title: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, { roots: [tree.roots[0]] } satisfies TreeResponse);
  return render(
    <QueryClientProvider client={queryClient}>
      <Breadcrumbs root="docs" path={path} title={title} />
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("Breadcrumbs", () => {
  it("drops the redundant ancestor crumb on a section's own index landing", () => {
    // The index page renders AS the /docs/runbooks landing; folderTitle(runbooks) === the index title,
    // so the parent crumb would self-link to the very page shown. The trail collapses to `docs / Runbooks`.
    const { container } = renderCrumbs("runbooks/index.md", "Runbooks");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["docs", "Runbooks"]);
    // No ancestor crumb links to the page being viewed.
    expect(container.querySelector('a[href="/docs/runbooks"]')).toBeNull();
    // The leaf is the non-link current crumb.
    const current = container.querySelector('[aria-current="page"]')!;
    expect(current.textContent).toBe("Runbooks");
  });

  it("keeps the ancestor crumb for a normal page under a folder", () => {
    const { container } = renderCrumbs("runbooks/deploy.md", "Deploy");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["docs", "Runbooks", "Deploy"]);
    // The ancestor links to its folder landing.
    expect(container.querySelector('a[href="/docs/runbooks"]')?.textContent).toBe("Runbooks");
  });

  it("scopes the folder lookup BY root: the same relative path in another root gets ITS titles and urls", () => {
    // Both roots hold `runbooks/`; the extra-root page must crumb through the EXTRA entry's folder
    // (title + url), never the primary root's. The root prop is the scope, because the path alone is ambiguous.
    const { container } = renderCrumbs("runbooks/deploy.md", "Extra Deploy", "extra");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["extra", "Extra Runbooks", "Extra Deploy"]);
    expect(container.querySelector('a[href="/extra/runbooks"]')?.textContent).toBe("Extra Runbooks");
    expect(container.querySelector('a[href="/docs/runbooks"]')).toBeNull();
  });

  it("roots the trail in THIS page's root, linking to that root's own url - never back to /docs", () => {
    // The crumb was hardcoded `{ label: "docs", url: "/docs" }`. On an extra root that named the wrong
    // tree AND, because bare `/docs` resolves to the primary, quietly walked the reader into a DIFFERENT
    // root's copy of the docs. The negative assertion is the load-bearing one.
    const { container } = renderCrumbs("runbooks/deploy.md", "Extra Deploy", "extra");
    const crumb = container.querySelector("a")!;
    expect(crumb.textContent).toBe("extra");
    // The url is the entry's SERVER-ISSUED root url, consumed verbatim and never re-derived from the root name.
    expect(crumb.getAttribute("href")).toBe("/extra");
    expect(container.querySelector('a[href="/docs"]')).toBeNull();
    expect(container.textContent).not.toContain("docs /");
  });

  it("derives a single-root crumb from its server-issued entry", () => {
    const { container } = renderSoloCrumbs("runbooks/deploy.md", "Deploy");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["docs", "Runbooks", "Deploy"]);
    expect(container.querySelector('a[href="/docs"]')?.textContent).toBe("docs");
  });

  it("single root index landing: the trail collapses to `docs / Runbooks`", () => {
    const { container } = renderSoloCrumbs("runbooks/index.md", "Runbooks");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["docs", "Runbooks"]);
    expect(container.querySelector('a[href="/docs"]')?.textContent).toBe("docs");
    // No ancestor crumb links to the page being viewed.
    expect(container.querySelector('a[href="/docs/runbooks"]')).toBeNull();
    expect(container.querySelector('[aria-current="page"]')?.textContent).toBe("Runbooks");
  });

  it("a FAILED tree says so - the inert trail is not left to look like it is still loading", async () => {
    // The bug: the component read `data?.roots`, so a dead fetch and a pending one rendered IDENTICALLY -
    // a trail that will never link, with nothing to say it is not still coming. The degraded trail is right
    // either way (the root COUNT is still unknown), so what the failure owes the reader is the news itself.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ error: { code: "internal", message: "boom" } }), { status: 500 })),
    );
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <Breadcrumbs root="extra" path="runbooks/deploy.md" title="Extra Deploy" />
      </QueryClientProvider>,
    );
    await waitFor(() => expect(container.querySelector("[data-pb-breadcrumbs-error]")).not.toBeNull());
    // The trail itself still renders (the page is perfectly readable) and still links nowhere.
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["extra", "runbooks", "Extra Deploy"]);
    expect(container.querySelectorAll("a")).toHaveLength(0);
    // A settled failure is NOT busy - the one bit that tells the two states apart.
    expect(container.querySelector("[data-pb-breadcrumbs]")?.getAttribute("aria-busy")).toBe("false");
  });

  it("while the tree is still loading the root crumb is inert", () => {
    // The server-issued root URL is unknown until the tree arrives, so the label renders without a guessed
    // link. This keeps an extra-root reader in the same tree while navigation is pending.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <Breadcrumbs root="extra" path="runbooks/deploy.md" title="Extra Deploy" />
      </QueryClientProvider>,
    );
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["extra", "runbooks", "Extra Deploy"]);
    expect(container.querySelector('a[href="/docs"]')).toBeNull();
    // No crumb links anywhere in this window: the ancestor has no server-issued url yet either.
    expect(container.querySelectorAll("a")).toHaveLength(0);
    // PENDING, not failed: announced as busy, and it does NOT claim a failure that has not happened.
    expect(container.querySelector("[data-pb-breadcrumbs]")?.getAttribute("aria-busy")).toBe("true");
    expect(container.querySelector("[data-pb-breadcrumbs-error]")).toBeNull();
  });
});
