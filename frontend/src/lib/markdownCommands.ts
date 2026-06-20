import { EditorSelection, type EditorState, type TransactionSpec } from "@codemirror/state";
import type { Command, EditorView } from "@codemirror/view";

/**
 * C3 markdown authoring commands: the toolbar + keymap formatting ops (bold/italic/code/link/heading/
 * bullet-list/numbered-list/blockquote) over the BODY CodeMirror doc. Each op is TWO layers (D-1):
 *
 *  - a PURE core `(state) => TransactionSpec | null` — computes the changes + new selection from the doc
 *    and selection, returns `null` when it declines (so a keymap can fall through to a default binding);
 *  - a thin `Command` `(view) => boolean` that runs the core, dispatches when non-null, and returns
 *    `true` whenever it ACTED. The cores are what `markdownCommands.test.ts` exercises (construct an
 *    `EditorState`, apply the spec, assert doc + selection) — no DOM, mirroring `lib/unifiedDiff.ts`.
 *
 * Marker / shape choices (greenfield, recorded in the C3 addendum D-1):
 *  - italic marker is `_` (CommonMark-conventional emphasis; avoids `*`/`**` visual ambiguity);
 *  - heading is a SINGLE H2 toggle (`## `) with `#+ `-prefix normalization (no level cycling);
 *  - numbered list writes `1. ` per line (CommonMark renders the ordered list regardless; no renumbering).
 *
 * LOAD-BEARING: `Mod-i` is bound in `defaultKeymap` to `selectParentSyntax`. The formatting keymap is
 * PREPENDED before the defaults in the extensions array, and `toggleItalic` RETURNS `true` whenever it
 * acts (it always does — a selection wraps/toggles, an empty cursor inserts a pair), so CM6's `runFor`
 * stops before `selectParentSyntax` runs. The ordering + the `true` return are what prevent the clobber.
 */

/** Turns a pure core into a `Command`: dispatch + refocus when it acts, return whether it handled the key. */
function commandFromCore(core: (state: EditorState) => TransactionSpec | null): Command {
  return (view: EditorView) => {
    const spec = core(view.state);
    if (!spec) return false;
    view.dispatch(spec);
    return true;
  };
}

// ---- inline wrap-toggle (bold/italic/code) -------------------------------------------------------

/**
 * Wrap-toggle a single marker around the selection. With a selection: toggle OFF if it's already wrapped
 * (markers just outside, or the selection itself begins/ends with the marker), else wrap and re-select the
 * inner text. With an empty cursor: insert the marker pair and place the cursor between (never toggles off).
 */
function wrapToggleCore(marker: string): (state: EditorState) => TransactionSpec | null {
  const m = marker.length;
  return (state) => {
    const { from, to } = state.selection.main;
    const doc = state.doc;
    if (from === to) {
      // Empty cursor: insert `marker + marker`, cursor between the pair.
      return { changes: { from, insert: marker + marker }, selection: EditorSelection.cursor(from + m) };
    }
    // Already wrapped with the markers OUTSIDE the selection (what the wrap op produces).
    if (from - m >= 0 && to + m <= doc.length && doc.sliceString(from - m, from) === marker && doc.sliceString(to, to + m) === marker) {
      return {
        changes: [
          { from: from - m, to: from },
          { from: to, to: to + m },
        ],
        selection: EditorSelection.range(from - m, to - m),
      };
    }
    // Already wrapped with the markers INSIDE the selection (the user selected the whole `**word**`).
    if (to - from >= 2 * m && doc.sliceString(from, from + m) === marker && doc.sliceString(to - m, to) === marker) {
      return {
        changes: [
          { from, to: from + m },
          { from: to - m, to },
        ],
        selection: EditorSelection.range(from, to - 2 * m),
      };
    }
    // Plain selection: wrap it, re-select the inner text.
    return {
      changes: [
        { from, insert: marker },
        { from: to, insert: marker },
      ],
      selection: EditorSelection.range(from + m, to + m),
    };
  };
}

export const toggleBoldCore = wrapToggleCore("**");
export const toggleItalicCore = wrapToggleCore("_");
export const toggleCodeCore = wrapToggleCore("`");

