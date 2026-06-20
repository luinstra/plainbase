import { EditorSelection, EditorState, type TransactionSpec } from "@codemirror/state";
import { describe, expect, it } from "vitest";
import {
  insertLinkCore,
  insertTableCore,
  toggleBlockquoteCore,
  toggleBoldCore,
  toggleBulletListCore,
  toggleCodeBlockCore,
  toggleCodeCore,
  toggleHeadingCore,
  toggleItalicCore,
  toggleNumberedListCore,
} from "./markdownCommands";

/**
 * C3 markdown-command goldens (acceptance #1, D-1/D-6). Pure: construct an `EditorState` with a doc +
 * selection, apply the core's `TransactionSpec`, assert the resulting doc string and selection — no DOM,
 * mirroring `unifiedDiff.test.ts`. These lock the exact wrap/toggle/normalize behaviors; widen them (not
 * just the code) for any hole a review finds.
 */

/** Build a state from a doc and a selection range (anchor..head; collapsed when equal). */
function stateOf(doc: string, anchor: number, head = anchor): EditorState {
  return EditorState.create({ doc, selection: EditorSelection.range(anchor, head) });
}

/** Apply a core to a state and return the resulting { doc, from, to }. */
function applyCore(core: (state: EditorState) => TransactionSpec | null, state: EditorState) {
  const spec = core(state);
  expect(spec).not.toBeNull();
  const next = state.update(spec!);
  return { doc: next.state.doc.toString(), from: next.state.selection.main.from, to: next.state.selection.main.to };
}

describe("inline wrap-toggle", () => {
  it("bold wraps a selection (** …**) and selects the inner text", () => {
    const r = applyCore(toggleBoldCore, stateOf("word here", 0, 4));
    expect(r.doc).toBe("**word** here");
    expect([r.from, r.to]).toEqual([2, 6]);
  });

  it("bold toggles OFF an already-bold selection (outer markers)", () => {
    // Selection is the inner `word`, markers just outside — what the wrap op produces.
    const r = applyCore(toggleBoldCore, stateOf("**word** here", 2, 6));
    expect(r.doc).toBe("word here");
    expect([r.from, r.to]).toEqual([0, 4]);
  });

  it("bold toggles OFF when the markers are inside the selection", () => {
    // User selected the whole `**word**`.
    const r = applyCore(toggleBoldCore, stateOf("**word** here", 0, 8));
    expect(r.doc).toBe("word here");
    expect([r.from, r.to]).toEqual([0, 4]);
  });

  it("bold with no selection inserts ** ** and places the cursor between", () => {
    const r = applyCore(toggleBoldCore, stateOf("ab", 1));
    expect(r.doc).toBe("a****b");
    expect([r.from, r.to]).toEqual([3, 3]);
  });

  it("italic uses _ (underscore) and wraps/toggles", () => {
    const wrapped = applyCore(toggleItalicCore, stateOf("word", 0, 4));
    expect(wrapped.doc).toBe("_word_");
    expect([wrapped.from, wrapped.to]).toEqual([1, 5]);
    const toggledOff = applyCore(toggleItalicCore, stateOf("_word_", 1, 5));
    expect(toggledOff.doc).toBe("word");
  });

  it("italic core ALWAYS returns a spec when it acts (so Mod-i never falls through to selectParentSyntax)", () => {
    // A selection, a collapsed cursor, and an already-wrapped selection all yield a non-null spec — the
    // Command therefore always returns true on Mod-i, so runFor stops before the default fires.
    expect(toggleItalicCore(stateOf("word", 0, 4))).not.toBeNull();
    expect(toggleItalicCore(stateOf("word", 2))).not.toBeNull();
    expect(toggleItalicCore(stateOf("_word_", 1, 5))).not.toBeNull();
  });

  it("code wraps with a backtick and toggles", () => {
    const wrapped = applyCore(toggleCodeCore, stateOf("x", 0, 1));
    expect(wrapped.doc).toBe("`x`");
    const toggledOff = applyCore(toggleCodeCore, stateOf("`x`", 1, 2));
    expect(toggledOff.doc).toBe("x");
  });
});

