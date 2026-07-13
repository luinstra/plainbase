import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { fireEvent, render, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { sessionQuery, treeQuery } from "../api/queries";
import type { RootTree, TreeFolder } from "../api/types";
import { ROOT_UNAVAILABLE } from "../components/ErrorView";
import { SidebarNav } from "../components/Sidebar";
import { createAppRouter } from "../router";

/**
 * Stable-selector guard (§5.9): `.pb-sidebar` + `data-pb-*` are a public customization
 * API. The snapshot pins the emitted markup so refactors can't silently break user CSS.
 */

const tree: TreeFolder = {
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
      name: "guides",
      title: "Guides",
      description: null,
      path: "guides",
      url: "/docs/main/guides",
      page_count: 1,
      children: [
        {
          type: "page",
          id: "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a",
          title: "Deploy Guide",
          slug: "deploy-guide",
          path: "guides/deploy-guide.md",
          url: "/docs/main/guides/deploy-guide",
          status: "active",
          updated: null,
        },
      ],
    },
    {
      // A collision-loser FOLDER: url null → inert label, subtree still listed.
      type: "folder",
      name: "shadowed-folder",
      title: null,
      description: null,
      path: "shadowed-folder",
      url: null,
      page_count: 1,
      children: [
        {
          // A path-space collision loser: url null → the link must fall back to /p/{id}.
          type: "page",
          id: "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99",
          title: "Shadowed Page",
          slug: "shadowed",
          path: "shadowed-folder/shadowed.md",
          url: null,
          status: "active",
          updated: null,
        },
      ],
    },
  ],
};

