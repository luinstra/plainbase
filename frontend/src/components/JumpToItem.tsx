import type { QuickSwitchEntry } from "../lib/tree";
import { titleHighlights } from "../lib/highlightSplit";
import { RootBadge } from "./RootBadge";

/** A cached title match. Display trails are inert; activation keeps the server-issued destination. */
export function JumpToItem({
  entry,
  showRoot,
  rootLabel,
  trail,
  query = "",
  id,
  active,
  onActivate,
  onHover,
}: {
  entry: QuickSwitchEntry;
  showRoot?: boolean;
  rootLabel?: string;
  trail?: string;
  query?: string;
  id: string;
  active: boolean;
  onActivate: () => void;
  onHover: () => void;
}) {
  const node = "page" in entry ? entry.page : entry.diagram;

  return (
    <li
      id={id}
      role="option"
      aria-selected={active}
      data-pb-search-item="jump"
      data-pb-search-active={active ? "" : undefined}
      onMouseDown={(event) => {
        event.preventDefault();
        onActivate();
      }}
      onMouseMove={onHover}
      className={
        active
          ? "pb-search-row cursor-pointer rounded px-3 py-2"
          : "pb-search-row cursor-pointer rounded px-3 py-2 hover:bg-hovered"
      }
    >
      {!showRoot && rootLabel && <span className="sr-only">{rootLabel}: </span>}
      <span className="block truncate text-sm font-medium text-ink" title={node.title}>
        {titleHighlights(node.title, query).map((part, index) => part.mark ? <mark key={index}>{part.text}</mark> : part.text)}
      </span>
      <span className="mt-0.5 flex items-baseline gap-1.5 overflow-hidden">
        {showRoot && <RootBadge root={entry.root} label={rootLabel} />}
        <span className="truncate font-mono text-xs text-faint" title={node.path}>{trail ?? node.path}</span>
      </span>
    </li>
  );
}
