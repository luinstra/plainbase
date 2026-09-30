import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { addDiscussionComment, discussionDetailQuery, refreshDiscussionViews } from "../api/discussions";
import { sessionQuery } from "../api/queries";
import type { DiscussionDetailResponse } from "../api/types";
import { formatTime } from "../lib/datetime";
import { DiscussionComposer, commentValidation, discussionWriteError } from "./DiscussionComposer";
import { DiscussionAnchor, DiscussionAvailability, DiscussionReadError, DiscussionSummary, ReadWindow } from "./DiscussionRead";

export function DiscussionThread({ root, id, inPanel = false }: { root: string; id: string; inPanel?: boolean }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const posting = useRef(false);
  const alive = useRef(true);
  const [body, setBody] = useState("");
  const [composing, setComposing] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshFailed, setRefreshFailed] = useState(false);
  const client = useQueryClient();
  const session = useQuery(sessionQuery);
  useEffect(() => { if (inPanel) heading.current?.focus(); }, [inPanel, id]);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);
  const query = useInfiniteQuery(discussionDetailQuery(root, id));
  const replyMutation = useMutation({ mutationFn: (submitted: string) => addDiscussionComment(root, id, submitted), retry: false });
  const first = query.data?.pages[0];
  const detail = first?.discussion;
  const title = detail?.page.path ? `Discussion on ${detail.page.path}` : "Discussion";
  const seen = new Set<string>();
  const comments = query.data?.pages.flatMap((page) => page.comments.filter((comment) => {
    if (seen.has(comment.id)) return false;
    seen.add(comment.id);
    return true;
  })) ?? [];
  const disabled = query.isPending || query.isError || query.isRefetchError ? "Refresh this discussion before replying." :
    first?.discussions_available === false ? "Replies are unavailable here." :
    !detail || detail.status !== "open" || detail.state === "incomplete" || detail.state === "unreadable" ? "This discussion cannot accept replies." :
    session.data?.auth_mode !== "off" && session.data?.authenticated === false ? "Sign in before replying. Your draft will stay here." : null;

  async function reply() {
    if (posting.current || disabled) return;
    const validation = commentValidation(body);
    if (validation) { setError(validation); return; }
    const submitted = { root, id, body };
    posting.current = true; setStatus("Posting…"); setError(null); setRefreshFailed(false);
    try {
      const result = await replyMutation.mutateAsync(submitted.body);
      if (alive.current) {
        setBody((current) => current === submitted.body ? "" : current);
        setComposing(false);
        setStatus("Reply posted");
      }
      try {
        await refreshDiscussionViews(client, submitted.root, submitted.id);
        if (!alive.current || !result.comment_id) return;
        const refreshed = client.getQueryData<InfiniteData<DiscussionDetailResponse>>(discussionDetailQuery(submitted.root, submitted.id).queryKey);
        const visible = refreshed?.pages.some((page) => page.comments.some((comment) => comment.id === result.comment_id));
        if (!visible && refreshed?.pages.at(-1)?.next) setStatus("Reply posted. Continue loading comments to see it.");
      } catch { if (alive.current) setRefreshFailed(true); }
    } catch (failure) {
      if (alive.current) {
        setStatus(null); setError(discussionWriteError(failure));
        if (failure instanceof ApiError && ["discussion_changed", "stale_discussion", "discussion_resolved", "comment_retracted"].includes(failure.code)) {
          void query.refetch();
        }
      }
    } finally { posting.current = false; }
  }
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
    {first?.discussions_available && detail?.status === "open" && detail.state !== "incomplete" && detail.state !== "unreadable" && !composing &&
      <button type="button" className="pb-discussion-action" onClick={() => { setComposing(true); setError(null); setStatus(null); }}>Reply</button>}
    {composing && <DiscussionComposer body={body} setBody={setBody} submit={() => void reply()}
      cancel={() => { setComposing(false); setBody(""); setError(null); }} busy={replyMutation.isPending}
      disabled={disabled} error={error} status={status} submitLabel="Post reply" onEscape={() => setError(null)} />}
    {!composing && status && <p role="status" aria-label={status}>{status}</p>}
    {!composing && error && <p role="alert">{error}</p>}
    {refreshFailed && <p role="alert">Posted, but the view could not refresh. <button type="button" className="pb-discussion-action"
      onClick={() => void refreshDiscussionViews(client, root, id).then(() => { if (alive.current) setRefreshFailed(false); })
        .catch(() => { if (alive.current) setRefreshFailed(true); })}>Refresh</button></p>}
  </ReadWindow>;
}
