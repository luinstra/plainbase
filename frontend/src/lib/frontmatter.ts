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
 */
export interface SplitDocument {
  /** The raw frontmatter text WITHOUT the `---`/`...` fences, or null when the buffer has no leading block. */
  frontmatter: string | null;
  /** Everything after the closing fence (or the whole buffer, BOM stripped, when there is no block). */
  body: string;
}

export function splitFrontmatter(buffer: string): SplitDocument {
  // The server strips a leading UTF-8 BOM before delimiter detection; offsets resume after it.
  const bom = buffer.charCodeAt(0) === 0xfeff ? 1 : 0;

  // The opener is the first line after any BOM: EXACTLY `---` (no trailing space/tab — `--- ` is plain
  // Markdown to the server) terminated by an EOL. A bare `---` at EOF is a thematic break, not an opener.
  const opener = matchDelimiterLine(buffer, bom, "-", true);
  if (opener === null) return { frontmatter: null, body: buffer.slice(bom) };

  // The closer is the next line that is EXACTLY `---` or `...`, terminated by an EOL or EOF.
  let lineStart = opener;
  while (lineStart < buffer.length) {
    const dashClose = matchDelimiterLine(buffer, lineStart, "-", false);
    const dotClose = dashClose === null ? matchDelimiterLine(buffer, lineStart, ".", false) : null;
    const bodyStart = dashClose ?? dotClose;
    if (bodyStart !== null) {
      // The inner region is `[opener, closerLineStart)`; drop the single EOL terminating the last inner
      // line so `frontmatter` is the value text without a trailing newline (the body boundary is `bodyStart`,
      // identical to the server's — only the captured-string presentation trims the EOL).
      return { frontmatter: buffer.slice(opener, lineStart).replace(/\r?\n$|\r$/, ""), body: buffer.slice(bodyStart) };
    }
    lineStart = nextLineStart(buffer, lineStart);
  }
  // Opener present but no closing delimiter ⇒ NOT frontmatter (the `---` is a thematic break).
  return { frontmatter: null, body: buffer.slice(bom) };
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

/** Reads a top-level `key: value` scalar out of raw frontmatter text (no nesting), trimming surrounding quotes. */
export function frontmatterValue(frontmatter: string, key: string): string | null {
  for (const line of frontmatter.split("\n")) {
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
