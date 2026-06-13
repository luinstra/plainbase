import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { SearchHit } from "../api/types";
import { SearchResultItem } from "../components/SearchResultItem";

/**
 * Snippet injection-inertness (criterion 2/13). A snippet containing `<script>` and
 * `<img onerror>` as LITERAL text must render as text nodes — never `innerHTML`. Kept
 * distinct from the pure-function highlightSplit goldens.
 */

function hit(snippet: string, highlights: { start: number; end: number }[]): SearchHit {
  return {
    page_id: "p1",
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

  it("joins the heading_path breadcrumb verbatim", () => {
    const { container } = render(
      <SearchResultItem hit={{ ...hit("s", []), heading_path: ["Deploy Guide", "Prerequisites"] }} id="opt" active={false} onActivate={noop} onHover={noop} />,
    );
    expect(container.textContent).toContain("Deploy Guide › Prerequisites");
  });
});
