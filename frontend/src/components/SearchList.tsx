import { Fragment } from "react";
import type { RootTree } from "../api/types";
import { searchSpaceLabel, searchTrail, type SearchRow } from "../lib/searchResults";
import { JumpToItem } from "./JumpToItem";
import { SearchResultItem } from "./SearchResultItem";

export const LISTBOX_ID = "pb-search-listbox";
export function optionId(key: string): string {
  return `pb-search-opt-${encodeURIComponent(key)}`;
}

export function SearchList({ rows, roots, query, selectedKey, onSelect, onActivate, status, errorMessage, showSpaceGroups = true }: {
  rows: SearchRow[];
  roots: RootTree[];
  query: string;
  selectedKey: string | null;
  onSelect: (key: string) => void;
  onActivate: (row: SearchRow) => void;
  status: "idle" | "loading" | "empty" | "error" | "ready";
  errorMessage?: string;
  showSpaceGroups?: boolean;
}) {
  return (
    <ul id={LISTBOX_ID} role="listbox" aria-label="Search results" className="pb-search-results overflow-y-auto p-2" data-pb-search-list>
      {rows.map((row, index) => {
        const previous = rows[index - 1];
        const newSection = previous?.kind !== row.kind;
        const newSpace = newSection || previous?.root !== row.root;
        const node = row.kind === "jump" ? ("page" in row.entry ? row.entry.page : row.entry.diagram) : row.hit;
        const props = { id: optionId(row.key), active: row.key === selectedKey, query,
          onActivate: () => onActivate(row), onHover: () => onSelect(row.key),
          trail: searchTrail(roots, row.root, node.path), rootLabel: searchSpaceLabel(roots, row.root), showRoot: false };
        return (
          <Fragment key={row.key}>
            {newSection && <li role="presentation" className="pb-search-section">{row.kind === "hit" ? "Content matches" : query ? "Title matches" : "Suggestions"}</li>}
            {newSpace && showSpaceGroups && <li role="presentation" className="pb-search-grouplabel" data-pb-search-group={row.root}>{searchSpaceLabel(roots, row.root)}</li>}
            {row.kind === "jump" ? <JumpToItem entry={row.entry} {...props} /> : <SearchResultItem hit={row.hit} {...props} />}
          </Fragment>
        );
      })}
      {status === "loading" && <li role="presentation" data-pb-search-loading className="px-3 py-3 text-sm text-muted" aria-live="polite">Searching content…</li>}
      {status === "error" && <li role="presentation" data-pb-search-error className="px-3 py-3 text-sm text-link-broken" aria-live="polite">{errorMessage}</li>}
      {status === "empty" && <li role="presentation" data-pb-search-empty className="px-3 py-5 text-sm text-muted">{rows.length ? "No content matches" : "No matches"} for “{query}”</li>}
      {status === "idle" && rows.length === 0 && <li role="presentation" className="px-3 py-5 text-sm text-muted">Type to search your docs</li>}
    </ul>
  );
}
