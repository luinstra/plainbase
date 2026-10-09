import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { rootDiscussionsQuery } from "../api/discussions";
import { treeQuery } from "../api/queries";
import { useDiscussionRefresh } from "../lib/useDiscussionRefresh";
import { rootDiscussionsDisabled, rootLabel } from "../lib/tree";
import { DISCUSSION_STATES, DiscussionFilters, type DiscussionStatusFilter, DiscussionAvailability, DiscussionListRows, DiscussionReadError, ReadWindow, stateLabel, uniqueDiscussionItems } from "./DiscussionRead";

export function DiscussionsIndex({ root }: { root?: string }) {
  return root === undefined ? <DiscussionsChooser /> : <RootDiscussions key={root} root={root} />;
}

function DiscussionsChooser() {
  const tree = useQuery(treeQuery);
  return <ReadWindow><h1 className="text-3xl font-bold">Discussions</h1>
    {tree.isPending && <p role="status">Loading roots…</p>}
    {tree.isError && <DiscussionReadError error={tree.error} retry={() => void tree.refetch()} />}
    {tree.data && <ul className="pb-discussion-list">{tree.data.roots.filter((entry) => entry.discussionsEnabled !== false).map((entry) => <li key={entry.root} className="pb-discussion-card">
      <Link to="/discussions/$root" params={{ root: entry.root }} className="pb-discussion-title">{rootLabel(entry)}</Link>
      <p className="text-sm text-muted">{!entry.available ? "Root not serving" : entry.editable ? "Serving" : "Read-only root"}</p>
    </li>)}</ul>}
  </ReadWindow>;
}

function RootDiscussions({ root }: { root: string }) {
  const [state, setState] = useState<string | null>(null);
  const [status, setStatus] = useState<DiscussionStatusFilter>("all");
  const tree = useQuery(treeQuery);
  const configuredDisabled = rootDiscussionsDisabled(tree.data?.roots, root);
  const query = useInfiniteQuery({ ...rootDiscussionsQuery(root, state), enabled: (!!tree.data || tree.isError) && !configuredDisabled });
  const first = query.data?.pages[0];
  const unavailable = query.data?.pages.find((page) => !page.discussions_available);
  useDiscussionRefresh(query, (!!tree.data || tree.isError) && !configuredDisabled && !unavailable);
  const latest = query.data?.pages.at(-1);
  const items = uniqueDiscussionItems(query.data?.pages ?? []);
  const visible = items.filter((item) => status === "all" || item.status === status);
  if (configuredDisabled || unavailable?.reason === "disabled_by_config") return <ReadWindow>
    <Link to="/discussions" className="text-sm text-link">All roots</Link>
    <h1 className="text-3xl font-bold break-words">Discussions in {root}</h1>
    <DiscussionAvailability reason="disabled_by_config" />
  </ReadWindow>;
  return <ReadWindow>
    <Link to="/discussions" className="text-sm text-link">All roots</Link>
    <h1 className="text-3xl font-bold break-words">Discussions in {root}</h1>
    <div className="flex flex-wrap items-end gap-3">
      <label className="text-sm text-muted">Match state <select className="ml-2 rounded border border-edge bg-surface px-2 py-1 text-ink" value={state ?? ""} onChange={(event) => setState(event.target.value || null)}>
        <option value="">All states</option>
        {DISCUSSION_STATES.map((value) => <option key={value} value={value}>{stateLabel(value)}</option>)}
      </select></label>
      <DiscussionFilters value={status} onChange={setStatus} more={!!query.hasNextPage} />
    </div>
    {query.isPending && <p role="status">Loading discussions…</p>}
    {query.isError && !query.data && <DiscussionReadError error={query.error} retry={() => void query.refetch()} />}
    {unavailable && <DiscussionAvailability reason={unavailable.reason} />}
    {first?.discussions_available && !unavailable && <>
      {query.isRefetchError && <p role="alert">Refresh failed. Showing earlier results; retry before relying on them. <button type="button" className="pb-discussion-action" onClick={() => void query.refetch()}>Retry</button></p>}
      {items.length === 0 && !query.hasNextPage && <p>No discussions in this view.</p>}
      {latest?.discussions.length === 0 && query.hasNextPage && <p>No matches in this window. Load more to continue the scan.</p>}
      {status !== "all" && visible.length === 0 && <p>No {status} discussions in the loaded results.</p>}
      <DiscussionListRows root={root} items={visible} />
      {query.isFetchNextPageError && <p role="alert">Could not load the next page. Earlier discussions remain available.</p>}
      {query.hasNextPage && <button type="button" className="pb-discussion-action" disabled={query.isFetching} onClick={() => void query.fetchNextPage({ cancelRefetch: false })}>
        {query.isFetchingNextPage ? "Loading…" : "Load more"}
      </button>}
    </>}
  </ReadWindow>;
}
