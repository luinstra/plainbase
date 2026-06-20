/**
 * Splits a Markdown buffer into its leading YAML frontmatter block and the body beneath it.
 *
 * This MIRRORS the server's single authoritative grammar (`server/.../domain/page/FrontmatterBlock.kt`,
 * §A3) — it must strip EXACTLY what the server treats as frontmatter and nothing it treats as body, or a
 * "save as new" would either resurrect a stale `id:` block as rendered content or drop real body text.
 * The server grammar: after an optional UTF-8 BOM, the first line is exactly `---` (three dashes, NO
 * trailing chars — `--- ` is plain Markdown) terminated by an EOL (`\r\n`, `\r`, or bare `\n`); the block
 * ends at the next line that is exactly `---` OR `...` (three identical dashes/dots) terminated by an EOL
 * or EOF. A bare `---` at EOF is no opener, and an opener with no closer ⇒ NO frontmatter (the `---` is an
 * ordinary thematic break). With no block the whole buffer (after any BOM) is the body.
 *
 * DIFFERENTIAL-FRAGILITY: this is a faithful subset/mirror of the server grammar, deliberately neither
 * looser nor stricter. It MUST track `FrontmatterBlock.kt` if that grammar ever changes — `frontmatter.test.ts`
 * pins the divergence cases (BOM, `...` closer, `--- ` trailing-space non-opener). A future cleanup could
 * strip frontmatter SERVER-SIDE so there is one grammar truth; flagged, not built (W6 addendum §FIX 2).
 *
 * Used by the "save as new" recovery path (D-5): the server PREPENDS its own freshly-minted frontmatter
 * and appends `body` verbatim, so the client must send the BODY ONLY — otherwise the created file gets
 * two frontmatter blocks and the old one renders as literal text.
 *
 * The READ side (`splitFrontmatter`, `frontmatterValue`, `frontmatterList`) and the surgical WRITE side
 * (`setFrontmatterValue`, `removeFrontmatterKey`, `setFrontmatterList`) are both mirrors of the server's
 * one grammar — the writes are the client analogue of `FrontmatterPatcher` (C2): a single-point splice,
 * never a parse-and-reserialize. Every byte outside the targeted line(s) survives in order — no
 * re-encoding, EOL normalization, trailing-newline change, quoting change, or key reordering. All offsets
 * are computed by the SINGLE detector `frontmatterRegion` shares with `splitFrontmatter`; there is NO
 * second frontmatter detector. NB: offsets are JS STRING-UNIT (char) offsets — a BOM is ONE char, where
 * the server's identically-named `FrontmatterBlock.Detection.Present` fields count bytes.
 */
export interface SplitDocument {
  /** The raw frontmatter text WITHOUT the `---`/`...` fences, or null when the buffer has no leading block. */
  frontmatter: string | null;
  /** Everything after the closing fence (or the whole buffer, BOM stripped, when there is no block). */
  body: string;
}

export function splitFrontmatter(buffer: string): SplitDocument {
  const region = frontmatterRegion(buffer);
  if (region === null) {
    // No block: the whole buffer (after any leading BOM) is the body.
    const bom = buffer.charCodeAt(0) === 0xfeff ? 1 : 0;
    return { frontmatter: null, body: buffer.slice(bom) };
  }
  // The inner region is `[innerStart, innerEnd)`; drop the single EOL terminating the last inner line so
  // `frontmatter` is the value text without a trailing newline (the body boundary is `bodyStart`, identical
  // to the server's — only the captured-string presentation trims the EOL).
  return {
    frontmatter: buffer.slice(region.innerStart, region.innerEnd).replace(/\r?\n$|\r$/, ""),
    body: buffer.slice(region.bodyStart),
  };
}

