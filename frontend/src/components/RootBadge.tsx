/**
 * The root a search row belongs to, shown when there is more than one root to belong to. Both search
 * surfaces name a page by its ROOT-RELATIVE path, so with two roots holding the same path the rows are
 * otherwise identical on screen — and the reader picks one at random. Callers own the multi-root gate
 * (a single-root install must not grow a badge reading "docs"); this is presentation only.
 *
 * `data-pb-root-badge` is the stable selector, carrying the root name so a customizer can target one.
 */
export function RootBadge({ root }: { root: string }) {
  return (
    <span className="shrink-0 rounded border border-edge px-1.5 font-mono text-[11px] text-muted" data-pb-root-badge={root}>
      {root}
    </span>
  );
}
