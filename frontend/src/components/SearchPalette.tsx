import { useQuery } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { searchQuery, SEARCH_LIMIT, SEARCH_MAX_QUERY, treeQuery } from "../api/queries";
import { searchRows, searchSpaceLabel, type SearchRow } from "../lib/searchResults";
import { fuzzyRank, type FuzzyCandidate } from "../lib/fuzzy";
import { permalinkOf } from "../lib/permalink";
import { diagrams, pageHref, pages, type QuickSwitchEntry } from "../lib/tree";
import { useDebounced } from "../lib/useDebounced";
import { LISTBOX_ID, optionId, SearchList } from "./SearchList";

/** Keep instant matches compact enough that content results remain nearby. */
export const QUICK_SWITCH_MAX = 8;
const DEBOUNCE_MS = 150;

export function SearchPalette({ disabled = false }: { disabled?: boolean }) {
  const [isOpen, setIsOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const router = useRouter();

  const close = useCallback(() => setIsOpen(false), []);

  const reopen = useCallback(() => {
    if (disabled) return;
    openerRef.current = (document.activeElement as HTMLElement | null) ?? null;
    setIsOpen(true);
  }, [disabled]);

  useEffect(() => { if (disabled) setIsOpen(false); }, [disabled]);

  // Cmd/Ctrl+K toggles; a `pb:search-open` custom event (header trigger) opens. The
  // shortcut always fully closes from anywhere in the dialog.
  const isOpenRef = useRef(isOpen);
  isOpenRef.current = isOpen;
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!event.defaultPrevented && !event.shiftKey && !event.altKey && (event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
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
  return <PaletteBody {...{ inputRef, overlayRef, close, router }} />;
}

function PaletteBody({ inputRef, overlayRef, close, router }: {
  inputRef: React.RefObject<HTMLInputElement | null>;
  overlayRef: React.RefObject<HTMLDivElement | null>;
  close: () => void;
  router: ReturnType<typeof useRouter>;
}) {
  const [rawQuery, setRawQuery] = useState("");
  const [scope, setScope] = useState<string | null>(null);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  // The shell owns tree acquisition; this observer never starts or refreshes a request.
  const tree = useQuery({ ...treeQuery, enabled: false });
  const roots = tree.data?.roots ?? [];
  const scopeUnavailable = scope !== null && !roots.some((root) => root.root === scope && root.available);
  const trimmed = rawQuery.trim().slice(0, SEARCH_MAX_QUERY);
  const debounced = useDebounced(trimmed, DEBOUNCE_MS);
  const queryReady = trimmed.length > 0 && trimmed === debounced && !scopeUnavailable;
  const fullText = useQuery(searchQuery(queryReady ? debounced : "", SEARCH_LIMIT, 0, scope));
  const candidates = useMemo(() => {
    const available = (tree.data?.roots ?? []).filter((root) => root.available && (scope === null || root.root === scope));
    return [...pages(available), ...diagrams(available)].map((entry): FuzzyCandidate<QuickSwitchEntry> => {
      const node = "page" in entry ? entry.page : entry.diagram;
      return { label: node.title, hint: node.path, node: entry };
    });
  }, [tree.data, scope]);
  const quick = (trimmed ? fuzzyRank(trimmed, candidates).map((item) => item.candidate) : candidates).slice(0, QUICK_SWITCH_MAX).map((item) => item.node);
  const hits = queryReady && !fullText.isError ? (fullText.data?.hits ?? []).filter((hit) =>
    (scope === null || hit.root === scope) && !roots.some((root) => root.root === hit.root && !root.available)) : [];
  const rows = searchRows(quick, hits, roots);
  const active = rows.find((row) => row.key === selectedKey);
  const activeId = active ? optionId(active.key) : undefined;
  const rowOrder = JSON.stringify(rows.map((row) => row.key));
  useEffect(() => {
    if (selectedKey !== null && !rows.some((row) => row.key === selectedKey)) setSelectedKey(null);
  }, [rows, selectedKey]);
  useLayoutEffect(() => { if (activeId) document.getElementById(activeId)?.scrollIntoView({ block: "nearest" }); }, [activeId, rowOrder]);

  function activate(row: SearchRow) {
    const href = row.kind === "jump"
      ? ("page" in row.entry ? pageHref(row.root, row.entry.page) : row.entry.diagram.url)
      : (row.hit.url ?? permalinkOf(row.root, row.hit.page_id)) + (row.hit.heading_id ? `#${row.hit.heading_id}` : "");
    router.history.push(href);
    close();
  }
  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); close(); return; }
    if (event.key === "Tab") {
      const controls = [...overlayRef.current!.querySelectorAll<HTMLElement>("input, button:not(:disabled)")];
      const index = controls.indexOf(document.activeElement as HTMLElement);
      event.preventDefault();
      controls[(index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length]?.focus();
      return;
    }
    if (event.target !== inputRef.current) return;
    const index = active ? rows.indexOf(active) : -1;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const next = index < 0 ? (event.key === "ArrowDown" ? 0 : rows.length - 1)
        : Math.max(0, Math.min(rows.length - 1, index + (event.key === "ArrowDown" ? 1 : -1)));
      setSelectedKey(rows[next]?.key ?? null);
    } else if (event.key === "Enter") {
      event.preventDefault();
      const row = active ?? rows[0];
      if (row) activate(row);
    }
  }
  const status = scopeUnavailable || (queryReady && fullText.isError) ? "error"
    : !trimmed ? "idle" : !queryReady || (fullText.isFetching && !fullText.data) ? "loading"
    : fullText.data && hits.length === 0 ? "empty" : "ready";
  const errorMessage = scopeUnavailable ? "This space is unavailable. Choose another space to continue."
    : fullText.error instanceof ApiError ? fullText.error.message : fullText.error?.message;

  return (
    <div ref={overlayRef} className="pb-search fixed inset-0 z-50 flex items-start justify-center p-4 pt-[8vh]" data-pb-search
      onMouseDown={(event) => { if (event.target === overlayRef.current) close(); }}>
      <div role="dialog" aria-modal="true" aria-label="Search your docs" onKeyDown={onKeyDown}
        className="pb-search-panel w-full overflow-hidden rounded-xl border border-edge bg-raised shadow-lg" data-pb-search-panel="">
        <input ref={inputRef} type="text" role="combobox" aria-label="Search your docs" aria-expanded={true}
          aria-controls={LISTBOX_ID} aria-activedescendant={activeId} aria-autocomplete="list" value={rawQuery} maxLength={SEARCH_MAX_QUERY}
          onChange={(event) => { setRawQuery(event.target.value); setSelectedKey(null); }} placeholder="Search your docs…" data-pb-search-input=""
          className="w-full bg-transparent px-4 py-4 text-ink outline-none placeholder:text-faint" />
        <div role="group" aria-label="Search scope" className="pb-search-scopes flex flex-wrap gap-1.5 border-b border-edge px-4 py-3">
          {[null, ...roots.map((root) => root.root)].map((root) => {
            const unavailable = root !== null && !roots.some((entry) => entry.root === root && entry.available);
            return <button key={JSON.stringify(["scope", root])} type="button" disabled={unavailable} aria-pressed={scope === root}
              className="pb-search-scope" onClick={() => { setScope(root); setSelectedKey(null); }}>
              {root === null ? "All spaces" : searchSpaceLabel(roots, root)}{unavailable ? " (unavailable)" : ""}
            </button>;
          })}
        </div>
        <SearchList {...{ rows, roots, status, errorMessage }} showSpaceGroups={scope === null} query={trimmed} selectedKey={active?.key ?? null} onSelect={setSelectedKey} onActivate={activate} />
        <div className="pb-search-foot flex items-center gap-[18px] border-t border-edge px-4 py-2 font-sans text-xs text-faint" data-pb-search-foot="" aria-hidden="true">
          <span><b>↑↓</b> move</span><span><b>↵</b> open</span><span><b>tab</b> spaces</span><span><b>esc</b> close</span>
        </div>
      </div>
    </div>
  );
}
