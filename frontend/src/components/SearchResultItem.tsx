import type { SearchHit } from "../api/types";
import { splitHighlights } from "../lib/highlightSplit";

/**
 * One Stage-2 full-text hit (PB-SEARCH-1). Title, the `heading_path` breadcrumb joined
 * verbatim, and the snippet rendered from `snippet` + `highlights` via `splitHighlights`:
 * each fragment is a bare text node or a `<mark>` through React interpolation — never
 * `innerHTML`, never client re-derivation (§A3/§A4).
 */
export function SearchResultItem({
  hit,
  id,
  active,
  onActivate,
  onHover,
}: {
  hit: SearchHit;
  id: string;
  active: boolean;
  onActivate: () => void;
  onHover: () => void;
}) {
  const fragments = splitHighlights(hit.snippet, hit.highlights);
  const breadcrumb = hit.heading_path.join(" › ");
  return (
    <li
      id={id}
      role="option"
      aria-selected={active}
      data-pb-search-item="hit"
      data-pb-search-active={active ? "" : undefined}
      onMouseDown={(event) => {
        event.preventDefault(); // keep focus in the input; activation drives navigation
        onActivate();
      }}
      onMouseMove={onHover}
      className={active ? "cursor-pointer rounded bg-active px-3 py-2" : "cursor-pointer rounded px-3 py-2 hover:bg-hovered"}
    >
      <div className="flex items-baseline gap-2">
        <span className="font-medium text-ink">{hit.title}</span>
        {breadcrumb && <span className="truncate text-xs text-muted">{breadcrumb}</span>}
      </div>
      <p className="mt-0.5 text-sm text-muted" data-pb-search-snippet>
        {fragments.map((frag, i) => (frag.mark ? <mark key={i}>{frag.text}</mark> : <span key={i}>{frag.text}</span>))}
      </p>
    </li>
  );
}
