import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
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
    const soloTree: TreeResponse = { roots: [tree.roots[0]] };
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(treeQuery.queryKey, soloTree);
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <Breadcrumbs root="main" path="runbooks/deploy.md" title="Deploy" />
      </QueryClientProvider>,
    );
    const items = [...container.querySelectorAll("li")].filter((li) => li.textContent?.trim() !== "/");
    expect(items.map((li) => li.textContent)).toEqual(["docs", "Runbooks", "Deploy"]);
    expect(container.querySelector('a[href="/docs"]')?.textContent).toBe("docs");
    expect(container.querySelector('a[href="/docs/main"]')).toBeNull();
  });

  it("falls back to the `docs` crumb while the tree is still loading - never a flash of the root's name", () => {
    // The root COUNT is not known until the tree arrives, so the loading state renders what a single-root
    // install renders, which is also exactly what shipped before multi-root. `/docs` is always a valid
    // link (it legacy-redirects to main), so this is a safe default rather than a guess.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <Breadcrumbs root="extra" path="runbooks/deploy.md" title="Extra Deploy" />
      </QueryClientProvider>,
    );
    expect(container.querySelector('a[href="/docs"]')?.textContent).toBe("docs");
    expect(container.textContent).not.toContain("extra");
  });
});
