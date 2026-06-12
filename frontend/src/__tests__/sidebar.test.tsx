import { render } from "@testing-library/react";
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
  children: [
    {
      type: "folder",
      name: "guides",
      title: "Guides",
      path: "guides",
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
      // A path-space collision loser: url null → the link must fall back to /p/{id}.
      type: "page",
      id: "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99",
      title: "Shadowed Page",
      slug: "shadowed",
      path: "shadowed.md",
      url: null,
      status: "active",
    },
  ],
};

describe("SidebarNav", () => {
  it("emits the stable selectors and links from node urls", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/guides/deploy-guide" />);

    const sidebar = container.querySelector(".pb-sidebar");
    expect(sidebar).not.toBeNull();
    expect(sidebar!.hasAttribute("data-pb-sidebar")).toBe(true);
    expect(container.querySelectorAll('[data-pb-nav-item="folder"]')).toHaveLength(1);
    expect(container.querySelectorAll('[data-pb-nav-item="page"]')).toHaveLength(2);

    const canonical = container.querySelector('a[href="/docs/guides/deploy-guide"]');
    expect(canonical).not.toBeNull();
    expect(canonical!.getAttribute("aria-current")).toBe("page");

    // The collision loser links via its permalink.
    const loser = container.querySelector('a[href="/p/0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99"]');
    expect(loser).not.toBeNull();
    expect(loser!.textContent).toBe("Shadowed Page");
  });

  it("matches the stable-markup snapshot", () => {
    const { container } = render(<SidebarNav root={tree} currentPathname="/docs/guides/deploy-guide" />);
    expect(container.firstChild).toMatchSnapshot();
  });
});
