import hljs from "highlight.js/lib/common";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useEffect, useMemo, useState } from "react";
import { ApiError } from "../api/client";
import { diffQuery, historyQuery, pageByPathQuery } from "../api/queries";
import type { CommitDto, DiffResponse } from "../api/types";
import { languageForPath, MAX_DIFF_RENDER_CHARS, parseUnifiedDiff, type DiffLine } from "../lib/unifiedDiff";
import { NotFoundView } from "./NotFound";

/**
 * Mirrors the server's `DEFAULT_HISTORY_LIMIT` (HistoryRoutes.kt): `/history` returns at most this many
 * newest-first commits. A list at exactly this length is (almost certainly) truncated, so we surface a
 * non-blocking hint — pagination is deliberately out of W7 scope.
 */
const HISTORY_LIMIT = 100;

/**
 * The `?mode=history` surface (W7, D-1): a per-page commit list (newest-first) + a two-commit unified
 * diff. Consumes the W5 read API (`/history`, `/diff`) — read-only, no server change. Mirrors
 * `EditorPage`'s shape: resolve the splat through `pageByPathQuery` (loading/error/404 states), then
 * render the id-keyed history view. The footer affordance that LINKS here is gated elsewhere on the
 * already-loaded `PageResponse.commit` (PageView.DocFooter) — this VIEW is the only place that fetches
 * `/history`, so a plain read view fires no git subprocess (MF-1).
 */
export function History({ path }: { path: string }) {
  const page = useQuery(pageByPathQuery(path));

  if (page.isPending) {
    return (
      <p className="py-16 text-center text-faint" data-pb-loading>
        Loading…
      </p>
    );
  }
  if (page.isError) {
    if (page.error instanceof ApiError && (page.error.isNotFound || page.error.status === 400)) return <NotFoundView />;
    return (
      <div className="py-16 text-center" data-pb-error>
        <h1 className="text-2xl font-bold text-ink">Something went wrong</h1>
        <p className="mt-3 text-muted">{page.error.message}</p>
      </div>
    );
  }

  // Key by id so a navigation to a different page remounts the view with a fresh selection/diff.
  return <HistoryView key={page.data.id} id={page.data.id} path={path} />;
}

