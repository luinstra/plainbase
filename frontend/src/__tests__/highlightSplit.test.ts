import { describe, expect, it, vi } from "vitest";
import { splitHighlights } from "../lib/highlightSplit";

/**
 * §A3 highlight-splitter goldens (criterion 12) — exact-output assertions on the returned
 * structured fragments. The splitter is pure and never produces an HTML string.
 */
describe("splitHighlights", () => {
  it("1. empty highlights → one plain fragment", () => {
    expect(splitHighlights("hello world", [])).toEqual([{ text: "hello world", mark: false }]);
  });

  it("2. single mid-string range → pre/hit/post", () => {
    // "abcDEFghi", mark [3,6)
    expect(splitHighlights("abcDEFghi", [{ start: 3, end: 6 }])).toEqual([
      { text: "abc", mark: false },
      { text: "DEF", mark: true },
      { text: "ghi", mark: false },
    ]);
  });

  it("3. multiple disjoint ranges → alternating false/true", () => {
    // "0123456789", mark [1,3) and [5,7)
    expect(
      splitHighlights("0123456789", [
        { start: 1, end: 3 },
        { start: 5, end: 7 },
      ]),
    ).toEqual([
      { text: "0", mark: false },
      { text: "12", mark: true },
      { text: "34", mark: false },
      { text: "56", mark: true },
      { text: "789", mark: false },
    ]);
  });

  it("4. adjacent ranges → two consecutive marks, no empty filler", () => {
    expect(
      splitHighlights("abcdefg", [
        { start: 0, end: 3 },
        { start: 3, end: 6 },
      ]),
    ).toEqual([
      { text: "abc", mark: true },
      { text: "def", mark: true },
      { text: "g", mark: false },
    ]);
  });

  it("5. range at string start → no empty leading fragment", () => {
    expect(splitHighlights("abcdef", [{ start: 0, end: 3 }])).toEqual([
      { text: "abc", mark: true },
      { text: "def", mark: false },
    ]);
  });

  it("6. range at string end → no empty trailing fragment", () => {
    expect(splitHighlights("abcdef", [{ start: 3, end: 6 }])).toEqual([
      { text: "abc", mark: false },
      { text: "def", mark: true },
    ]);
  });

  it("7. full-string range → single mark fragment", () => {
    expect(splitHighlights("abcdef", [{ start: 0, end: 6 }])).toEqual([{ text: "abcdef", mark: true }]);
  });

  it("8. non-BMP snippet → marks whole grapheme region, no lone surrogate", () => {
    // "a😀b matched" — 😀 is U+1F600, two UTF-16 units at indices 1,2. Mark "😀b" → [1,4).
    const snippet = "a😀b matched";
    const fragments = splitHighlights(snippet, [{ start: 1, end: 4 }]);
    expect(fragments).toEqual([
      { text: "a", mark: false },
      { text: "😀b", mark: true },
      { text: " matched", mark: false },
    ]);
    for (const frag of fragments) {
      // No fragment ends or starts on a lone surrogate (the pair is preserved by slice).
      expect(/[\uD800-\uDBFF]$/.test(frag.text)).toBe(false);
      expect(/^[\uDC00-\uDFFF]/.test(frag.text)).toBe(false);
    }
  });

  it("9. leading/trailing ellipsis preserved as plain text", () => {
    const snippet = "…rolling deploy of the…";
    const fragments = splitHighlights(snippet, [{ start: 1, end: 8 }]);
    expect(fragments[0]).toEqual({ text: "…", mark: false });
    expect(fragments[1]).toEqual({ text: "rolling", mark: true });
    expect(fragments[fragments.length - 1]).toEqual({ text: " deploy of the…", mark: false });
  });

  it("10. malformed ranges — deterministic clamp-end / skip-collapse, no throw", () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => undefined);

    // inverted (start >= end) → skip → whole snippet plain
    expect(splitHighlights("0123456789", [{ start: 5, end: 3 }])).toEqual([{ text: "0123456789", mark: false }]);
    // negative start → skip → whole snippet plain
    expect(splitHighlights("0123456789", [{ start: -1, end: 2 }])).toEqual([{ text: "0123456789", mark: false }]);
    // end > length on a length-10 snippet → clamp end to 10 → full-string mark
    expect(splitHighlights("0123456789", [{ start: 0, end: 999 }])).toEqual([{ text: "0123456789", mark: true }]);

    for (const fragments of [
      splitHighlights("0123456789", [{ start: 5, end: 3 }]),
      splitHighlights("0123456789", [{ start: 0, end: 999 }]),
    ]) {
      for (const frag of fragments) {
        expect(typeof frag.text).toBe("string");
        expect(frag.text).not.toBe(undefined);
      }
    }
    expect(errorSpy).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });
});