/**
 * The char-offset anatomy of a buffer's leading frontmatter block, or null when there is none. Computed
 * by the SAME detector `splitFrontmatter` uses (`matchDelimiterLine`/`nextLineStart`) — one grammar truth.
 * Field NAMES mirror the server `FrontmatterBlock.Detection.Present`; the UNITS are JS chars (a BOM is one
 * char here, three bytes there). `innerStart..innerEnd` spans the inner lines incl. their EOLs (the closer
 * line start is `innerEnd`); `bodyStart` is the first body char (past the closer line).
 */
interface FrontmatterRegion {
  /** Char length of a leading UTF-8 BOM (0 or 1). */
  bomLength: number;
  /** First char of the inner region (just past the opener `---` line). */
  innerStart: number;
  /** One past the last inner char = the closer line's start. */
  innerEnd: number;
  /** First body char (just past the closer `---`/`...` line). */
  bodyStart: number;
}

function frontmatterRegion(buffer: string): FrontmatterRegion | null {
  // The server strips a leading UTF-8 BOM before delimiter detection; offsets resume after it.
  const bomLength = buffer.charCodeAt(0) === 0xfeff ? 1 : 0;

  // The opener is the first line after any BOM: EXACTLY `---` (no trailing space/tab — `--- ` is plain
  // Markdown to the server) terminated by an EOL. A bare `---` at EOF is a thematic break, not an opener.
  const innerStart = matchDelimiterLine(buffer, bomLength, "-", true);
  if (innerStart === null) return null;

  // The closer is the next line that is EXACTLY `---` or `...`, terminated by an EOL or EOF.
  let lineStart = innerStart;
  while (lineStart < buffer.length) {
    const dashClose = matchDelimiterLine(buffer, lineStart, "-", false);
    const bodyStart = dashClose ?? matchDelimiterLine(buffer, lineStart, ".", false);
    if (bodyStart !== null) return { bomLength, innerStart, innerEnd: lineStart, bodyStart };
    lineStart = nextLineStart(buffer, lineStart);
  }
  // Opener present but no closing delimiter ⇒ NOT frontmatter (the `---` is a thematic break).
  return null;
}

/**
 * Returns the offset just past the line at [start] when it is EXACTLY three [fence] chars terminated by an
 * EOL (`\r\n`, `\r`, or `\n`), else null. When [requireEol] (the opener), a fence at EOF is rejected — a
 * block needs a terminated opener; closers accept EOF.
 */
function matchDelimiterLine(buffer: string, start: number, fence: string, requireEol: boolean): number | null {
  if (buffer.slice(start, start + 3) !== fence.repeat(3)) return null;
  const after = start + 3;
  const ch = buffer[after];
  if (ch === "\n") return after + 1;
  if (ch === "\r") return buffer[after + 1] === "\n" ? after + 2 : after + 1;
  // No EOL: only a closer may sit at EOF; an opener must be terminated.
  return !requireEol && after === buffer.length ? after : null;
}

/** The offset of the line after [start], consuming one `\r\n`/`\r`/`\n` terminator (or EOF). */
function nextLineStart(buffer: string, start: number): number {
  let i = start;
  while (i < buffer.length && buffer[i] !== "\n" && buffer[i] !== "\r") i++;
  if (buffer[i] === "\r") i++;
  if (buffer[i] === "\n") i++;
  return i;
}

/**
 * Splits [text] into logical lines on the SAME CR/LF grammar as `nextLineStart`/the delimiter detector
 * (`\r\n`, bare `\r`, `\n`) — one grammar truth. The returned lines carry NO terminator. A naive
 * `text.split("\n")` would mis-model a bare-`\r` (classic-Mac) block as ONE line, so the field readers
 * (`frontmatterValue`/`frontmatterList`) must use this, never a raw `\n` split.
 */
function splitLines(text: string): string[] {
  const lines: string[] = [];
  let lineStart = 0;
  let i = 0;
  while (i < text.length) {
    if (text[i] === "\r" || text[i] === "\n") {
      lines.push(text.slice(lineStart, i));
      if (text[i] === "\r" && text[i + 1] === "\n") i++;
      i++;
      lineStart = i;
    } else {
      i++;
    }
  }
  lines.push(text.slice(lineStart));
  return lines;
}

