import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useRef, useState } from "react";
import { pageDiscussionsQuery } from "../api/discussions";
import { DiscussionAvailability, DiscussionListRows, DiscussionReadError, uniqueDiscussionItems } from "./DiscussionRead";
import { DiscussionThread } from "./DiscussionThread";

export function DiscussionPanel({ root, pageId, onClose }: { root: string; pageId: string; onClose: () => void }) {
  const [selected, setSelected] = useState<string | null>(null);
  const panel = useRef<HTMLElement>(null);
  const query = useInfiniteQuery(pageDiscussionsQuery(root, pageId));
  const first = query.data?.pages[0];
  const items = uniqueDiscussionItems(query.data?.pages ?? []);
  return <section ref={panel} className="pb-discussion-panel min-w-0 space-y-4" aria-label="Page discussions" data-pb-discussion-panel>
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h2 className="text-xl font-bold">Discussions</h2>
      <button type="button" className="pb-discussion-action" onClick={onClose}>Back to page</button>
    </div>
    {selected ? <>
      <button type="button" className="pb-discussion-action" onClick={() => {
        const id = selected;
        setSelected(null);
        requestAnimationFrame(() => Array.from(panel.current?.querySelectorAll<HTMLButtonElement>("[data-pb-discussion-id]") ?? [])
          .find((button) => button.dataset.pbDiscussionId === id)?.focus());
      }}>Back to page discussions</button>
      <DiscussionThread root={root} id={selected} inPanel />
    </> : <>
      <Link to="/discussions/$root" params={{ root }} className="text-sm text-link">All discussions in {root}</Link>
      <button type="button" className="pb-discussion-action" disabled={query.isRefetching} onClick={() => void query.refetch()}>Refresh</button>
      {query.isPending && <p role="status">Loading page discussions…</p>}
      {query.isError && !query.data && <DiscussionReadError error={query.error} retry={() => void query.refetch()} />}
      {first && !first.discussions_available && <DiscussionAvailability reason={first.reason} />}
      {first?.discussions_available && <>
        {query.isRefetchError && <p role="alert">Refresh failed. Showing earlier discussions.</p>}
        {items.length === 0 && (query.hasNextPage ? <p>No discussions in this window. Load more to continue.</p> : <p>No discussions on this page.</p>)}
        <DiscussionListRows root={root} items={items} onSelect={(id) => setSelected(id)} />
        {query.isFetchNextPageError && <p role="alert">Could not load more discussions. Earlier rows remain available.</p>}
        {query.hasNextPage && <button type="button" className="pb-discussion-action" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
          {query.isFetchingNextPage ? "Loading…" : "Load more"}
        </button>}
      </>}
    </>}
  </section>;
}