export const toggleBold = commandFromCore(toggleBoldCore);
export const toggleItalic = commandFromCore(toggleItalicCore);
export const toggleCode = commandFromCore(toggleCodeCore);

// ---- line-prefix toggle (heading/bullet/numbered/blockquote) -------------------------------------

/** The inclusive line numbers (1-based) the selection touches. */
function selectedLines(state: EditorState): { first: number; last: number } {
  const { from, to } = state.selection.main;
  return { first: state.doc.lineAt(from).number, last: state.doc.lineAt(to).number };
}

/**
 * Toggle a per-line prefix over every line the selection touches. `has` decides if a line already carries
 * the prefix; `strip` returns the line text with the prefix removed; `add` returns the prefixed line text.
 * If ALL non-blank targeted lines already carry it, remove; else add to every line (a partial selection
 * becomes fully prefixed). The selection is preserved over the rewritten span.
 */
function linePrefixCore(
  has: (text: string) => boolean,
  strip: (text: string) => string,
  add: (text: string) => string,
): (state: EditorState) => TransactionSpec | null {
  return (state) => {
    const { first, last } = selectedLines(state);
    const lines = [];
    let allPrefixed = true;
    let anyNonBlank = false;
    for (let n = first; n <= last; n++) {
      const line = state.doc.line(n);
      lines.push(line);
      if (line.text.trim().length > 0) {
        anyNonBlank = true;
        if (!has(line.text)) allPrefixed = false;
      }
    }
    const remove = anyNonBlank && allPrefixed;
    const changes = lines
      .filter((line) => (remove ? has(line.text) : true))
      .map((line) => ({ from: line.from, to: line.to, insert: remove ? strip(line.text) : add(line.text) }));
    if (changes.length === 0) return null;
    const span = { from: lines[0].from, to: lines[lines.length - 1].to };
    return { changes, selection: EditorSelection.range(span.from, mapSpanEnd(span.to, changes)) };
  };
}

/** The post-change position of the original span end, given the in-span replacements (changes are ordered). */
function mapSpanEnd(end: number, changes: { from: number; to: number; insert: string }[]): number {
  let delta = 0;
  for (const c of changes) delta += c.insert.length - (c.to - c.from);
  return end + delta;
}

const HEADING_PREFIX = /^#+ /;
const NUMBERED_PREFIX = /^\d+\. /;

export const toggleHeadingCore = linePrefixCore(
  (text) => text.startsWith("## "),
  (text) => text.slice("## ".length),
  (text) => `## ${text.replace(HEADING_PREFIX, "")}`,
);

export const toggleBulletListCore = linePrefixCore(
  (text) => text.startsWith("- "),
  (text) => text.slice("- ".length),
  (text) => `- ${text}`,
);

export const toggleNumberedListCore = linePrefixCore(
  (text) => NUMBERED_PREFIX.test(text),
  (text) => text.replace(NUMBERED_PREFIX, ""),
  (text) => `1. ${text}`,
);

export const toggleBlockquoteCore = linePrefixCore(
  (text) => text.startsWith("> "),
  (text) => text.slice("> ".length),
  (text) => `> ${text}`,
);

export const toggleHeading = commandFromCore(toggleHeadingCore);
export const toggleBulletList = commandFromCore(toggleBulletListCore);
export const toggleNumberedList = commandFromCore(toggleNumberedListCore);
export const toggleBlockquote = commandFromCore(toggleBlockquoteCore);

// ---- fenced code block ---------------------------------------------------------------------------

const FENCE = "```";

/**
 * Toggle a fenced code block around the selected lines. With a selection (or a cursor on a line):
 * if the targeted block is ALREADY fenced (the line above the first targeted line is a bare ``` and
 * the line below the last is a bare ```), strip both fences; else wrap the line span in ``` fences and
 * re-select the inner span. With an empty cursor on a BLANK line: insert an empty fence pair with the
 * cursor on the empty line between (the common "start a code block" flow).
 */
