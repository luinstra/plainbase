import type { SearchHit, TreePage } from "../api/types";
import { JumpToItem } from "./JumpToItem";
import { SearchResultItem } from "./SearchResultItem";

export const LISTBOX_ID = "pb-search-listbox";

/** A row's real DOM id — `aria-activedescendant` must point at one of these (Resolution 4). */
export function optionId(stage: "jump" | "search", index: number): string {
  return `pb-search-opt-${stage}-${index}`;
}

/**
 * The single visible list for the active stage (`role="listbox"`). Stage 1 renders jump
 * rows + the bridge; Stage 2 renders hit rows + non-selectable status rows. Only one list
 * exists at a time, so selection is a plain per-stage integer (ADR-0005).
 */
export function SearchList({
  stage,
  // Stage 1
  jumpPages,
  query,
  bridgeEnabled,
  bridgeIndex,
  // Stage 2
  hits,
  status,
  errorMessage,
  // shared
  selectedIndex,
  onSelect,
  onActivate,
  onActivateBridge,
}: {
  stage: "jump" | "search";
  jumpPages?: TreePage[];
  query?: string;
  bridgeEnabled?: boolean;
  bridgeIndex?: number;
  hits?: SearchHit[];
  status?: "loading" | "empty" | "error" | "ready";
  errorMessage?: string;
  selectedIndex: number;
  onSelect: (index: number) => void;
  onActivate: (index: number) => void;
  onActivateBridge: () => void;
}) {
  return (
    <ul id={LISTBOX_ID} role="listbox" aria-label="Search results" className="max-h-80 overflow-y-auto p-1.5" data-pb-search-list>
      {stage === "jump" ? (
        <>
          {(jumpPages ?? []).map((page, index) => (
            <JumpToItem
              key={page.id}
              page={page}
              id={optionId("jump", index)}
              active={index === selectedIndex}
              onActivate={() => onActivate(index)}
              onHover={() => onSelect(index)}
            />
          ))}
          <li
            id={optionId("jump", bridgeIndex ?? 0)}
            role="option"
            aria-selected={(bridgeIndex ?? -1) === selectedIndex}
            aria-disabled={bridgeEnabled ? undefined : true}
            data-pb-search-bridge=""
            data-pb-search-active={(bridgeIndex ?? -1) === selectedIndex ? "" : undefined}
            onMouseDown={(event) => {
              event.preventDefault();
              onActivateBridge();
            }}
            onMouseMove={() => onSelect(bridgeIndex ?? 0)}
            className={
              (bridgeIndex ?? -1) === selectedIndex
                ? "mt-1.5 flex cursor-pointer items-center gap-2 rounded border-t border-edge bg-active px-3 py-2 pt-3 text-sm text-ink"
                : "mt-1.5 flex cursor-pointer items-center gap-2 rounded border-t border-edge px-3 py-2 pt-3 text-sm text-muted hover:bg-hovered"
            }
          >
            <span aria-hidden="true" className="text-faint">
              ⌕
            </span>
            <span>{query ? <>Search all docs for “{query}” ↵</> : <>Search all docs…</>}</span>
          </li>
        </>
      ) : (
        <>
          {status === "loading" && (
            <li data-pb-search-loading="" className="flex items-center gap-2 px-3 py-2 text-sm text-muted" aria-live="polite">
              <span aria-hidden="true" className="pb-search-spinner" />
              Searching…
            </li>
          )}
          {status === "empty" && (
            <li data-pb-search-empty="" className="px-3 py-2 text-sm text-muted">
              No matches
            </li>
          )}
          {status === "error" && (
            <li data-pb-search-error="" className="px-3 py-2 text-sm text-link-broken">
              {errorMessage}
            </li>
          )}
          {(hits ?? []).map((hit, index) => (
            <SearchResultItem
              key={`${hit.page_id}:${hit.heading_id ?? ""}:${index}`}
              hit={hit}
              id={optionId("search", index)}
              active={index === selectedIndex}
              onActivate={() => onActivate(index)}
              onHover={() => onSelect(index)}
            />
          ))}
        </>
      )}
    </ul>
  );
}
