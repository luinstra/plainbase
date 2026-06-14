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
  path: "",
  url: "/docs",
  children: [
    {
      type: "folder",
      name: "guides",
      title: "Guides",
      path: "guides",
      url: "/docs/guides",
      children: [
        {
          type: "page",
          id: "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a",
          title: "Deploy Guide",
          slug: "deploy-guide",
          path: "guides/deploy-guide.md",
          url: "/docs/guides/deploy-guide",
          status: "active",
        },
      ],
    },
    {
      // A collision-loser FOLDER: url null → inert label, subtree still listed.
      type: "folder",
      name: "shadowed-folder",
      title: null,
      path: "shadowed-folder",
      url: null,
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

  it("matches the stable-markup snapshot", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/guides/deploy-guide" />);
    expect(container.firstChild).toMatchSnapshot();
  });
});