describe("line-prefix toggle", () => {
  it("heading adds ## to each line, normalizes an existing #/### to ##, and toggles off", () => {
    const added = applyCore(toggleHeadingCore, stateOf("Title", 0));
    expect(added.doc).toBe("## Title");

    const normalizedH1 = applyCore(toggleHeadingCore, stateOf("# Title", 0));
    expect(normalizedH1.doc).toBe("## Title");
    const normalizedH3 = applyCore(toggleHeadingCore, stateOf("### Title", 0));
    expect(normalizedH3.doc).toBe("## Title");

    const off = applyCore(toggleHeadingCore, stateOf("## Title", 0));
    expect(off.doc).toBe("Title");
  });

  it("bullet list toggles - per line over a multi-line selection (all-prefixed ⇒ remove)", () => {
    const added = applyCore(toggleBulletListCore, stateOf("a\nb\nc", 0, 5));
    expect(added.doc).toBe("- a\n- b\n- c");
    const off = applyCore(toggleBulletListCore, stateOf("- a\n- b\n- c", 0, 11));
    expect(off.doc).toBe("a\nb\nc");
    // A partial selection (one line already bulleted) becomes fully bulleted.
    const partial = applyCore(toggleBulletListCore, stateOf("- a\nb", 0, 5));
    expect(partial.doc).toBe("- - a\n- b");
  });

  it("numbered list writes 1. per line and toggles off any ^\\d+\\. ", () => {
    const added = applyCore(toggleNumberedListCore, stateOf("a\nb", 0, 3));
    expect(added.doc).toBe("1. a\n1. b");
    // Toggle-off matches an externally-authored 3. too.
    const off = applyCore(toggleNumberedListCore, stateOf("1. a\n3. b", 0, 9));
    expect(off.doc).toBe("a\nb");
  });

  it("blockquote toggles > per line", () => {
    const added = applyCore(toggleBlockquoteCore, stateOf("a\nb", 0, 3));
    expect(added.doc).toBe("> a\n> b");
    const off = applyCore(toggleBlockquoteCore, stateOf("> a\n> b", 0, 7));
    expect(off.doc).toBe("a\nb");
  });
});

describe("fenced code block", () => {
  it("wraps the selected lines in ``` fences and re-selects the inner span", () => {
    const r = applyCore(toggleCodeBlockCore, stateOf("a\nb", 0, 3));
    expect(r.doc).toBe("```\na\nb\n```");
    // The inner span is re-selected (just past the opening fence + newline, through the original end).
    expect(r.doc.slice(r.from, r.to)).toBe("a\nb");
  });

  it("toggles OFF an already-fenced block (the lines inside ``` fences)", () => {
    // Cursor inside the fenced block (on `a`); the lines above/below are bare ``` fences → strip them.
    const r = applyCore(toggleCodeBlockCore, stateOf("```\na\nb\n```", 4, 7));
    expect(r.doc).toBe("a\nb");
    expect(r.doc.slice(r.from, r.to)).toBe("a\nb");
  });

  it("on a blank line with an empty cursor inserts an empty fence with the cursor inside", () => {
    const r = applyCore(toggleCodeBlockCore, stateOf("", 0));
    expect(r.doc).toBe("```\n\n```");
    // Cursor on the empty inner line (after the opening ``` + newline).
    expect([r.from, r.to]).toEqual(["```\n".length, "```\n".length]);
  });
});

describe("link", () => {
  it("link wraps a selection as [sel](url) with the cursor in the url slot", () => {
    const r = applyCore(insertLinkCore, stateOf("docs", 0, 4));
    expect(r.doc).toBe("[docs](url)");
    // Cursor between the parens (after `[docs](`).
    expect([r.from, r.to]).toEqual(["[docs](".length, "[docs](".length]);
    expect(r.doc.slice(r.from, r.from + "url)".length)).toBe("url)");
  });

  it("link with no selection inserts [](url) with the cursor in the text slot", () => {
    const r = applyCore(insertLinkCore, stateOf("", 0));
    expect(r.doc).toBe("[](url)");
    expect([r.from, r.to]).toEqual([1, 1]);
  });
});

describe("table", () => {
  const SKELETON = "| Column | Column |\n| --- | --- |\n| Cell | Cell |";

  it("on a blank-line doc inserts the skeleton in place and selects the first header cell", () => {
    const r = applyCore(insertTableCore, stateOf("", 0));
    expect(r.doc).toBe(SKELETON);
    // The first `Column` header cell is selected so the author types to replace it.
    expect(r.doc.slice(r.from, r.to)).toBe("Column");
    expect([r.from, r.to]).toEqual(["| ".length, "| Column".length]);
  });

  it("a MID-WORD cursor inserts the table after the whole line — never splits the line (anchors at line.to)", () => {
    // Cursor at offset 2 — inside `pr|ose`. The table must NOT split it into `pr`/`ose`; it anchors at the
    // line END so the line stays intact and the table follows as its own block.
    const r = applyCore(insertTableCore, stateOf("prose", 2));
    expect(r.doc).toBe(`prose\n\n${SKELETON}\n`);
    // The existing line is intact and the first header cell is still selected.
    expect(r.doc.slice(r.from, r.to)).toBe("Column");
  });

  it("a non-empty selection collapses to the cursor and inserts a fresh table (insert, not wrap)", () => {
    // Selection anchor at the start, cursor (head) at the end of the non-blank line; the table goes in as
    // its own block at the cursor — the selected text is NOT consumed/wrapped.
    const r = applyCore(insertTableCore, stateOf("pick", 0, 4));
    expect(r.doc).toBe(`pick\n\n${SKELETON}\n`);
    expect(r.doc.slice(r.from, r.to)).toBe("Column");
  });
});