export const toggleCodeBlockCore = (state: EditorState): TransactionSpec | null => {
  const doc = state.doc;
  const { first, last } = selectedLines(state);
  const firstLine = doc.line(first);
  const lastLine = doc.line(last);

  // Already fenced: a bare ``` line immediately above the span and immediately below it → strip both.
  const above = first > 1 ? doc.line(first - 1) : null;
  const below = last < doc.lines ? doc.line(last + 1) : null;
  if (above?.text.trim() === FENCE && below?.text.trim() === FENCE) {
    return {
      changes: [
        { from: above.from, to: firstLine.from }, // the ``` line + its trailing newline
        { from: lastLine.to, to: below.to }, // the trailing newline + the closing ``` line
      ],
      selection: EditorSelection.range(above.from, above.from + (lastLine.to - firstLine.from)),
    };
  }

  // Empty cursor on a blank line: insert an empty fence with the cursor on the inner blank line.
  const { from, to } = state.selection.main;
  if (from === to && firstLine.text.length === 0) {
    return {
      changes: { from: firstLine.from, insert: `${FENCE}\n\n${FENCE}` },
      selection: EditorSelection.cursor(firstLine.from + FENCE.length + 1),
    };
  }

  // Wrap the line span in fences, re-select the inner (now-shifted) span.
  return {
    changes: [
      { from: firstLine.from, insert: `${FENCE}\n` },
      { from: lastLine.to, insert: `\n${FENCE}` },
    ],
    selection: EditorSelection.range(firstLine.from + FENCE.length + 1, lastLine.to + FENCE.length + 1),
  };
};

export const toggleCodeBlock = commandFromCore(toggleCodeBlockCore);

// ---- link ----------------------------------------------------------------------------------------

/**
 * Insert a markdown link. With a selection: `[sel](url)` with the cursor in the empty `()` url slot (the
 * author types the URL next). With an empty cursor: `[](url)` with the cursor in the `[]` text slot (the
 * author types the link text next — the common empty-cursor flow).
 */
export const insertLinkCore = (state: EditorState): TransactionSpec | null => {
  const { from, to } = state.selection.main;
  if (from === to) {
    const insert = "[](url)";
    return { changes: { from, insert }, selection: EditorSelection.cursor(from + "[".length) };
  }
  const sel = state.doc.sliceString(from, to);
  const insert = `[${sel}](url)`;
  // Cursor between the parens (after `[sel](`).
  const urlSlot = from + `[${sel}](`.length;
  return { changes: { from, to, insert }, selection: EditorSelection.cursor(urlSlot) };
};

export const insertLink = commandFromCore(insertLinkCore);

// ---- table ---------------------------------------------------------------------------------------

const TABLE = "| Column | Column |\n| --- | --- |\n| Cell | Cell |";

/**
 * Insert a GFM starter table as its OWN block at the cursor, then select the first header `Column` so the
 * author types to replace it (parking like `insertLink`). A GFM table must start at a line and be set off
 * as a block, so: on a blank line we insert in place; with text on the current line we prepend a blank line
 * (newline + blank-line separation) and append a trailing newline so the table parses as a block, not inline.
 * A non-empty selection collapses to the cursor — this is an insert, not a wrap of the selected text.
 */
export const insertTableCore = (state: EditorState): TransactionSpec | null => {
  // A non-empty selection collapses to the head — this is an insert, not a wrap.
  const at = state.selection.main.head;
  const line = state.doc.lineAt(at);
  const onBlankLine = line.text.trim().length === 0;
  // On a non-blank line, anchor AFTER the WHOLE line (line.to), never at the raw cursor offset — inserting
  // mid-word would split the line. Set the table off as its own block: a blank line before, a newline after.
  // On a blank line, insert in place at the cursor.
  const anchor = onBlankLine ? at : line.to;
  const prefix = onBlankLine ? "" : "\n\n";
  const suffix = onBlankLine ? "" : "\n";
  const insert = `${prefix}${TABLE}${suffix}`;
  // Select the first `Column` header cell (after the leading separation + `| `).
  const cellStart = anchor + prefix.length + "| ".length;
  return { changes: { from: anchor, to: anchor, insert }, selection: EditorSelection.range(cellStart, cellStart + "Column".length) };
};

export const insertTable = commandFromCore(insertTableCore);
