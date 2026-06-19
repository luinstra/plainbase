/**
 * Unified-diff parsing for the W7 history surface (D-5). Pure — no DOM, no React, no hljs — so the
 * line-classification + marker-stripping logic is unit-testable in isolation (mirrors lib/highlightSplit.ts).
 *
 * The `unified_diff` blob is raw `git diff` text (DiffResponse.unified_diff). The renderer turns each
 * parsed line into a token-styled row; intra-line syntax highlighting (one detected language) lives in
 * the renderer, not here, because it depends on hljs.
 */

/** A giant diff is never parsed/highlighted into the DOM — over this many chars we render the cap state. */
export const MAX_DIFF_RENDER_CHARS = 200_000;

/**
 * `meta`   — diff/file/hunk headers and notices (NOT a content +/- row).
 * `add`    — an inserted line (leading `+`, not a `+++` header).
 * `del`    — a removed line (leading `-`, not a `---` header).
 * `context`— an unchanged line (leading space, or an empty line).
 */
export type DiffLineKind = "meta" | "add" | "del" | "context";

export interface DiffLine {
  kind: DiffLineKind;
  /** The line content WITHOUT the leading diff-marker column (meta keeps its full text). */
  text: string;
}

/**
 * The extended-header meta prefixes (MF-3). These are git's per-file header block (`diff --git` … first
 * `@@`): the `--- `/`+++ ` source/target lines plus the mode/similarity/rename/copy lines emitted for
 * chmod/rename/copy/new/deleted diffs. They are headers, NOT content — but ONLY outside a hunk body. The
 * parser is STATE-AWARE: a hunk-body content line like `-- foo` renders raw as `--- foo` and a `++ foo` as
 * `+++ foo`; applying these prefixes inside a hunk would mis-classify them as meta and silently drop them.
 */
const HEADER_PREFIXES = [
  "diff ",
  "index ",
  "Binary files ",
  "--- ",
  "+++ ",
  "old mode ",
  "new mode ",
  "new file mode ",
  "deleted file mode ",
  "similarity index ",
  "dissimilarity index ",
  "rename from ",
  "rename to ",
  "copy from ",
  "copy to ",
] as const;

/**
 * Parses a unified-diff blob into typed lines, faithful to unified-diff structure (MF-3). Strips a
 * trailing `\r` per line (CRLF diffs) BEFORE classifying, then drops the single leading marker column for
 * add/del/context display. An empty blob yields no rows.
 *
 * STATE MACHINE: a file section's header runs from `diff --git ` (or the first header line) until the
 * first `@@` hunk header. Header prefixes classify as meta ONLY when `inHunk` is false. An `@@ ` line is
 * meta and opens a hunk (`inHunk = true`); a new `diff --git ` resets `inHunk = false`. While `inHunk`,
 * EVERY line is classified by its FIRST char only — `+`→add, `-`→del, ` `/empty→context, `\`→the
 * `\ No newline at end of file` marker (meta) — so a `-- foo`/`++ foo` content line is never swallowed by
 * the `--- `/`+++ ` header prefixes.
 *
 * The blob is split on `\n`; a trailing empty segment (from a final newline) is dropped so a normal diff
 * doesn't render a spurious empty context row at the end.
 */
export function parseUnifiedDiff(unifiedDiff: string): DiffLine[] {
  if (unifiedDiff.length === 0) return [];
  const segments = unifiedDiff.split("\n");
  if (segments.length > 0 && segments[segments.length - 1] === "") segments.pop();

  const lines: DiffLine[] = [];
  let inHunk = false;
  for (const segment of segments) {
    const line = segment.endsWith("\r") ? segment.slice(0, -1) : segment;
    if (line.startsWith("@@")) {
      inHunk = true;
      lines.push({ kind: "meta", text: line });
    } else if (line.startsWith("diff ")) {
      // A new file section: leave the previous hunk and re-enter the header block.
      inHunk = false;
      lines.push({ kind: "meta", text: line });
    } else if (!inHunk) {
      // Header block: the extended-header prefixes are meta here; anything else (shouldn't occur in
      // well-formed git output) falls through to first-char classification.
      lines.push(HEADER_PREFIXES.some((prefix) => line.startsWith(prefix)) ? { kind: "meta", text: line } : classifyBody(line));
    } else {
      lines.push(classifyBody(line));
    }
  }
  return lines;
}

/** Classifies a hunk-body line by its FIRST char only, stripping the single leading marker column. */
function classifyBody(line: string): DiffLine {
  if (line.startsWith("+")) return { kind: "add", text: line.slice(1) };
  if (line.startsWith("-")) return { kind: "del", text: line.slice(1) };
  if (line.startsWith("\\")) return { kind: "meta", text: line }; // `\ No newline at end of file`
  // A context line keeps its content sans the leading marker column; an empty line has no column to strip.
  return { kind: "context", text: line.startsWith(" ") ? line.slice(1) : line };
}

/**
 * Maps a diff path's file extension to a highlight.js language id (D-5/MF-2): ONE language detected once
 * for the whole diff, never per-line auto-detection. An unmapped extension returns null → the renderer
 * shows escaped plaintext. The renderer ALSO guards on `hljs.getLanguage` (mirror Prose.tsx:37), so a
 * mapped-but-unregistered language still degrades to plaintext.
 */
const EXTENSION_LANGUAGES: Record<string, string> = {
  ts: "typescript",
  tsx: "typescript",
  js: "javascript",
  jsx: "javascript",
  mjs: "javascript",
  cjs: "javascript",
  json: "json",
  py: "python",
  rb: "ruby",
  go: "go",
  rs: "rust",
  java: "java",
  kt: "kotlin",
  kts: "kotlin",
  c: "c",
  h: "c",
  cpp: "cpp",
  cc: "cpp",
  hpp: "cpp",
  cs: "csharp",
  php: "php",
  swift: "swift",
  scala: "scala",
  sh: "bash",
  bash: "bash",
  zsh: "bash",
  yml: "yaml",
  yaml: "yaml",
  toml: "ini",
  ini: "ini",
  sql: "sql",
  css: "css",
  scss: "scss",
  less: "less",
  html: "xml",
  xml: "xml",
  md: "markdown",
  markdown: "markdown",
};

export function languageForPath(path: string): string | null {
  const lastDot = path.lastIndexOf(".");
  if (lastDot < 0 || lastDot === path.length - 1) return null;
  const ext = path.slice(lastDot + 1).toLowerCase();
  return EXTENSION_LANGUAGES[ext] ?? null;
}
