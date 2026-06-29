import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { treeQuery } from "../api/queries";
import type { TreeResponse } from "../api/types";
import { Breadcrumbs } from "../components/Breadcrumbs";

/**
 * Breadcrumb trail (ADR-0003): ancestor crumbs link to their folder landing views, the leaf is the
 * current page. A section's own index landing must NOT read `docs / <Title> / <Title>` — the parent
 * crumb and the leaf collapse to one (Phase 5.5 REPLACE landings).
 */

const tree: TreeResponse = {
  root: {
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
};

function renderCrumbs(path: string, title: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, tree);
  return render(
    <QueryClientProvider client={queryClient}>
      <Breadcrumbs path={path} title={title} />
    </QueryClientProvider>,
  );
}

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
});
