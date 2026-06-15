import type { TreePage } from "../api/types";

/**
 * One Stage-1 quick-switcher row: the page title + dimmed `node.path`. Presentation only —
 * navigation uses `pageHref(node)` (the `lib/tree.ts` helper) verbatim, never a re-derived
 * URL. No fuzzy highlighting markup is applied to the title (kept plain to stay text-only).
 */
export function JumpToItem({
  page,
  id,
  active,
  onActivate,
  onHover,
}: {
  page: TreePage;
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
      <span className="font-medium text-ink">{page.title}</span>
      <span className="truncate font-mono text-xs text-faint">{page.path}</span>
    </li>
  );
}
