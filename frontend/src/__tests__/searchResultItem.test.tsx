import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { SearchHit } from "../api/types";
import { SearchResultItem } from "../components/SearchResultItem";
import { JumpToItem } from "../components/JumpToItem";
import type { QuickSwitchEntry } from "../lib/tree";

/**
 * Snippet injection-inertness (criterion 2/13). A snippet containing `<script>` and
 * `<img onerror>` as LITERAL text must render as text nodes — never `innerHTML`. Kept
 * distinct from the pure-function highlightSplit goldens.
 */

function hit(snippet: string, highlights: { start: number; end: number }[]): SearchHit {
  return {
    page_id: "p1",
    root: "docs",
    path: "x.md",
    url: "/docs/x",
    title: "X",
    heading_id: "h",
    heading_text: "H",
    heading_path: ["X", "H"],
    snippet,
    highlights,
    score: 1,
    citation: { page_id: "p1", heading_id: "h", path: "x.md", content_hash: "h", commit: null, uri: "plainbase://p1#h@h" },
  };
}

function noop() {}

describe("SearchResultItem", () => {
  it("omits only the leading title breadcrumb and hides a genuinely empty excerpt", () => {
    const props = { id: "opt", active: false, onActivate: noop, onHover: noop };
    const { container, rerender } = render(<SearchResultItem {...props} hit={{ ...hit("Actual body", []), heading_path: [" x ", "Details"] }} />);
    expect(container.querySelector("[data-pb-search-trail]")?.textContent).toBe("x.md · Details");
    expect(container.querySelector("[data-pb-search-snippet]")?.textContent).toBe("Actual body");
    rerender(<SearchResultItem {...props} hit={{ ...hit("", []), heading_path: ["X"] }} />);
    expect(container.querySelector("[data-pb-search-trail]")?.textContent).toBe("x.md");
    expect(container.querySelector("[data-pb-search-snippet]")).toBeNull();
  });
  it("highlights literal query terms safely in quick and content titles, not path-only matches", () => {
    const title = "Deploy <img> Guide";
    const entry = { root: "docs", page: { id: "p1", title, path: "special.md" } } as QuickSwitchEntry;
    const props = { id: "opt", active: false, onActivate: noop, onHover: noop, query: "DEPLOY <img>" };
    const quick = render(<JumpToItem entry={entry} {...props} />);
    const content = render(<SearchResultItem hit={{ ...hit("body", []), title }} {...props} />);
    for (const view of [quick, content]) {
      const heading = view.container.querySelector('[title="Deploy <img> Guide"]')!;
      expect([...heading.querySelectorAll("mark")].map((mark) => mark.textContent)).toEqual(["Deploy", "<img>"]);
      expect(heading.textContent).toBe(title);
      expect(heading.querySelector("img")).toBeNull();
    }
    quick.rerender(<JumpToItem entry={entry} {...props} query="special" />);
    expect(quick.container.querySelector("mark")).toBeNull();
  });
  it("renders attacker-controlled snippet text inert (no script/img elements injected)", () => {
    const snippet = 'before <script>alert(1)</script> and <img onerror=bad> after';
    const { container } = render(<SearchResultItem hit={hit(snippet, [{ start: 0, end: 6 }])} id="opt" active={false} onActivate={noop} onHover={noop} />);

    const result = container.querySelector("[data-pb-search-snippet]")!;
    // The literal angle-bracket text survives as text content…
    expect(result.textContent).toContain("<script>alert(1)</script>");
    expect(result.textContent).toContain("<img onerror=bad>");
    // …but no actual <script>/<img> element was created in the subtree.
    expect(result.querySelector("script")).toBeNull();
    expect(result.querySelector("img")).toBeNull();
  });

  it("wraps highlighted ranges in <mark> (text-node split, not innerHTML)", () => {
    const { container } = render(<SearchResultItem hit={hit("rolling deploy", [{ start: 0, end: 7 }])} id="opt" active={false} onActivate={noop} onHover={noop} />);
    const mark = container.querySelector("[data-pb-search-snippet] mark");
    expect(mark).not.toBeNull();
    expect(mark!.textContent).toBe("rolling");
  });

  it("combines path and verbatim heading trail on one line without losing full text", () => {
    const { container } = render(
      <SearchResultItem hit={{ ...hit("s", []), heading_path: ["Deploy Guide", "Prerequisites"] }} id="opt" active={false} onActivate={noop} onHover={noop} />,
    );
    expect(container.textContent).toContain("Deploy Guide › Prerequisites");
    const trail = container.querySelector("[data-pb-search-trail]");
    expect(trail?.textContent).toBe("x.md · Deploy Guide › Prerequisites");
    expect(trail?.getAttribute("title")).toBe("x.md · Deploy Guide › Prerequisites");
    expect(trail?.querySelector(".font-mono")?.textContent).toBe("x.md");
    expect(trail?.querySelector(".font-sans")?.textContent).toBe("Deploy Guide › Prerequisites");
  });

  it("carries the data-pb-search-active tint hook only when active", () => {
    const props = { hit: hit("s", []), id: "opt", onActivate: noop, onHover: noop };
    const inactive = render(<SearchResultItem {...props} active={false} />);
    expect(inactive.container.querySelector("[data-pb-search-active]")).toBeNull();

    const activeRow = render(<SearchResultItem {...props} active={true} />);
    expect(activeRow.container.querySelector('[data-pb-search-item="hit"]')!.getAttribute("data-pb-search-active")).toBe("");
  });
});