describe("SidebarNav", () => {
  it("emits the stable selectors and links from node urls", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/main/guides/deploy-guide" />);

    // `.pb-sidebar`/`data-pb-sidebar` moved UP to the wrapper's single `<aside>` (multi-root C5) - a nav
    // per root, one aside for all of them. The count guard lives with the wrapper's tests below.
    const nav = container.querySelector("nav");
    expect(nav).not.toBeNull();
    expect(nav!.getAttribute("aria-label")).toBe("Documentation tree");
    expect(container.querySelectorAll('[data-pb-nav-item="folder"]')).toHaveLength(2);
    expect(container.querySelectorAll('[data-pb-nav-item="page"]')).toHaveLength(2);

    const canonical = container.querySelector('a[href="/docs/main/guides/deploy-guide"]');
    expect(canonical).not.toBeNull();
    expect(canonical!.getAttribute("aria-current")).toBe("page");

    // The collision loser links via its permalink.
    const loser = container.querySelector('a[href="/p/0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99"]');
    expect(loser).not.toBeNull();
    expect(loser!.textContent).toBe("Shadowed Page");
  });

  it("links folder labels to their landing url; a loser folder keeps an inert label", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/main/guides" />);

    const folderLink = container.querySelector('a[href="/docs/main/guides"]');
    expect(folderLink).not.toBeNull();
    expect(folderLink!.textContent).toBe("Guides");
    expect(folderLink!.getAttribute("aria-current")).toBe("page"); // the landing view is active

    // The loser folder (url null) renders its label as text, not a link.
    const loserItem = [...container.querySelectorAll('[data-pb-nav-item="folder"]')].find((li) =>
      li.textContent?.includes("shadowed-folder"),
    );
    expect(loserItem!.querySelector("a")?.textContent).not.toBe("shadowed-folder");
  });

  it("toggles a folder's children via the disclosure button, independent of the label link", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/main/guides/deploy-guide" />);

    const toggle = container.querySelector('[data-pb-nav-item="folder"] [data-pb-folder-toggle]')!;
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(container.querySelector('a[href="/docs/main/guides/deploy-guide"]')).not.toBeNull();

    fireEvent.click(toggle);
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(container.querySelector('a[href="/docs/main/guides/deploy-guide"]')).toBeNull(); // collapsed
    expect(container.querySelector('a[href="/docs/main/guides"]')).not.toBeNull(); // the label link survives

    fireEvent.click(toggle);
    expect(container.querySelector('a[href="/docs/main/guides/deploy-guide"]')).not.toBeNull();
  });

  it("marks exactly the active row with aria-current (the slash-bar/tint hook)", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/main/guides/deploy-guide" />);
    const active = container.querySelectorAll('[aria-current="page"]');
    expect(active).toHaveLength(1);
    expect(active[0].getAttribute("href")).toBe("/docs/main/guides/deploy-guide");
    expect(active[0].className).not.toContain("bg-active"); // tint now comes from the .pb-* rule
  });

  it("renders the caret as an empty host, with no text glyph", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/main/guides/deploy-guide" />);
    expect(container.textContent).not.toMatch(/[▾▸]/);
    expect(container.querySelectorAll(".pb-folder-caret").length).toBeGreaterThan(0);
  });

  it("surfaces the root landing as a home link to the folder URL at the TOP, not the page's bare URL", () => {
    const withIndex: TreeFolder = {
      type: "folder",
      name: "",
      title: null,
      description: null,
      path: "",
      url: "/docs/main",
      page_count: 0,
      children: [
        { type: "folder", name: "guides", title: "Guides", description: null, path: "guides", url: "/docs/main/guides", page_count: 0, children: [] },
        { type: "page", id: "id-zeta", title: "Zeta", slug: "zeta", path: "zeta.md", url: "/docs/main/zeta", status: "active", updated: null },
        // index.md is LAST in tree order but is the root's landing — it surfaces first, as the home link.
        { type: "page", id: "id-home", title: "Home", slug: "index", path: "index.md", url: "/docs/main/index", status: "active", updated: null },
      ],
    };
    const { container } = render(<SidebarNav root={withIndex} currentPathname="/docs/main" />);
    const first = container.querySelector("nav [data-pb-nav-item]")!;
    expect(first.getAttribute("data-pb-nav-item")).toBe("page");
    expect(first.textContent).toContain("Home");
    // It points at the FOLDER url (one canonical path - /docs/main since C3), active on the bare-root landing…
    const home = first.querySelector("a")!;
    expect(home.getAttribute("href")).toBe("/docs/main");
    expect(home.getAttribute("aria-current")).toBe("page");
    // …and the index page is never ALSO listed at its own bare url.
    expect(container.querySelector('a[href="/docs/main/index"]')).toBeNull();
  });

  it("labels a _folder.yaml-less folder with its index child's frontmatter title, not the raw dir name", () => {
    // A created section dir has no _folder.yaml (title null); its human label is the index page's title.
    const indexTitled: TreeFolder = {
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
          page_count: 1,
          children: [
            { type: "page", id: "id-rb-index", title: "Runbooks", slug: "index", path: "runbooks/index.md", url: "/docs/main/runbooks/index", status: "active", updated: null },
          ],
        },
      ],
    };
    const { container } = render(<SidebarNav root={indexTitled} currentPathname="/docs" />);
    const folderLink = container.querySelector('a[href="/docs/main/runbooks"]')!;
    expect(folderLink).not.toBeNull();
    expect(folderLink.textContent).toBe("Runbooks"); // the index title, NOT "runbooks"
    // The folder's index child is surfaced ONLY by the folder row (its one canonical path) — it is
    // NOT also listed as a child page row at its own bare url.
    expect(container.querySelector('a[href="/docs/main/runbooks/index"]')).toBeNull();
    expect(container.querySelectorAll('[data-pb-nav-item="page"]')).toHaveLength(0);
  });

  it("gives an index-only folder no expand affordance (the disclosure button is disabled, no empty list)", () => {
    // A `url`-folder whose ONLY child is its landing surfaces that child through the label link, so
    // `nonLandingChildren` leaves nothing to disclose — the chevron must be disabled, not a click that
    // expands to an empty list.
    const indexOnly: TreeFolder = {
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
          page_count: 1,
          children: [
            { type: "page", id: "id-rb-index", title: "Runbooks", slug: "index", path: "runbooks/index.md", url: "/docs/main/runbooks/index", status: "active", updated: null },
          ],
        },
      ],
    };
    const { container } = render(<SidebarNav root={indexOnly} currentPathname="/docs" />);
    const toggle = container.querySelector<HTMLButtonElement>('[data-pb-nav-item="folder"] [data-pb-folder-toggle]')!;
    expect(toggle.disabled).toBe(true);
    expect(toggle.getAttribute("aria-expanded")).toBeNull(); // nothing to expand → no expanded state
    // Clicking the inert chevron reveals no child list (the landing is reached via the folder label only).
    fireEvent.click(toggle);
    expect(container.querySelector('[data-pb-nav-item="page"]')).toBeNull();
  });

  it("keeps a loser folder's index child as a row (no url to surface it through the label)", () => {
    // A collision-loser folder (url null) has an inert label, so its index/README can't be reached
    // via the folder link — it must remain a child row or it's unreachable from the tree.
    const loserWithIndex: TreeFolder = {
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
          url: null,
          page_count: 1,
          children: [
            { type: "page", id: "id-loser-index", title: "Runbooks", slug: "index", path: "runbooks/index.md", url: "/p/id-loser-index", status: "active", updated: null },
          ],
        },
      ],
    };
    const { container } = render(<SidebarNav root={loserWithIndex} currentPathname="/docs" />);
    // The loser folder label is inert (no link), so the index survives as a child row at its permalink.
    expect(container.querySelector('a[href="/p/id-loser-index"]')).not.toBeNull();
    expect(container.querySelectorAll('[data-pb-nav-item="page"]')).toHaveLength(1);
  });

  it("matches the stable-markup snapshot", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/main/guides/deploy-guide" />);
    expect(container.firstChild).toMatchSnapshot();
  });
});

