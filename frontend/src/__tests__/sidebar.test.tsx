import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { sessionQuery, treeQuery } from "../api/queries";
import type { RootTree, TreeFolder } from "../api/types";
import { ROOT_UNAVAILABLE } from "../components/ErrorView";
import { SidebarNav } from "../components/Sidebar";
import { writeSidebarPreferences } from "../lib/sidebarPreferences";
import { createAppRouter } from "../router";

afterEach(() => {
  sessionStorage.clear();
});

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
  url: "/docs",
  page_count: 0,
  children: [
    {
      type: "folder",
      name: "guides",
      title: "Guides",
      description: null,
      path: "guides",
      url: "/docs/guides",
      page_count: 1,
      children: [
        {
          type: "page",
          id: "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a",
          title: "Deploy Guide",
          slug: "deploy-guide",
          path: "guides/deploy-guide.md",
          url: "/docs/guides/deploy-guide",
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
          // A path-space collision loser: url null → the link must fall back to the ROOTED /p/{root}/{id}.
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
    const { container } = render(
      <SidebarNav
        tree={tree}
        root="docs"
        currentPathname="/docs/guides/deploy-guide"
        initialOpenFolders={["shadowed-folder"]}
      />,
    );

    // `.pb-sidebar`/`data-pb-sidebar` moved UP to the wrapper's single `<aside>` (multi-root C5) - a nav
    // per root, one aside for all of them. The count guard lives with the wrapper's tests below.
    const nav = container.querySelector("nav");
    expect(nav).not.toBeNull();
    expect(nav!.getAttribute("aria-label")).toBe("Documentation tree");
    expect(container.querySelectorAll('[data-pb-nav-item="folder"]')).toHaveLength(2);
    expect(container.querySelectorAll('[data-pb-nav-item="page"]')).toHaveLength(2);

    const canonical = container.querySelector('a[href="/docs/guides/deploy-guide"]');
    expect(canonical).not.toBeNull();
    expect(canonical!.getAttribute("aria-current")).toBe("page");

    // The collision loser links via its ROOTED permalink: a bare one answers 300 once the id is held
    // by more than one root, and this row is reached through the loser FOLDER, so it also proves the
    // root survives the nested recursion.
    const loser = container.querySelector('a[href="/p/docs/0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99"]');
    expect(loser).not.toBeNull();
    expect(loser!.textContent).toBe("Shadowed Page");
    expect(container.querySelector('a[href="/p/0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99"]')).toBeNull();
  });

  it("links folder labels to their landing url; a loser folder keeps an inert label", () => {
    const { container } = render(<SidebarNav tree={tree} root="docs" currentPathname="/docs/guides" />);

    const folderLink = container.querySelector('a[href="/docs/guides"]');
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
    const { container } = render(<SidebarNav tree={tree} root="docs" currentPathname="/docs/guides/deploy-guide" />);

    const toggle = container.querySelector('[data-pb-nav-item="folder"] [data-pb-folder-toggle]')!;
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(container.querySelector('a[href="/docs/guides/deploy-guide"]')).not.toBeNull();

    fireEvent.click(toggle);
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(container.querySelector('a[href="/docs/guides/deploy-guide"]')).toBeNull(); // collapsed
    expect(container.querySelector('a[href="/docs/guides"]')).not.toBeNull(); // the label link survives

    fireEvent.click(toggle);
    expect(container.querySelector('a[href="/docs/guides/deploy-guide"]')).not.toBeNull();
  });

  it("opens the active page's ancestors and leaves unrelated folders collapsed", () => {
    const { container } = render(<SidebarNav tree={tree} root="docs" currentPathname="/docs/guides/deploy-guide" />);
    const toggles = container.querySelectorAll("[data-pb-folder-toggle]");

    expect(toggles[0].getAttribute("aria-expanded")).toBe("true");
    expect(toggles[1].getAttribute("aria-expanded")).toBe("false");
    expect(container.querySelector('a[href="/docs/guides/deploy-guide"]')).not.toBeNull();
    expect(container.querySelector('a[href="/p/docs/0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99"]')).toBeNull();
  });

  it("opens a newly active ancestor after client-side navigation", async () => {
    const { container, rerender } = render(<SidebarNav tree={tree} root="docs" currentPathname="/docs" />);
    const guidesToggle = container.querySelector<HTMLButtonElement>("[data-pb-folder-toggle]")!;
    expect(guidesToggle.getAttribute("aria-expanded")).toBe("false");

    rerender(<SidebarNav tree={tree} root="docs" currentPathname="/docs/guides/deploy-guide" />);
    await waitFor(() => expect(guidesToggle.getAttribute("aria-expanded")).toBe("true"));
  });

  it("opens the ancestor of an active collision-loser permalink", () => {
    const { container } = render(
      <SidebarNav
        tree={tree}
        root="docs"
        currentPathname="/p/docs/0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99"
      />,
    );
    const toggles = container.querySelectorAll("[data-pb-folder-toggle]");

    expect(toggles[0].getAttribute("aria-expanded")).toBe("false");
    expect(toggles[1].getAttribute("aria-expanded")).toBe("true");
    expect(container.querySelector('a[href="/p/docs/0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99"]')).not.toBeNull();
  });

  it("marks exactly the active row with aria-current (the persistent tint hook)", () => {
    const { container } = render(<SidebarNav tree={tree} root="docs" currentPathname="/docs/guides/deploy-guide" />);
    const active = container.querySelectorAll('[aria-current="page"]');
    expect(active).toHaveLength(1);
    expect(active[0].getAttribute("href")).toBe("/docs/guides/deploy-guide");
    expect(active[0].className).not.toContain("bg-active"); // tint now comes from the .pb-* rule
  });

  it("renders the caret as an empty host, with no text glyph", () => {
    const { container } = render(<SidebarNav tree={tree} root="docs" currentPathname="/docs/guides/deploy-guide" />);
    expect(container.textContent).not.toMatch(/[▾▸]/);
    expect(container.querySelectorAll(".pb-folder-caret").length).toBeGreaterThan(0);
  });

  it("groups folders before pages while preserving the server wire order within each group", () => {
    const mixed: TreeFolder = {
      type: "folder",
      name: "",
      title: null,
      description: null,
      path: "",
      url: "/docs",
      page_count: 2,
      children: [
        { type: "page", id: "id-zulu-page", title: "Zulu page", slug: "zulu", path: "zulu.md", url: "/docs/zulu", status: "active", updated: null },
        { type: "folder", name: "guides", title: "Guides", description: null, path: "guides", url: "/docs/guides", page_count: 0, children: [] },
        { type: "page", id: "id-alpha-page", title: "Alpha page", slug: "alpha", path: "alpha.md", url: "/docs/alpha", status: "active", updated: null },
        { type: "folder", name: "api", title: "API", description: null, path: "api", url: "/docs/api", page_count: 0, children: [] },
      ],
    };
    const { container } = render(<SidebarNav tree={mixed} root="docs" currentPathname="/docs" />);
    const rows = [...container.querySelector("nav > ul")!.children];

    expect(rows.map((row) => row.getAttribute("data-pb-nav-item"))).toEqual(["folder", "folder", "page", "page"]);
    expect(rows.map((row) => row.textContent?.trim())).toEqual(["Guides", "API", "Zulu page", "Alpha page"]);
    expect(container.querySelector("[data-pb-tree-kind]")).toBeNull();
    expect(container.querySelector('a[href="/docs/alpha"]')?.className).toContain("text-ink");
    expect(container.querySelector('a[href="/docs/alpha"]')?.className).not.toContain("text-muted");
  });

  it("surfaces the root landing as a home link to the folder URL at the TOP, not the page's bare URL", () => {
    const withIndex: TreeFolder = {
      type: "folder",
      name: "",
      title: null,
      description: null,
      path: "",
      url: "/docs",
      page_count: 0,
      children: [
        { type: "folder", name: "guides", title: "Guides", description: null, path: "guides", url: "/docs/guides", page_count: 0, children: [] },
        { type: "page", id: "id-zeta", title: "Zeta", slug: "zeta", path: "zeta.md", url: "/docs/zeta", status: "active", updated: null },
        // index.md is LAST in tree order but is the root's landing — it surfaces first, as the home link.
        { type: "page", id: "id-home", title: "Home", slug: "index", path: "index.md", url: "/docs/index", status: "active", updated: null },
      ],
    };
    const { container } = render(<SidebarNav tree={withIndex} root="docs" currentPathname="/docs" />);
    const first = container.querySelector("nav [data-pb-nav-item]")!;
    expect(first.getAttribute("data-pb-nav-item")).toBe("page");
    expect(first.textContent).toContain("Home");
    // It points at the FOLDER url (one canonical path - /docs since C3), active on the bare-root landing…
    const home = first.querySelector("a")!;
    expect(home.getAttribute("href")).toBe("/docs");
    expect(home.getAttribute("aria-current")).toBe("page");
    // …and the index page is never ALSO listed at its own bare url.
    expect(container.querySelector('a[href="/docs/index"]')).toBeNull();
  });

  it("labels a _folder.yaml-less folder with its index child's frontmatter title, not the raw dir name", () => {
    // A created section dir has no _folder.yaml (title null); its human label is the index page's title.
    const indexTitled: TreeFolder = {
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
          page_count: 1,
          children: [
            { type: "page", id: "id-rb-index", title: "Runbooks", slug: "index", path: "runbooks/index.md", url: "/docs/runbooks/index", status: "active", updated: null },
          ],
        },
      ],
    };
    const { container } = render(<SidebarNav tree={indexTitled} root="docs" currentPathname="/docs" />);
    const folderLink = container.querySelector('a[href="/docs/runbooks"]')!;
    expect(folderLink).not.toBeNull();
    expect(folderLink.textContent).toBe("Runbooks"); // the index title, NOT "runbooks"
    // The folder's index child is surfaced ONLY by the folder row (its one canonical path) — it is
    // NOT also listed as a child page row at its own bare url.
    expect(container.querySelector('a[href="/docs/runbooks/index"]')).toBeNull();
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
          page_count: 1,
          children: [
            { type: "page", id: "id-rb-index", title: "Runbooks", slug: "index", path: "runbooks/index.md", url: "/docs/runbooks/index", status: "active", updated: null },
          ],
        },
      ],
    };
    const { container } = render(<SidebarNav tree={indexOnly} root="docs" currentPathname="/docs" />);
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
      url: "/docs",
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
            { type: "page", id: "id-loser-index", title: "Runbooks", slug: "index", path: "runbooks/index.md", url: "/p/docs/id-loser-index", status: "active", updated: null },
          ],
        },
      ],
    };
    const { container } = render(
      <SidebarNav tree={loserWithIndex} root="docs" currentPathname="/docs" initialOpenFolders={["runbooks"]} />,
    );
    // The loser folder label is inert (no link), so the index survives as a child row at its permalink.
    expect(container.querySelector('a[href="/p/docs/id-loser-index"]')).not.toBeNull();
    expect(container.querySelectorAll('[data-pb-nav-item="page"]')).toHaveLength(1);
  });

  it("matches the stable-markup snapshot", () => {
    const { container } = render(
      <SidebarNav
        tree={tree}
        root="docs"
        currentPathname="/docs/guides/deploy-guide"
        initialOpenFolders={["shadowed-folder"]}
      />,
    );
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
  const rootUrl = root === "docs" ? "/docs" : `/${root}`;
  return {
    type: "folder",
    name: "",
    title: null,
    description: null,
    path: "",
    url: rootUrl,
    page_count: 1,
    children: [{ type: "page", id: pageId, title, slug: "intro", path: "intro.md", url: `${rootUrl}/intro`, status: "active", updated: null }],
  };
}

const DOCS: RootTree = { root: "docs", available: true, editable: true, primary: true, tree: entryTree("docs", "id-main-intro", "Main Intro") };
const EXTRA: RootTree = { root: "extra", available: true, editable: true, primary: false, tree: entryTree("extra", "id-extra-intro", "Extra Intro") };
function entryWithFolder(root: string, primary: boolean): RootTree {
  const rootUrl = `/${root}`;
  return {
    root,
    available: true,
    editable: true,
    primary,
    tree: {
      type: "folder",
      name: "",
      title: null,
      description: null,
      path: "",
      url: rootUrl,
      page_count: 0,
      children: [
        {
          type: "folder",
          name: "notes",
          title: "Notes",
          description: null,
          path: "notes",
          url: `${rootUrl}/notes`,
          page_count: 1,
          children: [
            {
              type: "page",
              id: `id-${root}-note`,
              title: `${root} note`,
              slug: "one",
              path: "notes/one.md",
              url: `${rootUrl}/notes/one`,
              status: "active",
              updated: null,
            },
          ],
        },
      ],
    },
  };
}

/** What a DOWN root actually ships: listed (it is configured) with an EMPTY subtree - see tree.ts FolderEntry. */
const DOWN: RootTree = {
  root: "handbook",
  available: false,
  editable: true,
  primary: false,
  tree: { type: "folder", name: "", title: null, description: null, path: "", url: "/handbook", page_count: 0, children: [] },
};

function renderShell(roots: RootTree[], initialEntry = "/docs") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, { roots });
  queryClient.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [initialEntry] }));
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe("Sidebar (the tree-fed parent)", () => {
  it("renders one selector and only the route-owned root's tree when multiple roots exist", async () => {
    const { container } = renderShell([DOCS, EXTRA]);

    await waitFor(() => expect(container.querySelectorAll("[data-pb-sidebar]")).toHaveLength(1));
    expect(container.querySelectorAll(".pb-sidebar")).toHaveLength(1);

    const selector = container.querySelector<HTMLButtonElement>("[data-pb-root-selector]");
    expect(selector).not.toBeNull();
    expect(selector!.tagName).toBe("BUTTON");
    expect(selector!.getAttribute("data-pb-selected-root")).toBe("docs");
    expect(selector!.getAttribute("aria-expanded")).toBe("false");

    fireEvent.click(selector!);
    const options = [...container.querySelectorAll<HTMLButtonElement>("[data-pb-root-option]")];
    expect(container.querySelector('[role="listbox"]')).not.toBeNull();
    expect(options.map((option) => option.getAttribute("data-pb-root-option"))).toEqual(["docs", "extra"]);
    expect(options.map((option) => option.tagName)).toEqual(["BUTTON", "BUTTON"]);
    expect(options[0].getAttribute("aria-selected")).toBe("true");

    const sections = [...container.querySelectorAll("[data-pb-root-section]")];
    expect(sections.map((section) => section.getAttribute("data-pb-root-section"))).toEqual(["docs"]);
    expect(sections[0].querySelector('a[href="/docs/intro"]')).not.toBeNull();
    expect(container.querySelector('a[href="/extra/intro"]')).toBeNull();
  });

  it("opens the themed root listbox from the keyboard, moves by arrow, and restores focus on Escape", async () => {
    const { container } = renderShell([DOCS, EXTRA]);
    const selector = await waitFor(() => {
      const element = container.querySelector<HTMLButtonElement>("[data-pb-root-selector]");
      expect(element).not.toBeNull();
      return element!;
    });

    selector.focus();
    fireEvent.keyDown(selector, { key: "ArrowDown" });
    const docs = container.querySelector<HTMLButtonElement>('[data-pb-root-option="docs"]')!;
    const extra = container.querySelector<HTMLButtonElement>('[data-pb-root-option="extra"]')!;
    await waitFor(() => expect(document.activeElement).toBe(docs));
    expect(docs.tabIndex).toBe(0);
    expect(extra.tabIndex).toBe(-1);

    fireEvent.keyDown(docs, { key: "ArrowDown" });
    await waitFor(() => expect(document.activeElement).toBe(extra));
    expect(docs.tabIndex).toBe(-1);
    expect(extra.tabIndex).toBe(0);

    fireEvent.keyDown(extra, { key: "Escape" });
    expect(container.querySelector("[data-pb-root-menu]")).toBeNull();
    expect(document.activeElement).toBe(selector);
  });

  it("confirms the active root option with Enter", async () => {
    const { container } = renderShell([DOCS, EXTRA]);
    const selector = await waitFor(() => {
      const element = container.querySelector<HTMLButtonElement>("[data-pb-root-selector]");
      expect(element).not.toBeNull();
      return element!;
    });

    fireEvent.keyDown(selector, { key: "ArrowDown" });
    const docs = container.querySelector<HTMLButtonElement>('[data-pb-root-option="docs"]')!;
    const extra = container.querySelector<HTMLButtonElement>('[data-pb-root-option="extra"]')!;
    await waitFor(() => expect(document.activeElement).toBe(docs));
    fireEvent.keyDown(docs, { key: "ArrowDown" });
    await waitFor(() => expect(document.activeElement).toBe(extra));

    fireEvent.keyDown(extra, { key: "Enter" });
    await waitFor(() => expect(selector.getAttribute("data-pb-selected-root")).toBe("extra"));
    expect(container.querySelector("[data-pb-root-menu]")).toBeNull();
    expect(container.querySelector('[data-pb-root-section="extra"]')).not.toBeNull();
  });

  it("closes an open root menu when Escape is pressed on the trigger", async () => {
    const { container } = renderShell([DOCS, EXTRA]);
    const selector = await waitFor(() => {
      const element = container.querySelector<HTMLButtonElement>("[data-pb-root-selector]");
      expect(element).not.toBeNull();
      return element!;
    });

    fireEvent.click(selector);
    await waitFor(() => expect(container.querySelector("[data-pb-root-menu]")).not.toBeNull());
    selector.focus();
    fireEvent.keyDown(selector, { key: "Escape" });

    expect(container.querySelector("[data-pb-root-menu]")).toBeNull();
    expect(selector.getAttribute("aria-expanded")).toBe("false");
    expect(document.activeElement).toBe(selector);
  });

  it("uses a valid saved root on rootless routes but lets a rooted URL override it", async () => {
    writeSidebarPreferences({ selectedRoot: "extra", openFolders: {} });

    const rootless = renderShell([DOCS, EXTRA], "/review");
    await waitFor(() =>
      expect(rootless.container.querySelector("[data-pb-root-selector]")?.getAttribute("data-pb-selected-root")).toBe("extra"),
    );
    expect(rootless.container.querySelector('[data-pb-root-section="extra"]')).not.toBeNull();
    rootless.unmount();

    const rooted = renderShell([DOCS, EXTRA], "/p/docs/0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99");
    await waitFor(() =>
      expect(rooted.container.querySelector("[data-pb-root-selector]")?.getAttribute("data-pb-selected-root")).toBe("docs"),
    );
    expect(rooted.container.querySelector('[data-pb-root-section="docs"]')).not.toBeNull();
  });

  it("falls back to the declared primary rather than wire position when a saved root is stale", async () => {
    writeSidebarPreferences({ selectedRoot: "removed", openFolders: {} });

    const { container } = renderShell([EXTRA, DOCS], "/review");
    await waitFor(() =>
      expect(container.querySelector("[data-pb-root-selector]")?.getAttribute("data-pb-selected-root")).toBe("docs"),
    );
    expect(container.querySelector('[data-pb-root-section="docs"]')).not.toBeNull();
  });

  it("restores folder expansion independently for each root", async () => {
    const { container } = renderShell([entryWithFolder("docs", true), entryWithFolder("extra", false)]);
    const selector = await waitFor(() => {
      const element = container.querySelector<HTMLButtonElement>("[data-pb-root-selector]");
      expect(element).not.toBeNull();
      return element!;
    });
    const selectRoot = (root: string) => {
      fireEvent.click(selector);
      const option = container.querySelector<HTMLButtonElement>(`[data-pb-root-option="${root}"]`);
      expect(option).not.toBeNull();
      fireEvent.click(option!);
    };

    const activeToggle = () => container.querySelector<HTMLButtonElement>("[data-pb-folder-toggle]")!;
    expect(activeToggle().getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(activeToggle());
    expect(activeToggle().getAttribute("aria-expanded")).toBe("true");

    selectRoot("extra");
    await waitFor(() => expect(container.querySelector('[data-pb-root-section="extra"]')).not.toBeNull());
    expect(activeToggle().getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(activeToggle());
    expect(activeToggle().getAttribute("aria-expanded")).toBe("true");

    selectRoot("docs");
    await waitFor(() => expect(container.querySelector('[data-pb-root-section="docs"]')).not.toBeNull());
    expect(activeToggle().getAttribute("aria-expanded")).toBe("true");
  });

  it("lists every root in the selector and omits the selector entirely for one root", async () => {
    const many = renderShell([DOCS, EXTRA]);
    const selector = await waitFor(() => {
      const element = many.container.querySelector<HTMLButtonElement>("[data-pb-root-selector]");
      expect(element).not.toBeNull();
      return element!;
    });
    fireEvent.click(selector);
    await waitFor(() => expect(many.container.querySelectorAll("[data-pb-root-label]")).toHaveLength(2));
    const labels = [...many.container.querySelectorAll("[data-pb-root-label]")];
    expect(labels.map((label) => label.textContent)).toEqual(["docs", "extra"]);

    // One root (every legacy install): a selector with one choice is pure noise, so there is none.
    const one = renderShell([DOCS]);
    await waitFor(() => expect(one.container.querySelector("[data-pb-root-section]")).not.toBeNull());
    expect(one.container.querySelector("[data-pb-root-selector]")).toBeNull();
  });

  it("shows a DOWN root's section as an outage, never as an empty tree", async () => {
    const { container } = renderShell([DOCS, DOWN]);

    const selector = await waitFor(() => {
      const element = container.querySelector<HTMLButtonElement>("[data-pb-root-selector]");
      expect(element).not.toBeNull();
      return element!;
    });
    fireEvent.click(selector);
    expect([...container.querySelectorAll("[data-pb-root-option]")].map((option) => option.textContent)).toEqual([
      "docs",
      "handbook (unavailable)",
    ]);

    fireEvent.click(container.querySelector<HTMLButtonElement>('[data-pb-root-option="handbook"]')!);
    await waitFor(() => expect(container.querySelector('[data-pb-root-section="handbook"]')).not.toBeNull());
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

    fireEvent.click(selector);
    fireEvent.click(container.querySelector<HTMLButtonElement>('[data-pb-root-option="docs"]')!);
    await waitFor(() =>
      expect(container.querySelector('[data-pb-root-section="docs"] a[href="/docs/intro"]')).not.toBeNull(),
    );
  });
});
