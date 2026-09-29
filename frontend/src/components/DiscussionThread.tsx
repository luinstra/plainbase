import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useEffect, useRef } from "react";
import { ApiError } from "../api/client";
import { discussionDetailQuery } from "../api/discussions";
import { formatTime } from "../lib/datetime";
import { DiscussionAnchor, DiscussionAvailability, DiscussionReadError, DiscussionSummary, ReadWindow } from "./DiscussionRead";

export function DiscussionThread({ root, id, inPanel = false }: { root: string; id: string; inPanel?: boolean }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { if (inPanel) heading.current?.focus(); }, [inPanel, id]);
  const query = useInfiniteQuery(discussionDetailQuery(root, id));
  const first = query.data?.pages[0];
  const detail = first?.discussion;
  const title = detail?.page.path ? `Discussion on ${detail.page.path}` : "Discussion";
  const seen = new Set<string>();
  const comments = query.data?.pages.flatMap((page) => page.comments.filter((comment) => {
    if (seen.has(comment.id)) return false;
    seen.add(comment.id);
    return true;
  })) ?? [];
  return <ReadWindow>
    <div className="flex flex-wrap items-center justify-between gap-2">
      <Link to="/discussions/$root" params={{ root }} className="text-sm text-link">Discussions in {root}</Link>
      {inPanel && <Link to="/discussions/$root/$id" params={{ root, id }} className="text-sm text-link">Open full discussion</Link>}
      <button type="button" className="pb-discussion-action" disabled={query.isRefetching} onClick={() => void query.refetch()}>Refresh</button>
    </div>
    {inPanel ?
      <h3 ref={heading} className="text-xl font-bold break-words" tabIndex={-1}>{title}</h3> :
      <h1 ref={heading} className="text-3xl font-bold break-words" tabIndex={-1}>{title}</h1>}
    {query.isPending && <p role="status">Loading discussion…</p>}
    {query.isError && !query.data && (query.error instanceof ApiError && query.error.code === "discussion_not_found" ?
      <p role="alert">Discussion not found</p> :
      <DiscussionReadError error={query.error} retry={() => void query.refetch()} />)}
    {first && !first.discussions_available && <DiscussionAvailability reason={first.reason} />}
    {first?.discussions_available && !detail && <p role="alert">Discussion details are unavailable. Try refreshing.</p>}
    {detail && <>
      <details><summary>Discussion ID</summary><code>{id}</code></details>
      {query.isRefetchError && <p role="alert">Refresh failed. Showing earlier discussion content; retry before relying on it.</p>}
      <DiscussionSummary item={detail} root={root} />
      <DiscussionAnchor label="Original anchor" anchor={detail.anchor} inPanel={inPanel} />
      {detail.reattachment && <section className="space-y-2">
        <p className="text-sm text-muted">Reattached by {detail.reattachment.by.label} at <time dateTime={detail.reattachment.at} title={detail.reattachment.at}>{formatTime(detail.reattachment.at)}</time></p>
        <DiscussionAnchor label="Latest reattachment" anchor={detail.reattachment.anchor} inPanel={inPanel} />
      </section>}
      {detail.state !== "incomplete" && detail.state !== "unreadable" && <section className="space-y-4">
        {inPanel ? <h4 className="text-xl font-semibold">Comments</h4> : <h2 className="text-xl font-semibold">Comments</h2>}
        {comments.length === 0 && <p>{query.hasNextPage ? "No comments in this window. Load more to continue." : "No comments yet."}</p>}
        {comments.map((comment) => <article key={comment.id} className="pb-discussion-card">
          <p className="mb-2 text-sm text-muted">{comment.author.label}{comment.author.kind === "agent" ? " · Agent" : ""} · <time dateTime={comment.created} title={comment.created}>{formatTime(comment.created)}</time>
            {comment.edited_at && <> · Edited <time dateTime={comment.edited_at} title={comment.edited_at}>{formatTime(comment.edited_at)}</time></>}{comment.retracted ? " · Retracted" : ""}</p>
          <div className="pb-discussion-body" dangerouslySetInnerHTML={{ __html: comment.html }} />
        </article>)}
        {query.isFetchNextPageError && <p role="alert">Could not load more comments. Earlier comments remain available.</p>}
        {query.hasNextPage && <button type="button" className="pb-discussion-action" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
          {query.isFetchingNextPage ? "Loading…" : "Load more"}
        </button>}
      </section>}
    </>}
  </ReadWindow>;
}
