import type { PageEntry } from "../lib/tree";
import { RootBadge } from "./RootBadge";

/**
 * One Stage-1 quick-switcher row: the page title + dimmed `page.path`, badged with the entry's root
 * when there are 2+ of them (`showRoot`) — the path is root-relative, so without the badge two roots
 * holding the same file render two identical rows. Presentation only: navigation uses `pageHref(page)`
 * (the `lib/tree.ts` helper) verbatim, never a re-derived URL. No fuzzy highlighting markup is applied
 * to the title (kept plain to stay text-only).
 */
export function JumpToItem({
  entry,
  showRoot,
  id,
  active,
  onActivate,
  onHover,
}: {
  entry: PageEntry;
  showRoot?: boolean;
  id: string;
  active: boolean;
  onActivate: () => void;
  onHover: () => void;
}) {
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
          ? "flex cursor-pointer items-baseline justify-between gap-3 rounded px-3 py-2"
          : "flex cursor-pointer items-baseline justify-between gap-3 rounded px-3 py-2 hover:bg-hovered"
      }
    >
      <span className="font-medium text-ink">{entry.page.title}</span>
      <span className="flex items-baseline gap-1.5 overflow-hidden">
        {showRoot && <RootBadge root={entry.root} />}
        <span className="truncate font-mono text-xs text-faint">{entry.page.path}</span>
      </span>
    </li>
  );
}