/** Reads a top-level `key: value` scalar out of raw frontmatter text (no nesting), trimming surrounding quotes. */
export function frontmatterValue(frontmatter: string, key: string): string | null {
  for (const line of splitLines(frontmatter)) {
    // `splitLines` is the shared CR/LF grammar — it already drops the terminator, so a CRLF/bare-CR block's
    // lines arrive clean (no trailing \r to defeat the non-multiline `$`).
    const match = new RegExp(`^${key}:[ \\t]*(.*)$`).exec(line);
    if (!match) continue;
    const raw = match[1].trim();
    if (!raw) return null;
    return unquoteYamlScalar(raw);
  }
  return null;
}

/** Strips a single layer of matching YAML single/double quotes (best-effort — the values we read are simple scalars). */
function unquoteYamlScalar(value: string): string {
  if (value.length >= 2 && ((value[0] === '"' && value.endsWith('"')) || (value[0] === "'" && value.endsWith("'")))) {
    const inner = value.slice(1, -1);
    return value[0] === '"' ? inner.replace(/\\(["\\])/g, "$1") : inner;
  }
  return value;
}

/**
 * Reads a top-level YAML list out of [buffer]'s frontmatter — a block list (`key:` then `  - item` lines)
 * OR an inline flow list (`key: [a, b, "c, d"]`) — returning `[]` when the key is absent or empty. The
 * inverse of [setFrontmatterList]; reads externally-authored documents in either shape so the edit-mode
 * chips match the read rail (`PageView.asTags`). Scalar-only `frontmatterValue` cannot do this (C2/MINOR-4).
 */
export function frontmatterList(buffer: string, key: string): string[] {
  const { frontmatter } = splitFrontmatter(buffer);
  if (frontmatter === null) return [];
  const lines = splitLines(frontmatter);
  const keyIndex = lines.findIndex((line) => keyLineMatcher(key).test(line));
  if (keyIndex < 0) return [];

  const after = lines[keyIndex].replace(keyLineMatcher(key), "").trim();
  if (after.startsWith("[")) return parseInlineList(after);

  // Block list: the indented `  - item` run directly below the `key:` line.
  const items: string[] = [];
  for (let i = keyIndex + 1; i < lines.length; i++) {
    const item = /^[ \t]+-[ \t]*(.*)$/.exec(lines[i]);
    if (!item) break;
    items.push(unquoteYamlScalar(item[1].trim()));
  }
  return items;
}

/**
 * Parses an inline flow list `[a, b, "c, d"]` (best-effort, matching the scalar reader's quoting). Splits on
 * commas OUTSIDE quotes so a quoted item carrying a comma survives as one item; each item is then unquoted.
 */
function parseInlineList(inline: string): string[] {
  const inner = inline.replace(/^\[/, "").replace(/\][ \t]*$/, "").trim();
  if (inner === "") return [];
  const items: string[] = [];
  let current = "";
  let quote: '"' | "'" | null = null;
  for (let i = 0; i < inner.length; i++) {
    const ch = inner[i];
    if (quote) {
      if (ch === "\\" && quote === '"' && i + 1 < inner.length) {
        current += ch + inner[++i];
      } else {
        if (ch === quote) quote = null;
        current += ch;
      }
    } else if (ch === '"' || ch === "'") {
      quote = ch;
      current += ch;
    } else if (ch === ",") {
      items.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  items.push(current);
  return items.map((item) => unquoteYamlScalar(item.trim())).filter((item) => item !== "");
}

/**
 * Sets (or inserts) a top-level `key: value` line in [buffer]'s frontmatter, splicing ONLY that line —
 * every other byte (other keys, comments, order, quoting, the body, the BOM, the EOL style) is preserved.
 * When the buffer has no block, one is created (mirroring the server `FrontmatterPatcher.newBlock`). The
 * client analogue of the surgical patcher (C2/D-2): a single-point string splice, never a reserialize.
 */
export function setFrontmatterValue(buffer: string, key: string, value: string): string {
  return spliceFrontmatterLine(buffer, key, () => `${key}: ${emitScalar(value)}`);
}

/**
 * Writes a top-level YAML block list (`key:` then `  - item` lines), replacing the existing key block. The
 * list's INTERNAL separators use the SAME EOL the splice will use (the replaced span's own EOL in-place, the
 * neighbor's on insert, the file's first on create) — so a mixed-EOL block's tags edit never normalizes the
 * separators inside the block (matching the scalar path's EOL fidelity, C2 fold #8).
 */
export function setFrontmatterList(buffer: string, key: string, items: string[]): string {
  if (items.length === 0) return removeFrontmatterKey(buffer, key);
  return spliceFrontmatterLine(buffer, key, (eol) => `${key}:${eol}${items.map((item) => `  - ${emitScalar(item)}`).join(eol)}`);
}

/** Removes the top-level `key:` line (and a block list's following indented run) from the frontmatter; no-op if absent. */
export function removeFrontmatterKey(buffer: string, key: string): string {
  const span = frontmatterKeySpan(buffer, key);
  if (span === null) return buffer;
  return buffer.slice(0, span.lineStart) + buffer.slice(span.lineEnd);
}

/**
 * The shared splice core: replace the existing `key` line/run with [replacement] (in-place, copying the
 * replaced line's own EOL), or insert it at the end of the block (copying the line-above's EOL), or — when
 * there is no block — create one. Pure single-point edit, the byte-fidelity discipline of D-2.
 */
function spliceFrontmatterLine(buffer: string, key: string, replacement: (eol: string) => string): string {
  const region = frontmatterRegion(buffer);
  if (region === null) {
    // Absent: create `---{EOL}<replacement>{EOL}---{EOL}` after any BOM, body appended verbatim.
    const bom = buffer.charCodeAt(0) === 0xfeff ? 1 : 0;
    const eol = guessEol(buffer);
    const block = `---${eol}${replacement(eol)}${eol}---${eol}`;
    return buffer.slice(0, bom) + block + buffer.slice(bom);
  }

  const span = frontmatterKeySpan(buffer, key);
  if (span !== null) {
    // Update-in-place: replace the whole line/run, copying ITS terminator so CRLF/CR/LF and the
    // trailing-newline state are preserved exactly. A multi-line replacement (a list block) uses the same
    // EOL for its internal separators, so a mixed-EOL block isn't normalized.
    const eol = span.eol || guessEol(buffer);
    return buffer.slice(0, span.lineStart) + replacement(eol) + span.eol + buffer.slice(span.lineEnd);
  }

  // Insert-absent-key: append a new line just before the closer, copying the EOL of the line above it
  // (the last inner line, else the opener). Appending — not prepending — leaves a leading id:/title: order.
  const eol = lastInnerEol(buffer, region);
  return buffer.slice(0, region.innerEnd) + replacement(eol) + eol + buffer.slice(region.innerEnd);
}

/** The char span of a `key:` line (incl. a block list's indented run) within the frontmatter, with its own EOL. */
interface FrontmatterKeySpan {
  lineStart: number;
  /** One past the line/run's terminating EOL (or EOF). */
  lineEnd: number;
  /** The replaced line's own terminator (`\r\n`/`\r`/`\n`), or the buffer's guess when the line ends at EOF. */
  eol: string;
}

function frontmatterKeySpan(buffer: string, key: string): FrontmatterKeySpan | null {
  const region = frontmatterRegion(buffer);
  if (region === null) return null;

  const matcher = keyLineMatcher(key);
  let lineStart = region.innerStart;
  while (lineStart < region.innerEnd) {
    const lineEnd = nextLineStart(buffer, lineStart);
    if (matcher.test(buffer.slice(lineStart, lineEnd))) {
      // Consume any following indented `  - item` continuation run (a block list) so removing/replacing
      // the key drops its whole block, not just the `key:` line.
      let runEnd = lineEnd;
      while (runEnd < region.innerEnd && /^[ \t]+-/.test(buffer.slice(runEnd, nextLineStart(buffer, runEnd)))) {
        runEnd = nextLineStart(buffer, runEnd);
      }
      return { lineStart, lineEnd: runEnd, eol: eolOf(buffer, lineStart, runEnd) };
    }
    lineStart = lineEnd;
  }
  return null;
}

/** A column-0 `key:` line matcher (the same grammar `frontmatterValue` reads); the fixed C2 keys need no escaping. */
function keyLineMatcher(key: string): RegExp {
  return new RegExp(`^${key}:`);
}

/** The CR/LF terminator of the line/run ending at [end] (its last char before [end]), or "" when it ends at EOF. */
function eolOf(buffer: string, start: number, end: number): string {
  if (end > start && buffer[end - 1] === "\n") return buffer[end - 2] === "\r" ? "\r\n" : "\n";
  if (end > start && buffer[end - 1] === "\r") return "\r";
  return ""; // line sat at EOF with no terminator — preserve that (no EOL re-added).
}

/** The EOL to copy when inserting a new line at the block end: the last inner line's terminator, else the opener's. */
function lastInnerEol(buffer: string, region: FrontmatterRegion): string {
  // The inner region ends at `innerEnd` (the closer line start); the char just before it is the last
  // inner line's terminator. An empty inner region falls back to the opener line's terminator.
  const fromOpener = eolOf(buffer, region.bomLength, region.innerStart);
  if (region.innerEnd === region.innerStart) return fromOpener || guessEol(buffer);
  const inner = eolOf(buffer, region.innerStart, region.innerEnd);
  return inner || fromOpener || guessEol(buffer);
}

/** The buffer's first line terminator (mirrors the server `firstLineTerminator`), or `\n` when there is none. */
function guessEol(buffer: string): string {
  const crlf = buffer.indexOf("\r\n");
  const cr = buffer.indexOf("\r");
  const lf = buffer.indexOf("\n");
  if (crlf >= 0 && (lf < 0 || crlf <= lf)) return "\r\n";
  if (cr >= 0 && (lf < 0 || cr < lf)) return "\r";
  return "\n";
}

/**
 * Emits a value as a YAML scalar — plain when grammar-safe, else double-quoted (escaping `"`/`\`). The
 * inverse of [unquoteYamlScalar]; the quote path is the safety valve for a value that would otherwise break
 * the line grammar (a leading indicator, an embedded mapping `: `, or leading/trailing whitespace).
 */
function emitScalar(value: string): string {
  // A frontmatter scalar for the C2 editorial fields is single-line BY NATURE. A raw CR/LF would write a
  // physical extra line, breaking the single-line-splice invariant (a `tags:`-style continuation, or a
  // body line leaking into the block). Strip them here rather than escape — escaping into a double-quoted
  // `\n` would force `unquoteYamlScalar` to grow a matching un-escape, and YAML double-quoted `\n` is a
  // newline, not a literal — neither is worth it for fields that are single-line by definition.
  const single = value.replace(/[\r\n]+/g, " ");
  if (single !== "" && single === single.trim() && !startsWithIndicator(single) && !hasMappingColon(single)) return single;
  return `"${single.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

/** YAML `ns-plain-first` indicators that may not begin a plain scalar (mirrors `FrontmatterPatcher.INDICATOR_CHARS`). */
function startsWithIndicator(value: string): boolean {
  return "-?:,[]{}#&*!|>'\"%@`".includes(value[0]);
}

/** A plain scalar carries no mapping colon (a `:` followed by space/tab) — that would read as `k: v: w`. */
function hasMappingColon(value: string): boolean {
  return /:[ \t]/.test(value) || value.endsWith(":");
}
