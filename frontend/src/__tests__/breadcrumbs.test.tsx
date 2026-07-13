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
      root: "main",
      available: true,
      editable: true,
      tree: {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/docs/main",
        page_count: 0,
        children: [
          {
            type: "folder",
            name: "runbooks",
            title: null,
            description: null,
            path: "runbooks",
            url: "/docs/main/runbooks",
            page_count: 2,
            children: [
              { type: "page", id: "id-index", title: "Runbooks", slug: "index", path: "runbooks/index.md", url: "/docs/main/runbooks/index", status: "active", updated: null },
              { type: "page", id: "id-deploy", title: "Deploy", slug: "deploy", path: "runbooks/deploy.md", url: "/docs/main/runbooks/deploy", status: "active", updated: null },
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
      tree: {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/docs/extra",
        page_count: 0,
        children: [
          {
            type: "folder",
            name: "runbooks",
            title: "Extra Runbooks",
            description: null,
            path: "runbooks",
            url: "/docs/extra/runbooks",
            page_count: 1,
            children: [
              { type: "page", id: "id-extra", title: "Extra Deploy", slug: "deploy", path: "runbooks/deploy.md", url: "/docs/extra/runbooks/deploy", status: "active", updated: null },
            ],
          },
        ],
      },
    },
  ],
};

function renderCrumbs(path: string, title: string, root = "main") {
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
      <Breadcrumbs root="main" path={path} title={title} />
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("Breadcrumbs", () => {
  it("drops the redundant ancestor crumb on a section's own index landing", () => {
    // The index page renders AS the /docs/main/runbooks landing; folderTitle(runbooks) === the index title,
    // so the parent crumb would self-link to the very page shown. The trail collapses to `main / Runbooks`.
    const { container } = renderCrumbs("runbooks/index.md", "Runbooks");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["main", "Runbooks"]);
    // No ancestor crumb links to the page being viewed.
    expect(container.querySelector('a[href="/docs/main/runbooks"]')).toBeNull();
    // The leaf is the non-link current crumb.
    const current = container.querySelector('[aria-current="page"]')!;
    expect(current.textContent).toBe("Runbooks");
  });

  it("keeps the ancestor crumb for a normal page under a folder", () => {
    const { container } = renderCrumbs("runbooks/deploy.md", "Deploy");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["main", "Runbooks", "Deploy"]);
    // The ancestor links to its folder landing.
    expect(container.querySelector('a[href="/docs/main/runbooks"]')?.textContent).toBe("Runbooks");
  });

  it("scopes the folder lookup BY root: the same relative path in another root gets ITS titles and urls", () => {
    // Both roots hold `runbooks/`; the extra-root page must crumb through the EXTRA entry's folder
    // (title + url), never main's - the root prop is the scope, the path alone is ambiguous.
    const { container } = renderCrumbs("runbooks/deploy.md", "Extra Deploy", "extra");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["extra", "Extra Runbooks", "Extra Deploy"]);
    expect(container.querySelector('a[href="/docs/extra/runbooks"]')?.textContent).toBe("Extra Runbooks");
    expect(container.querySelector('a[href="/docs/main/runbooks"]')).toBeNull();
  });

  it("roots the trail in THIS page's root, linking to that root's own url - never back to /docs", () => {
    // The crumb was hardcoded `{ label: "docs", url: "/docs" }`. On an extra root that named the wrong
    // tree AND, because `/docs` legacy-redirects to main, quietly walked the reader into a DIFFERENT
    // root's copy of the docs. The negative assertion is the load-bearing one.
    const { container } = renderCrumbs("runbooks/deploy.md", "Extra Deploy", "extra");
    const crumb = container.querySelector("a")!;
    expect(crumb.textContent).toBe("extra");
    // The url is the entry's SERVER-ISSUED root url, consumed verbatim (never `/docs/${root}` re-derived).
    expect(crumb.getAttribute("href")).toBe("/docs/extra");
    expect(container.querySelector('a[href="/docs"]')).toBeNull();
    expect(container.textContent).not.toContain("docs /");
  });

  it("names the root ONLY when there is more than one: a SINGLE-root install keeps the `docs` crumb", () => {
    // The rule the sidebar section headers and the search root badges already follow (`roots.length > 1`),
    // and the breadcrumb follows it for the same reason: with the one root every legacy install has,
    // "main" is an internal name leaking into the UI where a meaningful word used to be. Naming it
    // unconditionally would be a UX regression for essentially every current user, shipped as a side
    // effect of a multi-root refactor.
    const { container } = renderSoloCrumbs("runbooks/deploy.md", "Deploy");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["docs", "Runbooks", "Deploy"]);
    expect(container.querySelector('a[href="/docs"]')?.textContent).toBe("docs");
    expect(container.querySelector('a[href="/docs/main"]')).toBeNull();
  });

  it("SINGLE root × index landing: the trail collapses to `docs / Runbooks`", () => {
    // The combination every existing single-root install actually runs, and it was pinned by NOTHING: the
    // collapse test above runs against the MULTI-root fixture (so it asserts `main / Runbooks`), and the
    // single-root test covers only a normal page. Either half can regress without the other noticing.
    const { container } = renderSoloCrumbs("runbooks/index.md", "Runbooks");
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["docs", "Runbooks"]);
    expect(container.querySelector('a[href="/docs"]')?.textContent).toBe("docs");
    // No ancestor crumb links to the page being viewed.
    expect(container.querySelector('a[href="/docs/main/runbooks"]')).toBeNull();
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
    expect(items.map((li) => li.textContent)).toEqual(["docs", "runbooks", "Extra Deploy"]);
    expect(container.querySelectorAll("a")).toHaveLength(0);
    // A settled failure is NOT busy - the one bit that tells the two states apart.
    expect(container.querySelector("[data-pb-breadcrumbs]")?.getAttribute("aria-busy")).toBe("false");
  });

  it("while the tree is still loading the `docs` crumb is INERT - a link there could walk the reader into main", () => {
    // The root COUNT is unknown until the tree arrives, and the crumb's two forms disagree about where it
    // POINTS: on a single-root install `/docs` is this page's own tree, and on a multi-root one `/docs`
    // resolves to MAIN. So a linked `docs` crumb rendered in this window takes a reader who is on an EXTRA
    // root's page and, on click, walks them out of the tree they are reading and into a different one.
    // The label still renders (the trail must not reflow); it simply does not link anywhere until we know.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <Breadcrumbs root="extra" path="runbooks/deploy.md" title="Extra Deploy" />
      </QueryClientProvider>,
    );
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["docs", "runbooks", "Extra Deploy"]);
    expect(container.querySelector('a[href="/docs"]')).toBeNull();
    // No crumb links anywhere in this window: the ancestor has no server-issued url yet either.
    expect(container.querySelectorAll("a")).toHaveLength(0);
    // PENDING, not failed: announced as busy, and it does NOT claim a failure that has not happened.
    expect(container.querySelector("[data-pb-breadcrumbs]")?.getAttribute("aria-busy")).toBe("true");
    expect(container.querySelector("[data-pb-breadcrumbs-error]")).toBeNull();
  });
});
