import { useQuery } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { searchQuery, SEARCH_MAX_QUERY, treeQuery } from "../api/queries";
import type { SearchHit, TreePage } from "../api/types";
import { fuzzyRank, type FuzzyCandidate } from "../lib/fuzzy";
import { pageHref, pages } from "../lib/tree";
import { useDebounced } from "../lib/useDebounced";
import { LISTBOX_ID, optionId, SearchList } from "./SearchList";

/** Stage-1 quick-switcher is capped so the bridge is always within a few ArrowDowns (UI-only). */
export const QUICK_SWITCH_MAX = 8;
const DEBOUNCE_MS = 150;

type Stage = "jump" | "search";

/**
 * The two-stage Cmd/Ctrl+K palette (ADR-0005). Stage 1 is the zero-network quick-switcher
 * over the cached tree; the bridge row crosses to Stage 2, full-text only. Mounted once in
 * `Shell`. Selection is a plain per-stage integer; Esc is stage-aware; outside-click always
 * closes. Combobox a11y (real option ids → `aria-activedescendant`, focus-trap, scroll-lock,
 * focus-return) is hand-rolled — no new dependency.
 */
export function SearchPalette() {
  const [isOpen, setIsOpen] = useState(false);
  const [stage, setStage] = useState<Stage>("jump");
  const [rawQuery, setRawQuery] = useState("");
  const [selectedIndex, setSelectedIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const router = useRouter();

  const close = useCallback(() => setIsOpen(false), []);

  const reopen = useCallback(() => {
    openerRef.current = (document.activeElement as HTMLElement | null) ?? null;
    setStage("jump");
    setRawQuery("");
    setSelectedIndex(0);
    setIsOpen(true);
  }, []);

  // Cmd/Ctrl+K toggles; a `pb:search-open` custom event (header trigger) opens. The
  // shortcut always fully closes regardless of stage.
  const isOpenRef = useRef(isOpen);
  isOpenRef.current = isOpen;
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        if (isOpenRef.current) close();
        else reopen();
      }
    };
    const onOpenEvent = () => reopen();
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pb:search-open", onOpenEvent);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pb:search-open", onOpenEvent);
    };
  }, [reopen, close]);

  // Declarative scroll-lock + focus-return (Resolution 4): cleanup restores both, so a
  // navigate-away close (Enter on a hit unmounts the palette mid-effect) still releases the
  // scroll-lock and returns focus — an imperative restore in the close handler would not.
  useEffect(() => {
    if (!isOpen) return;
    const opener = openerRef.current;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    inputRef.current?.focus();
    return () => {
      document.body.style.overflow = previousOverflow;
      opener?.focus?.();
    };
  }, [isOpen]);

  if (!isOpen) return null;
  return <PaletteBody {...{ stage, setStage, rawQuery, setRawQuery, selectedIndex, setSelectedIndex, inputRef, overlayRef, close, router }} />;
}

