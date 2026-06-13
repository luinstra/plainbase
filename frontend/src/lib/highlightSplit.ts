/**
 * Highlight splitter for full-text snippets (PB-SEARCH-1 §A3). Pure — no DOM, no React.
 *
 * The rendering component maps each fragment to a bare text node or `<mark>` via React
 * interpolation, so the snippet stays text-node only — never `innerHTML` (§A3: the snippet
 * carries no sanitization guarantee, unlike the server-rendered prose HTML).
 */

export interface SnippetFragment {
  text: string;
  mark: boolean;
}

/** Mirrors the wire (§A3): UTF-16 code-unit offsets, half-open `[start, end)`. */
export interface Highlight {
  start: number;
  end: number;
}

/**
 * Splits `snippet` into ordered fragments per §A3: ranges are UTF-16 code-unit offsets,
 * half-open `[start, end)`, ascending and non-overlapping (server-guaranteed). Returns the
 * whole snippet as one unmarked fragment when `highlights` is empty.
 *
 * DEFENSIVE (debate finding): §A3 guarantees well-formed offsets, but the splitter still
 * hardens against a malformed range in ONE deterministic order — (1) clamp `end` to
 * `snippet.length`; (2) then skip the range when it is non-positive-width or starts out of
 * bounds (`start >= end` or `start < 0`). An `end > length` clamps to a valid mark; an
 * inverted or negative range drops to plain text. Worst case degrades to plain/partial
 * text; it never throws and never tears a code point (no XSS surface — text-node only).
 */
export function splitHighlights(snippet: string, highlights: Highlight[]): SnippetFragment[] {
  const fragments: SnippetFragment[] = [];
  let cursor = 0;
  for (const { start, end: rawEnd } of highlights) {
    const end = Math.min(rawEnd, snippet.length);
    if (start >= end || start < 0) continue; // inverted / negative / empty → skip to plain text
    if (start > cursor) fragments.push({ text: snippet.slice(cursor, start), mark: false });
    fragments.push({ text: snippet.slice(start, end), mark: true });
    cursor = end;
  }
  if (cursor < snippet.length) fragments.push({ text: snippet.slice(cursor), mark: false });
  // An all-skipped (or empty) highlight list with an empty snippet still yields one fragment.
  if (fragments.length === 0) fragments.push({ text: snippet, mark: false });
  return fragments;
}
