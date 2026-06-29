import { fireEvent, render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { TreeFolder } from "../api/types";
import { SidebarNav } from "../components/Sidebar";

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
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/guides/deploy-guide" />);

    const sidebar = container.querySelector(".pb-sidebar");
    expect(sidebar).not.toBeNull();
    expect(sidebar!.hasAttribute("data-pb-sidebar")).toBe(true);
    expect(container.querySelectorAll('[data-pb-nav-item="folder"]')).toHaveLength(2);
    expect(container.querySelectorAll('[data-pb-nav-item="page"]')).toHaveLength(2);

    const canonical = container.querySelector('a[href="/docs/guides/deploy-guide"]');
    expect(canonical).not.toBeNull();
    expect(canonical!.getAttribute("aria-current")).toBe("page");

    // The collision loser links via its permalink.
    const loser = container.querySelector('a[href="/p/0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99"]');
    expect(loser).not.toBeNull();
    expect(loser!.textContent).toBe("Shadowed Page");
  });

  it("links folder labels to their landing url; a loser folder keeps an inert label", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/guides" />);

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
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/guides/deploy-guide" />);

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

  it("marks exactly the active row with aria-current (the slash-bar/tint hook)", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/guides/deploy-guide" />);
    const active = container.querySelectorAll('[aria-current="page"]');
    expect(active).toHaveLength(1);
    expect(active[0].getAttribute("href")).toBe("/docs/guides/deploy-guide");
    expect(active[0].className).not.toContain("bg-active"); // tint now comes from the .pb-* rule
  });

  it("renders the caret as an empty host, with no text glyph", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/guides/deploy-guide" />);
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
      url: "/docs",
      page_count: 0,
      children: [
        { type: "folder", name: "guides", title: "Guides", description: null, path: "guides", url: "/docs/guides", page_count: 0, children: [] },
        { type: "page", id: "id-zeta", title: "Zeta", slug: "zeta", path: "zeta.md", url: "/docs/zeta", status: "active", updated: null },
        // index.md is LAST in tree order but is the root's landing — it surfaces first, as the home link.
        { type: "page", id: "id-home", title: "Home", slug: "index", path: "index.md", url: "/docs/index", status: "active", updated: null },
      ],
    };
    const { container } = render(<SidebarNav root={withIndex} currentPathname="/docs" />);
    const first = container.querySelector("nav [data-pb-nav-item]")!;
    expect(first.getAttribute("data-pb-nav-item")).toBe("page");
    expect(first.textContent).toContain("Home");
    // It points at the FOLDER url (one canonical path), is active on the root landing…
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
    const { container } = render(<SidebarNav root={indexTitled} currentPathname="/docs" />);
    const folderLink = container.querySelector('a[href="/docs/runbooks"]')!;
    expect(folderLink).not.toBeNull();
    expect(folderLink.textContent).toBe("Runbooks"); // the index title, NOT "runbooks"
    // The folder's index child is surfaced ONLY by the folder row (its one canonical path) — it is
    // NOT also listed as a child page row at its own bare url.
    expect(container.querySelector('a[href="/docs/runbooks/index"]')).toBeNull();
    expect(container.querySelectorAll('[data-pb-nav-item="page"]')).toHaveLength(0);
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
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/guides/deploy-guide" />);
    expect(container.firstChild).toMatchSnapshot();
  });
});