function PaletteBody({
  stage,
  setStage,
  rawQuery,
  setRawQuery,
  selectedIndex,
  setSelectedIndex,
  inputRef,
  overlayRef,
  close,
  router,
}: {
  stage: Stage;
  setStage: (s: Stage) => void;
  rawQuery: string;
  setRawQuery: (q: string) => void;
  selectedIndex: number;
  setSelectedIndex: (updater: number | ((prev: number) => number)) => void;
  inputRef: React.RefObject<HTMLInputElement | null>;
  overlayRef: React.RefObject<HTMLDivElement | null>;
  close: () => void;
  router: ReturnType<typeof useRouter>;
}) {
  // Passive cache reader — the app shell owns fetching the tree. `refetchOnMount: false` keeps
  // opening the palette a true zero-network action even after `treeQuery`'s staleTime elapses; on
  // a cold cache it simply shows no Stage-1 matches (the bridge row) until the shell's fetch fills.
  const tree = useQuery({ ...treeQuery, refetchOnMount: false });

  // ---- Stage 1: quick-switcher (synchronous, zero network) ----
  const trimmed = rawQuery.trim();
  const candidates = useMemo<FuzzyCandidate[]>(() => {
    if (!tree.data) return [];
    return pages(tree.data.root).map((node: TreePage) => ({ node, label: node.title, hint: node.path }));
  }, [tree.data]);

  const jumpPages = useMemo<TreePage[]>(() => {
    if (!trimmed) return candidates.map((c) => c.node).slice(0, QUICK_SWITCH_MAX);
    return fuzzyRank(trimmed, candidates)
      .slice(0, QUICK_SWITCH_MAX)
      .map((m) => m.candidate.node);
  }, [trimmed, candidates]);

  const bridgeIndex = jumpPages.length; // the bridge is the last Stage-1 row
  const bridgeEnabled = trimmed.length > 0;

  // ---- Stage 2: full-text (debounced, query-keyed) ----
  const clamped = trimmed.slice(0, SEARCH_MAX_QUERY);
  const debounced = useDebounced(clamped, DEBOUNCE_MS);
  const fullText = useQuery(searchQuery(stage === "search" ? debounced : ""));
  const hits: SearchHit[] = fullText.data?.hits ?? [];
  const status: "loading" | "empty" | "error" | "ready" = fullText.isError
    ? "error"
    : fullText.isFetching && !fullText.data
      ? "loading"
      : fullText.data && fullText.data.hits.length === 0
        ? "empty"
        : "ready";
  const errorMessage = fullText.error instanceof ApiError ? fullText.error.message : fullText.error?.message;

  // Reset selection on stage-enter/return and on every query change (explicit Resolution-4
  // rule). Stage 1 resets to -1 = "no row actively selected" (criterion 22: Enter then
  // activates the bridge, the snappy "type + Enter → full-text" path); the first ArrowDown
  // steps onto row 0. Stage 2 resets to 0 (the top hit is the natural default).
  const noSelection = stage === "jump" ? -1 : 0;
  useEffect(() => setSelectedIndex(noSelection), [stage, trimmed, noSelection, setSelectedIndex]);

  const maxIndex = stage === "jump" ? bridgeIndex : Math.max(hits.length - 1, 0);
  const activeId =
    selectedIndex < 0 ? undefined : stage === "jump" ? optionId("jump", selectedIndex) : hits.length > 0 ? optionId("search", selectedIndex) : undefined;

  // Keep the actively-selected row visible: with SEARCH_LIMIT rows in a max-h-80 scroll
  // box, arrowing past the fold would leave it off-screen. `block: "nearest"` scrolls the
  // minimum needed (no janky recentering); guarded on activeId so the Stage-1 `-1` default
  // (no row selected) scrolls nothing. Layout effect keyed on index+stage → fires post-render.
  useLayoutEffect(() => {
    if (!activeId) return;
    document.getElementById(activeId)?.scrollIntoView({ block: "nearest" });
  }, [activeId]);

  const navigateToPage = useCallback(
    (page: TreePage) => {
      router.history.push(pageHref(page));
      close();
    },
    [router, close],
  );

  const navigateToHit = useCallback(
    (hit: SearchHit) => {
      const base = hit.url ?? `/p/${hit.page_id}`;
      router.history.push(hit.heading_id ? `${base}#${hit.heading_id}` : base);
      close();
    },
    [router, close],
  );

  const activateBridge = useCallback(() => {
    if (!bridgeEnabled) return; // empty query: the server refuses a blank `q`, so the bridge is inert
    setStage("search");
  }, [bridgeEnabled, setStage]);

  const enterAt = useCallback(
    (index: number) => {
      if (stage === "jump") {
        // No active row (-1) or the bridge row → activate the bridge (the snappy path).
        if (index < 0 || index >= bridgeIndex) {
          activateBridge();
          return;
        }
        navigateToPage(jumpPages[index]);
        return;
      }
      if (hits.length > 0 && index < hits.length) navigateToHit(hits[index]);
    },
    [stage, bridgeIndex, jumpPages, hits, activateBridge, navigateToPage, navigateToHit],
  );

  const returnToJump = useCallback(() => {
    setStage("jump");
    setSelectedIndex(-1);
  }, [setStage, setSelectedIndex]);

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setSelectedIndex((prev) => Math.min(prev + 1, maxIndex)); // clamp, no wrap; -1 → 0 first step
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setSelectedIndex((prev) => Math.max(prev - 1, stage === "jump" ? -1 : 0));
    } else if (event.key === "Enter") {
      event.preventDefault();
      enterAt(selectedIndex);
    } else if (event.key === "Escape") {
      event.preventDefault();
      // Stage-aware: Stage 2 returns to Stage 1; Stage 1 closes. NOT the outside-click handler.
      if (stage === "search") returnToJump();
      else close();
    } else if (event.key === "Backspace" && stage === "search" && rawQuery.length === 0) {
      event.preventDefault();
      returnToJump();
    } else if (event.key === "Tab") {
      // Focus-trap: the only focusable control is the input, so keep focus on it.
      event.preventDefault();
      inputRef.current?.focus();
    }
  };

  return (
    <div
      ref={overlayRef}
      className="pb-search fixed inset-0 z-50 flex items-start justify-center bg-surface/60 p-4 pt-[12vh]"
      data-pb-search=""
      data-pb-search-stage={stage}
      onMouseDown={(event) => {
        // Outside-click (the scrim) always fully closes, regardless of stage.
        if (event.target === overlayRef.current) close();
      }}
    >
      <div className="w-full max-w-xl overflow-hidden rounded-xl border border-edge bg-raised shadow-lg" onMouseDown={(e) => e.stopPropagation()}>
        {stage === "search" && (
          <div className="flex items-center gap-2 border-b border-edge px-3 py-1.5 text-xs text-muted" data-pb-search-stage-label="">
            <button
              type="button"
              onMouseDown={(event) => {
                event.preventDefault();
                returnToJump();
              }}
              className="rounded px-1.5 py-0.5 text-muted hover:bg-hovered hover:text-ink"
              aria-label="Back to quick switcher"
            >
              ← Back
            </button>
            <span className="font-medium text-ink">Search results</span>
          </div>
        )}
        <input
          ref={inputRef}
          type="text"
          role="combobox"
          aria-expanded={true}
          aria-controls={LISTBOX_ID}
          aria-activedescendant={activeId}
          aria-autocomplete="list"
          value={rawQuery}
          maxLength={SEARCH_MAX_QUERY}
          onChange={(event) => setRawQuery(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder={stage === "jump" ? "Jump to a page, or search all docs…" : "Search all docs…"}
          data-pb-search-input=""
          className="w-full bg-transparent px-4 py-3 text-ink outline-none placeholder:text-faint"
        />
        <SearchList
          stage={stage}
          jumpPages={jumpPages}
          query={trimmed}
          bridgeEnabled={bridgeEnabled}
          bridgeIndex={bridgeIndex}
          hits={hits}
          status={status}
          errorMessage={errorMessage}
          selectedIndex={selectedIndex}
          onSelect={setSelectedIndex}
          onActivate={enterAt}
          onActivateBridge={activateBridge}
        />
      </div>
    </div>
  );
}