function HistoryView({ id, path }: { id: string; path: string }) {
  const history = useQuery(historyQuery(id));
  // The diff is driven by the FULL shas (D-5) — never a display-truncated form. `from` is the OLDER
  // commit (later in the newest-first array), `to` the NEWER, so the diff always reads old→new.
  const [from, setFrom] = useState<string | null>(null);
  const [to, setTo] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const diff = useQuery(diffQuery(id, from, to));

  // A diff 404 means a ref vanished under us (history rewrite). The client sent valid list-sourced SHAs,
  // so this is NOT a user error: refresh the commit list, clear the selection, and show a transient notice.
  useEffect(() => {
    if (diff.error instanceof ApiError && diff.error.isNotFound) {
      setFrom(null);
      setTo(null);
      setNotice("History changed — refreshed the commit list.");
      void history.refetch();
    }
  }, [diff.error, history]);

  const back = (
    <Link to="/docs/$" params={{ _splat: path }} search={{}} className="pb-history-back" data-pb-history-back>
      ← Back to the page
    </Link>
  );

  if (history.isPending) {
    return (
      <div className="pb-history" data-pb-history>
        {back}
        <p className="py-16 text-center text-faint" data-pb-loading>
          Loading…
        </p>
      </div>
    );
  }
  if (history.isError) {
    return (
      <div className="pb-history" data-pb-history>
        {back}
        <p className="py-16 text-center text-muted" data-pb-history-error>
          Couldn’t load the page history. {history.error.message}
        </p>
      </div>
    );
  }

  // State 1 (D-4): Git mode off — there is no history feature here at all.
  if (!history.data.git_enabled) {
    return (
      <div className="pb-history" data-pb-history>
        {back}
        <p className="py-16 text-center text-muted" data-pb-history-disabled>
          Page history requires Git mode.
        </p>
      </div>
    );
  }

  const commits = history.data.commits;

  // State 2 (D-4): Git on, no commits yet — distinct from Git off; reachable by a direct ?mode=history URL.
  if (commits.length === 0) {
    return (
      <div className="pb-history" data-pb-history>
        {back}
        <p className="py-16 text-center text-muted" data-pb-history-empty>
          No history yet — this page hasn’t been committed.
        </p>
      </div>
    );
  }

  // State 3: the commit list + diff. `from`/`to` are pinned by list INDEX (later-in-array = older = `from`),
  // so the diff reads old→new regardless of click order.
  function toggle(sha: string) {
    setNotice(null);
    if (from === sha || to === sha) {
      // Unselect.
      if (from === sha) setFrom(null);
      else setTo(null);
      return;
    }
    const fromIndex = from === null ? -1 : commits.findIndex((c) => c.sha === from);
    const toIndex = to === null ? -1 : commits.findIndex((c) => c.sha === to);
    const clickedIndex = commits.findIndex((c) => c.sha === sha);
    const selected = [fromIndex, toIndex].filter((i) => i >= 0);
    selected.push(clickedIndex);
    // Keep the two MOST-RECENTLY-relevant picks; if three, drop the oldest selection so the latest click sticks.
    const picks = selected.slice(-2);
    // Pin from/to by index: the higher index (further down the newest-first list) is OLDER → `from`.
    if (picks.length === 2) {
      const [a, b] = picks;
      const older = Math.max(a, b);
      const newer = Math.min(a, b);
      setFrom(commits[older].sha);
      setTo(commits[newer].sha);
    } else {
      // First pick: hold it as `to`; the diff stays disabled until a second pick (D-5 single-pick edge).
      setFrom(null);
      setTo(commits[picks[0]].sha);
    }
  }

  const selectedShas = new Set([from, to].filter((s): s is string => s !== null));

  return (
    <div className="pb-history" data-pb-history>
      {back}
      <h1 className="pb-history-title text-2xl font-bold text-ink">Page history</h1>
      {notice && (
        <p className="pb-history-notice text-sm text-muted" data-pb-history-notice>
          {notice}
        </p>
      )}
      <ol className="pb-history-list" data-pb-history-list>
        {commits.map((commit) => (
          <CommitRow key={commit.sha} commit={commit} selected={selectedShas.has(commit.sha)} onToggle={() => toggle(commit.sha)} />
        ))}
      </ol>
      {commits.length >= HISTORY_LIMIT && (
        <p className="pb-history-truncated text-sm text-faint" data-pb-history-truncated>
          Showing the {HISTORY_LIMIT} most recent commits.
        </p>
      )}
      <DiffPane diff={diff.data} pending={diff.isPending} error={diff.error} selectedCount={selectedShas.size} />
    </div>
  );
}

/** Shortens a sha for DISPLAY only (data-pb-commit-sha / the visible label). The diff payload is the full sha (D-5). */
function shortSha(sha: string): string {
  return sha.slice(0, 7);
}

/**
 * Formats an ISO-8601 timestamp for display, defensively (D-8): a malformed value falls back to the raw
 * string (never "Invalid Date", never throws). The raw ISO rides a `title`/`dateTime` attr so tests stay
 * timezone-independent.
 */
function formatTime(iso: string): string {
  return Number.isNaN(Date.parse(iso)) ? iso : new Date(iso).toLocaleString();
}

function CommitRow({ commit, selected, onToggle }: { commit: CommitDto; selected: boolean; onToggle: () => void }) {
  return (
    <li className="pb-commit" data-pb-commit data-pb-commit-sha={shortSha(commit.sha)} data-pb-commit-selected={selected ? "" : undefined}>
      <button type="button" className="pb-commit-button" aria-pressed={selected} onClick={onToggle}>
        <span className="pb-commit-msg">{commit.message.split("\n")[0] || "(no message)"}</span>
        <span className="pb-commit-meta">
          <span className="pb-commit-author">{commit.author_name}</span>
          <time className="pb-commit-time" dateTime={commit.author_time} title={commit.author_time}>
            {formatTime(commit.author_time)}
          </time>
          <span className="pb-commit-sha font-mono">{shortSha(commit.sha)}</span>
        </span>
      </button>
    </li>
  );
}

