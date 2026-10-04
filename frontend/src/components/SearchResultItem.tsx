import type { SearchHit } from "../api/types";
import { splitHighlights, titleHighlights } from "../lib/highlightSplit";
import { RootBadge } from "./RootBadge";

/** A content hit. Snippets and heading paths remain server-owned text; highlights never use HTML injection. */
export function SearchResultItem({
  hit,
  showRoot,
  rootLabel,
  trail,
  query = "",
  id,
  active,
  onActivate,
  onHover,
}: {
  hit: SearchHit;
  showRoot?: boolean;
  rootLabel?: string;
  trail?: string;
  query?: string;
  id: string;
  active: boolean;
  onActivate: () => void;
  onHover: () => void;
}) {
  const fragments = splitHighlights(hit.snippet, hit.highlights);
  const headings = hit.heading_path[0]?.trim().toLowerCase() === hit.title.trim().toLowerCase()
    ? hit.heading_path.slice(1) : hit.heading_path;
  const breadcrumb = headings.join(" › ");
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
      className={active ? "pb-search-row cursor-pointer rounded px-3 py-2" : "pb-search-row cursor-pointer rounded px-3 py-2 hover:bg-hovered"}
    >
      {!showRoot && rootLabel && <span className="sr-only">{rootLabel}: </span>}
      <div className="flex items-baseline gap-2">
        <span className="truncate text-sm font-medium text-ink" title={hit.title}>
          {titleHighlights(hit.title, query).map((part, index) => part.mark ? <mark key={index}>{part.text}</mark> : part.text)}
        </span>
        {showRoot && <RootBadge root={hit.root} label={rootLabel} />}
      </div>
      <p className="mt-0.5 truncate text-xs text-faint" data-pb-search-trail title={[hit.path, breadcrumb].filter(Boolean).join(" · ")}>
        <span className="font-mono">{trail ?? hit.path}</span>
        {breadcrumb && <> · <span className="font-sans text-muted">{breadcrumb}</span></>}
      </p>
      {hit.snippet && <p className="pb-search-snippet mt-0.5 text-sm text-muted" data-pb-search-snippet title={hit.snippet}>
        {fragments.map((frag, i) => (frag.mark ? <mark key={i}>{frag.text}</mark> : <span key={i}>{frag.text}</span>))}
      </p>}
    </li>
  );
}