/**
 * The tree-fed WRAPPER - the seam every test above misses, because they all render `SidebarNav`
 * directly. The wrapper is where the roots are mapped, so it is where a multi-root install first
 * goes wrong: it once mapped each root to its OWN full-width `<aside>`, and N of those squeeze the
 * document off-screen. These tests render it through the real Shell for that reason.
 */
function entryTree(root: string, pageId: string, title: string): TreeFolder {
  return {
    type: "folder",
    name: "",
    title: null,
    description: null,
    path: "",
    url: `/docs/${root}`,
    page_count: 1,
    children: [{ type: "page", id: pageId, title, slug: "intro", path: "intro.md", url: `/docs/${root}/intro`, status: "active", updated: null }],
  };
}

const MAIN: RootTree = { root: "main", available: true, tree: entryTree("main", "id-main-intro", "Main Intro") };
const EXTRA: RootTree = { root: "extra", available: true, tree: entryTree("extra", "id-extra-intro", "Extra Intro") };
/** What a DOWN root actually ships: listed (it is configured) with an EMPTY subtree - see tree.ts FolderEntry. */
const DOWN: RootTree = {
  root: "handbook",
  available: false,
  tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs/handbook", page_count: 0, children: [] },
};

function renderShell(roots: RootTree[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, { roots });
  queryClient.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: ["/docs"] }));
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe("Sidebar (the tree-fed parent)", () => {
  it("renders ONE aside for N roots, with a section per entry in wire order", async () => {
    const { container } = renderShell([MAIN, EXTRA]);

    // DELIBERATELY INVERTED (multi-root C5). This assertion used to read `toHaveLength(2)` and pass:
    // it pinned the very bug it should have caught - one full-width `<aside>` PER root, so two roots
    // shoved the document off-screen. The sidebar is ONE aside; the roots are SECTIONS inside it.
    // Do not "restore" the 2.
    await waitFor(() => expect(container.querySelectorAll("[data-pb-sidebar]")).toHaveLength(1));
    expect(container.querySelectorAll(".pb-sidebar")).toHaveLength(1);

    const sections = [...container.querySelectorAll("[data-pb-root-section]")];
    expect(sections.map((section) => section.getAttribute("data-pb-root-section"))).toEqual(["main", "extra"]);
    const [main, extra] = sections;
    expect(main.querySelector('a[href="/docs/main/intro"]')).not.toBeNull();
    expect(extra.querySelector('a[href="/docs/extra/intro"]')).not.toBeNull();
  });

  it("labels each section when there are 2+ roots, and labels nothing when there is one", async () => {
    const many = renderShell([MAIN, EXTRA]);
    await waitFor(() => expect(many.container.querySelectorAll("[data-pb-root-label]")).toHaveLength(2));
    const labels = [...many.container.querySelectorAll("[data-pb-root-label]")];
    expect(labels.map((label) => label.textContent)).toEqual(["main", "extra"]);

    // One root (every legacy install): a header reading "main" is pure noise, so there is none.
    const one = renderShell([MAIN]);
    await waitFor(() => expect(one.container.querySelector("[data-pb-root-section]")).not.toBeNull());
    expect(one.container.querySelectorAll("[data-pb-root-label]")).toHaveLength(0);
  });

  it("shows a DOWN root's section as an outage, never as an empty tree", async () => {
    const { container } = renderShell([MAIN, DOWN]);

    await waitFor(() => expect(container.querySelectorAll("[data-pb-root-section]")).toHaveLength(2));
    const down = container.querySelector('[data-pb-root-section="handbook"]')!;
    const notice = down.querySelector("[data-pb-root-section-unavailable]")!;
    expect(notice).not.toBeNull();
    // Asserted against the SHARED copy, so the compact notice and the full-page view cannot drift apart.
    expect(notice.textContent).toContain(ROOT_UNAVAILABLE.eyebrow);
    expect(notice.textContent).toContain(ROOT_UNAVAILABLE.headline("handbook"));
    expect(notice.textContent).toContain(ROOT_UNAVAILABLE.body);
    // The server empties a down root's subtree; rendering those (absent) children would say "this root
    // has no pages" - i.e. "my docs are gone" - over what is an outage.
    expect(down.querySelector("nav")).toBeNull();
    expect(down.querySelector("[data-pb-nav-item]")).toBeNull();
    // The serving root next to it is untouched.
    expect(container.querySelector('[data-pb-root-section="main"] a[href="/docs/main/intro"]')).not.toBeNull();
  });
});