/**
 * The diff side of the view, a single loading/error/empty/loaded machine. Order matters: selection-count
 * gates FIRST (when `diffQuery` is disabled, `isPending` is true but the right copy is "select two", not
 * "loading"). A **404** is NOT handled here — the parent effect catches it (clears the selection, refetches
 * the list, shows the transient notice), which drops us back to the `selectedCount < 2` hint. Any OTHER
 * diff error (400/500) renders a generic, stable notice rather than hanging on "Loading diff…" forever.
 */
function DiffPane({ diff, pending, error, selectedCount }: { diff: DiffResponse | undefined; pending: boolean; error: unknown; selectedCount: number }) {
  if (selectedCount < 2) {
    return (
      <div className="pb-diff-pane" data-pb-diff-pane>
        <p className="text-sm text-faint" data-pb-diff-hint>
          Select two commits to compare.
        </p>
      </div>
    );
  }
  // A 404 is the parent effect's concern (it clears the selection → we never reach here with a 404 still set).
  // Everything else is a real failure: surface it instead of an infinite spinner.
  if (error && !(error instanceof ApiError && error.isNotFound)) {
    return (
      <div className="pb-diff-pane" data-pb-diff-pane>
        <p className="text-sm text-muted" data-pb-diff-error>
          Couldn’t load this diff. {error instanceof Error ? error.message : ""}
        </p>
      </div>
    );
  }
  if (pending || !diff) {
    return (
      <div className="pb-diff-pane" data-pb-diff-pane>
        <p className="text-sm text-faint" data-pb-loading>
          Loading diff…
        </p>
      </div>
    );
  }
  return (
    <div className="pb-diff-pane" data-pb-diff-pane>
      <Diff diff={diff} />
    </div>
  );
}

function Diff({ diff }: { diff: DiffResponse }) {
  // The size-cap lives INSIDE the memo so the hook is called UNCONDITIONALLY (Rules of Hooks): a `Diff`
  // instance that switches between an oversized and a normal diff on the same mount must keep a stable hook
  // count. Parse + per-line highlight is the only expensive work here, and it's skipped for the cap state, so a
  // giant diff still never parses. Memoized on (unified_diff, path) so a transient parent re-render — a window
  // refocus, a sibling state change — can't re-run the pass.
  const rows = useMemo(() => {
    if (diff.unified_diff.length > MAX_DIFF_RENDER_CHARS) return "too-large" as const;
    // ONE language detected once from the diff path's extension (MF-2) — never per-line auto-detection.
    // Guard on hljs.getLanguage exactly like Prose.tsx:37; a missing/unregistered language → escaped plaintext.
    const detected = languageForPath(diff.path);
    const language = detected && hljs.getLanguage(detected) ? detected : null;
    return parseUnifiedDiff(diff.unified_diff).map((line) => ({
      line,
      // hljs output is generated from the line's TEXT (no markup passes through), preserving the
      // no-injection property Prose relies on (Prose.tsx:41-45). Meta rows are never highlighted.
      html: line.kind !== "meta" && language !== null ? hljs.highlight(line.text, { language, ignoreIllegals: true }).value : null,
    }));
  }, [diff.unified_diff, diff.path]);

  // A giant diff can never freeze the browser: render the cap state instead of megabytes of rows.
  if (rows === "too-large") {
    return (
      <div className="pb-diff pb-diff-too-large" data-pb-diff data-pb-diff-too-large>
        <p className="text-sm text-muted">
          This diff is too large to render here ({diff.from.slice(0, 7)} → {diff.to.slice(0, 7)}). View it locally with{" "}
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
