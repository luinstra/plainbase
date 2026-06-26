import hljs from "highlight.js/lib/common";
import { useMemo } from "react";
import { languageForPath, MAX_DIFF_RENDER_CHARS, parseUnifiedDiff, type DiffLine } from "../lib/unifiedDiff";

/**
 * The shared token-styled unified-diff renderer (extracted from History — F7/P4): it renders a raw `git diff`
 * blob VERBATIM. Both the W7 history surface (a two-commit delta) and the P4 review detail (the server-stored
 * proposal diff) consume it — neither re-derives the diff. Keyed on `{ unifiedDiff, path }` so a parent
 * re-render can't re-run the parse/highlight pass.
 *
 * [tooLargeLabel] customizes the cap notice: History passes the from→to shas, the review detail has none and
 * omits it (the generic message). The `data-pb-diff*` selectors are a public/stable API — guarded by
 * history.test.tsx and the review tests.
 */
export function DiffView({ unifiedDiff, path, tooLargeLabel }: { unifiedDiff: string; path: string; tooLargeLabel?: string }) {
  // The size-cap lives INSIDE the memo so the hook is called UNCONDITIONALLY (Rules of Hooks): a `DiffView`
  // instance that switches between an oversized and a normal diff on the same mount must keep a stable hook
  // count. Parse + per-line highlight is the only expensive work, and it's skipped for the cap state, so a giant
  // diff still never parses.
  const rows = useMemo(() => {
    if (unifiedDiff.length > MAX_DIFF_RENDER_CHARS) return "too-large" as const;
    // ONE language detected once from the diff path's extension (MF-2) — never per-line auto-detection.
    // Guard on hljs.getLanguage exactly like Prose.tsx:37; a missing/unregistered language → escaped plaintext.
    const detected = languageForPath(path);
    const language = detected && hljs.getLanguage(detected) ? detected : null;
    return parseUnifiedDiff(unifiedDiff).map((line) => ({
      line,
      // hljs output is generated from the line's TEXT (no markup passes through), preserving the
      // no-injection property Prose relies on (Prose.tsx:41-45). Meta rows are never highlighted.
      html: line.kind !== "meta" && language !== null ? hljs.highlight(line.text, { language, ignoreIllegals: true }).value : null,
    }));
  }, [unifiedDiff, path]);

  // A giant diff can never freeze the browser: render the cap state instead of megabytes of rows.
  if (rows === "too-large") {
    return (
      <div className="pb-diff pb-diff-too-large" data-pb-diff data-pb-diff-too-large>
        <p className="text-sm text-muted">
          This diff is too large to render here{tooLargeLabel ? ` (${tooLargeLabel})` : ""}. View it locally with{" "}
          <code className="font-mono">git diff</code>.
        </p>
      </div>
    );
  }

  return (
    <div className="pb-diff" data-pb-diff>
      {rows.map(({ line, html }, index) => (
        <DiffRow key={index} line={line} html={html} />
      ))}
    </div>
  );
}

/** The visually-hidden, AT-only label for each row kind, paired with the (aria-hidden) +/- visual gutter. */
const KIND_LABEL: Record<DiffLine["kind"], string | null> = { add: "Added: ", del: "Removed: ", context: null, meta: null };

function DiffRow({ line, html }: { line: DiffLine; html: string | null }) {
  const srLabel = KIND_LABEL[line.kind];
  return (
    <div className="pb-diff-line" data-pb-diff-line={line.kind}>
      {srLabel && <span className="sr-only">{srLabel}</span>}
      <span className="pb-diff-gutter" aria-hidden="true">
        {line.kind === "add" ? "+" : line.kind === "del" ? "-" : " "}
      </span>
      {html !== null ? (
        <code className="pb-diff-code hljs" dangerouslySetInnerHTML={{ __html: html }} />
      ) : (
        <code className="pb-diff-code">{line.text}</code>
      )}
    </div>
  );
}
