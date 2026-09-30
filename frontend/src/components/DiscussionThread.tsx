import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { addDiscussionComment, discussionDetailQuery, editDiscussionComment, purgeDiscussionComment, reattachDiscussion,
  refreshDiscussionViews, reopenDiscussion, resolveDiscussion, retractDiscussionComment } from "../api/discussions";
import { sessionQuery } from "../api/queries";
import type { DiscussionComment, DiscussionDetailResponse, DiscussionQuoteRequestAnchor } from "../api/types";
import { formatTime } from "../lib/datetime";
import { DiscussionComposer, commentValidation, discussionActionError, discussionWriteError } from "./DiscussionComposer";
import { DiscussionAnchor, DiscussionAvailability, DiscussionReadError, DiscussionSummary, ReadWindow } from "./DiscussionRead";
import { DiscussionReattach, type DiscussionSourceConnection } from "./DiscussionReattach";

type ThreadAction = { kind: "edit"; comment: DiscussionComment; body: string } |
  { kind: "retract" | "purge"; comment: DiscussionComment } | { kind: "reattach"; pageId: string };
type ActionKind = ThreadAction["kind"] | "resolve" | "reopen";
type SuppressedComment = { kind: "edit" | "retract" | "purge"; detailUpdates: number };

export function DiscussionThread({ root, id, inPanel = false, source, creationPending = false, onActionChange }: {
  root: string; id: string; inPanel?: boolean; source?: DiscussionSourceConnection;
  creationPending?: boolean;
  onActionChange?: (active: boolean, busy: boolean) => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const posting = useRef(false);
  const alive = useRef(true);
  const inspection = useRef(false);
  const activityCallback = useRef(onActionChange);
  activityCallback.current = onActionChange;
  const [body, setBody] = useState("");
  const [composing, setComposing] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshFailed, setRefreshFailed] = useState<string | null>(null);
  const [action, setAction] = useState<ThreadAction | null>(null);
  const [writing, setWriting] = useState(false);
  const [inspectRequired, setInspectRequired] = useState(false);
  const [suppressed, setSuppressed] = useState<Record<string, SuppressedComment>>({});
  const origin = useRef<HTMLButtonElement | null>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const client = useQueryClient();
  const session = useQuery(sessionQuery);
  useEffect(() => { if (inPanel) heading.current?.focus(); }, [inPanel, id]);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; activityCallback.current?.(false, false); };
  }, []);
  useEffect(() => { if (action?.kind === "retract" || action?.kind === "purge") cancelButton.current?.focus(); }, [action?.kind]);
  const query = useInfiniteQuery(discussionDetailQuery(root, id));
  const replyMutation = useMutation({ mutationFn: (submitted: string) => addDiscussionComment(root, id, submitted), retry: false });
  useEffect(() => { onActionChange?.(!!action || composing || inspectRequired, writing || replyMutation.isPending); },
    [action, composing, inspectRequired, writing, replyMutation.isPending, onActionChange]);
  const first = query.data?.pages[0];
  const detail = first?.discussion;
  const title = detail?.page.path ? `Discussion on ${detail.page.path}` : "Discussion";
  const seen = new Set<string>();
  const comments = query.data?.pages.flatMap((page) => page.comments.filter((comment) => {
    if (seen.has(comment.id)) return false;
    seen.add(comment.id);
    return true;
  })) ?? [];
  useEffect(() => {
    if (!query.isSuccess || query.isFetching) return;
    const refreshed = client.getQueryState(discussionDetailQuery(root, id).queryKey);
    setSuppressed((current) => {
      const remaining = Object.fromEntries(Object.entries(current).filter(([commentId, saved]) =>
        // Loading another window doesn't refresh the old edited comment's HTML/Markdown.
        saved.kind === "edit" ? !!refreshed?.fetchMeta?.fetchMore || (refreshed?.dataUpdateCount ?? 0) <= saved.detailUpdates :
          query.data?.pages.some((page) => page.comments.some((comment) => comment.id === commentId && (saved.kind === "purge" || !comment.retracted)))));
      return Object.keys(remaining).length === Object.keys(current).length ? current : remaining;
    });
  }, [query.data, query.dataUpdatedAt, query.isSuccess, query.isFetching, client, root, id]);
  const disabled = query.isPending || query.isError || query.isRefetchError ? "Refresh this discussion before replying." :
    first?.discussions_available === false ? "Replies are unavailable here." :
    !detail || detail.status !== "open" || detail.state === "incomplete" || detail.state === "unreadable" ? "This discussion cannot accept replies." :
    session.data?.auth_mode !== "off" && session.data?.authenticated === false ? "Sign in before replying. Your draft will stay here." : null;
  const accessDisabled = query.isPending || query.isError || query.isRefetchError ? "Refresh this discussion before taking an action." :
    !first?.discussions_available ? "Actions are unavailable here. Your action is still here." :
    session.data?.auth_mode !== "off" && session.data?.authenticated === false ? "Sign in before taking an action. Your text and selection will stay here." : null;
  const actionDisabled = accessDisabled || (!detail?.status || !detail.starter || !detail.anchor || detail.state === "incomplete" || detail.state === "unreadable"
    ? "This discussion cannot currently accept this action. Refresh to check." : null);
  const currentComment = action && "comment" in action ? comments.find((comment) => comment.id === action.comment.id) : null;
  const targetDisabled = action && "comment" in action && (!currentComment || action.kind !== "purge" && currentComment.retracted)
    ? currentComment?.retracted ? action.kind === "edit" ? "This comment was retracted. Your text is still here for copying or canceling." :
      "This comment was retracted. Cancel this action to review the updated comment." :
      action.kind === "edit" ? "This comment is not in the loaded view. Keep any unsaved text for copying, then refresh or load more." :
        "This comment is not in the loaded view. Refresh or load more before continuing." : null;
  const busy = creationPending || writing || replyMutation.isPending;
  const competing = busy || !!action || composing;
  const localDisabled = inspectRequired ? "Refresh and inspect whether the change happened before trying again." : null;

  function begin(next: ThreadAction, button: HTMLButtonElement) {
    if (posting.current || creationPending || inspection.current || action || composing ||
      (next.kind === "purge" ? accessDisabled : actionDisabled) || "comment" in next && suppressed[next.comment.id]) return;
    origin.current = button;
    setAction(next); setError(null); setStatus(null);
  }

  function cancelAction() {
    if (posting.current) return;
    setAction(null); setError(null);
    const button = origin.current;
    requestAnimationFrame(() => { if (button?.isConnected && !button.disabled) button.focus(); else heading.current?.focus(); });
  }

  async function refresh() {
    const key = discussionDetailQuery(root, id).queryKey;
    const before = client.getQueryState(key);
    if (posting.current || creationPending || before?.fetchStatus === "fetching") return;
    let viewsRefreshed = false;
    try {
      await refreshDiscussionViews(client, root, id);
      viewsRefreshed = true;
    } catch { /* The query renders ordinary refresh failures; keep any existing saved-action notice. */ }
    finally {
      const refreshed = client.getQueryState(key);
      // Independent list failures do not invalidate a fresh detail inspection. Loading a later
      // window may cancel this full read, so neither inspection nor its notice retires on fetchMore.
      if (alive.current && refreshed?.status === "success" && refreshed.fetchStatus === "idle" && !refreshed.fetchMeta?.fetchMore &&
        refreshed.dataUpdateCount > (before?.dataUpdateCount ?? 0)) {
        inspection.current = false; setInspectRequired(false);
        if (viewsRefreshed) setRefreshFailed(null);
      }
    }
  }

  async function submitAction(kind: ActionKind, anchor?: DiscussionQuoteRequestAnchor): Promise<unknown> {
    if (posting.current || creationPending || inspection.current || (kind === "purge" ? accessDisabled : actionDisabled) || targetDisabled ||
      (kind === "resolve" || kind === "reopen") && (action || composing)) return;
    if (kind === "edit" && action?.kind === "edit") {
      const validation = commentValidation(action.body, "saving");
      if (validation) { setError(validation); return; }
    }
    if (kind === "reattach" && (action?.kind !== "reattach" || !anchor || detail?.status !== "open" || detail.page.id !== action.pageId)) return;
    const submitted = { root, id, action, anchor };
    const commentId = submitted.action && "comment" in submitted.action ? submitted.action.comment.id : null;
    posting.current = true; setWriting(true); setStatus(null); setError(null);
    const success = kind === "edit" ? { text: "Comment saved", verb: "Saved" } :
      kind === "retract" ? { text: "Comment retracted", verb: "Retracted" } :
        kind === "purge" ? { text: "Comment removed", verb: "Removed" } :
          kind === "resolve" ? { text: "Discussion resolved", verb: "Resolved" } :
            kind === "reopen" ? { text: "Discussion reopened", verb: "Reopened" } : { text: "Discussion reattached", verb: "Reattached" };
    try {
      if (kind === "edit" && submitted.action?.kind === "edit" && commentId)
        await editDiscussionComment(submitted.root, submitted.id, commentId, submitted.action.body);
      else if (kind === "retract" && commentId) await retractDiscussionComment(submitted.root, submitted.id, commentId);
      else if (kind === "purge" && commentId) await purgeDiscussionComment(submitted.root, submitted.id, commentId);
      else if (kind === "resolve") await resolveDiscussion(submitted.root, submitted.id);
      else if (kind === "reopen") await reopenDiscussion(submitted.root, submitted.id);
      else if (kind === "reattach" && submitted.anchor) await reattachDiscussion(submitted.root, submitted.id, submitted.anchor);
      else return;
      if (alive.current) {
        if (commentId && (kind === "edit" || kind === "retract" || kind === "purge")) {
          const detailUpdates = client.getQueryState(discussionDetailQuery(submitted.root, submitted.id).queryKey)?.dataUpdateCount ?? 0;
          setSuppressed((current) => ({ ...current, [commentId]: { kind, detailUpdates } }));
        }
        setAction(null); setStatus(success.text); setRefreshFailed(null);
        if (kind !== "resolve" && kind !== "reopen") heading.current?.focus();
      }
      try { await refreshDiscussionViews(client, submitted.root, submitted.id); }
      catch { if (alive.current) setRefreshFailed(success.verb); }
    } catch (failure) {
      if (alive.current) {
        setError(discussionActionError(failure, kind));
        if (!(failure instanceof ApiError) || failure.status >= 500) { inspection.current = true; setInspectRequired(true); }
        if (failure instanceof ApiError && ["discussion_changed", "stale_discussion", "already_resolved", "already_open",
          "discussion_resolved", "comment_retracted", "comment_not_found", "discussion_not_found"].includes(failure.code)) void query.refetch();
      }
      return failure;
    } finally { posting.current = false; if (alive.current) setWriting(false); }
  }

  async function reply() {
    if (posting.current || creationPending || inspection.current || disabled || action || !composing) return;
    const validation = commentValidation(body);
    if (validation) { setError(validation); return; }
    const submitted = { root, id, body };
    posting.current = true; setWriting(true); setStatus("Posting…"); setError(null);
    try {
      const result = await replyMutation.mutateAsync(submitted.body);
      if (alive.current) {
        setBody((current) => current === submitted.body ? "" : current);
        setComposing(false);
        setStatus("Reply posted");
        setRefreshFailed(null);
      }
      try {
        await refreshDiscussionViews(client, submitted.root, submitted.id);
        if (!alive.current || !result.comment_id) return;
        const refreshed = client.getQueryData<InfiniteData<DiscussionDetailResponse>>(discussionDetailQuery(submitted.root, submitted.id).queryKey);
        const visible = refreshed?.pages.some((page) => page.comments.some((comment) => comment.id === result.comment_id));
        if (!visible && refreshed?.pages.at(-1)?.next) setStatus("Reply posted. Continue loading comments to see it.");
      } catch { if (alive.current) setRefreshFailed("Posted"); }
    } catch (failure) {
      if (alive.current) {
        setStatus(null); setError(discussionWriteError(failure));
        if (!(failure instanceof ApiError) || failure.status >= 500) { inspection.current = true; setInspectRequired(true); }
        if (failure instanceof ApiError && ["discussion_changed", "stale_discussion", "discussion_resolved", "comment_retracted"].includes(failure.code)) {
          void query.refetch();
        }
      }
    } finally { posting.current = false; if (alive.current) setWriting(false); }
  }
  return <ReadWindow>
    <div className="flex flex-wrap items-center justify-between gap-2">
      <Link to="/discussions/$root" params={{ root }} className="text-sm text-link">Discussions in {root}</Link>
      {inPanel && <Link to="/discussions/$root/$id" params={{ root, id }} className="text-sm text-link">Open full discussion</Link>}
      <button type="button" className="pb-discussion-action" disabled={query.isFetching || busy} onClick={() => void refresh()}>Refresh</button>
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
      <div className="space-y-2">
        {!actionDisabled && <p className="text-sm text-muted">{session.data?.auth_mode === "off" ? "Local access allows discussion actions without signing in." :
          "Only the comment author can edit or retract. The starter or an editor, including an admin, can change status or reattach."}</p>}
        {actionDisabled && <p>{actionDisabled}</p>}
        {detail.status && <button type="button" className="pb-discussion-action" disabled={competing || !!actionDisabled || !!localDisabled}
          onClick={() => void submitAction(detail.status === "resolved" ? "reopen" : "resolve")}>
          {detail.status === "resolved" ? "Reopen discussion" : "Resolve discussion"}</button>}
        {detail.anchor?.kind === "quote" && <>
          <button type="button" className="pb-discussion-action ml-2"
            disabled={competing || !!actionDisabled || !!localDisabled || detail.status !== "open" || !detail.page.id ||
              inPanel && (!source || source.root !== root || source.pageId !== detail.page.id)}
            onClick={(event) => { if (detail.page.id) begin({ kind: "reattach", pageId: detail.page.id }, event.currentTarget); }}>Reattach</button>
          {detail.status === "resolved" && <p>Reopen this discussion before reattaching.</p>}
          {!detail.page.id && <p>This discussion has no stored source page ID for reattachment.</p>}
          {inPanel && detail.page.id && (!source || source.root !== root || source.pageId !== detail.page.id) &&
            <p>Open full discussion to reattach using its stored source page.</p>}
        </>}
      </div>
      {(comments.length > 0 || detail.state !== "incomplete" && detail.state !== "unreadable") && <section className="space-y-4">
        {inPanel ? <h4 className="text-xl font-semibold">Comments</h4> : <h2 className="text-xl font-semibold">Comments</h2>}
        {comments.length === 0 && <p>{query.hasNextPage ? "No comments in this window. Load more to continue." : "No comments yet."}</p>}
        {comments.map((comment) => <article key={comment.id} className="pb-discussion-card">
          <p className="mb-2 text-sm text-muted">{comment.author.label}{comment.author.kind === "agent" ? " · Agent" : ""} · <time dateTime={comment.created} title={comment.created}>{formatTime(comment.created)}</time>
            {comment.edited_at && <> · Edited <time dateTime={comment.edited_at} title={comment.edited_at}>{formatTime(comment.edited_at)}</time></>}{comment.retracted ? " · Retracted" : ""}</p>
          {suppressed[comment.id] ?
            <p>Comment {suppressed[comment.id].kind === "purge" ? "removed" : suppressed[comment.id].kind === "retract" ? "retracted" : "saved"}; refresh to update this view.</p> :
            <div className="pb-discussion-body" dangerouslySetInnerHTML={{ __html: comment.html }} />}
          {!suppressed[comment.id] && <div className="mt-3 flex flex-wrap gap-2">
            {!comment.retracted && <>
              <button type="button" className="pb-discussion-action" disabled={competing || !!actionDisabled || !!localDisabled}
                onClick={(event) => begin({ kind: "edit", comment, body: comment.markdown }, event.currentTarget)}>Edit comment</button>
              <button type="button" className="pb-discussion-action" disabled={competing || !!actionDisabled || !!localDisabled}
                onClick={(event) => begin({ kind: "retract", comment }, event.currentTarget)}>Retract comment</button>
            </>}
            <button type="button" className="pb-discussion-action" disabled={competing || !!accessDisabled || !!localDisabled}
              onClick={(event) => begin({ kind: "purge", comment }, event.currentTarget)}>
              {session.data?.auth_mode === "off" ? "Purge comment" : "Purge comment (admin)"}</button>
          </div>}
        </article>)}
        {query.isFetchNextPageError && <p role="alert">Could not load more comments. Earlier comments remain available.</p>}
        {query.hasNextPage && <button type="button" className="pb-discussion-action" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
          {query.isFetchingNextPage ? "Loading…" : "Load more"}
        </button>}
      </section>}
    </>}
    {action?.kind === "reattach" && <DiscussionReattach root={root} pageId={action.pageId} source={inPanel ? source : undefined}
      originalQuote={detail?.anchor?.kind === "quote" ? detail.anchor.quote : ""} busy={busy}
      disabled={actionDisabled || localDisabled || (detail?.page.id !== action.pageId ? "The stored source identity changed. Cancel and review this discussion." : null)}
      open={detail?.status === "open"} submit={(anchor) => submitAction("reattach", anchor)} cancel={cancelAction}
      clearSubmitError={() => setError(null)} />}
    {action?.kind === "edit" && <div data-pb-active-action><DiscussionComposer body={action.body}
      setBody={(value) => setAction((current) => current?.kind === "edit" ? { ...current, body: value } : current)}
      submit={() => void submitAction("edit")} cancel={cancelAction} busy={busy}
      disabled={actionDisabled || targetDisabled || localDisabled} error={error} submitLabel="Save comment" onEscape={() => setError(null)} /></div>}
    {(action?.kind === "retract" || action?.kind === "purge") && <section className="pb-discussion-card space-y-3"
      aria-label={action.kind === "purge" ? "Confirm purge" : "Confirm retraction"} data-pb-active-action onKeyDown={(event) => {
        if (event.key === "Escape" && !posting.current) { event.preventDefault(); cancelAction(); }
      }}>
      <p>{action.comment.author.label} · <time dateTime={action.comment.created} title={action.comment.created}>{formatTime(action.comment.created)}</time></p>
      <p>{action.kind === "purge" ? "Remove this comment file. Earlier Git history may still contain it." :
        "Replace this comment with a final retraction notice. It cannot be edited or restored here."}</p>
      {(action.kind === "purge" ? accessDisabled : actionDisabled) || targetDisabled || localDisabled ?
        <p>{(action.kind === "purge" ? accessDisabled : actionDisabled) || targetDisabled || localDisabled}</p> : null}
      <div className="flex flex-wrap gap-2">
        <button type="button" className="pb-discussion-action" disabled={writing || !!(action.kind === "purge" ? accessDisabled : actionDisabled) || !!targetDisabled || !!localDisabled}
          onClick={() => void submitAction(action.kind)}>{action.kind === "purge" ? "Purge comment" : "Retract comment"}</button>
        <button ref={cancelButton} type="button" className="pb-discussion-action" data-pb-action-focus disabled={writing} onClick={cancelAction}>Cancel</button>
      </div>
    </section>}
    {first?.discussions_available && detail?.status === "open" && detail.state !== "incomplete" && detail.state !== "unreadable" && !composing &&
      <button type="button" className="pb-discussion-action" disabled={competing || !!disabled || !!localDisabled} onClick={() => {
        if (posting.current || creationPending || inspection.current || disabled || action || composing) return;
        setComposing(true); setError(null); setStatus(null);
      }}>Reply</button>}
    {composing && <DiscussionComposer body={body} setBody={setBody} submit={() => void reply()}
      cancel={() => { if (posting.current) return; setComposing(false); setBody(""); setError(null); }} busy={busy}
      disabled={disabled || localDisabled} error={error} status={status} submitLabel="Post reply" onEscape={() => setError(null)} />}
    {!action && !composing && localDisabled && <p role="alert">{localDisabled}</p>}
    {!composing && status && <p role="status" aria-label={status}>{status}</p>}
    {!composing && action?.kind !== "edit" && error && <p role="alert">{error}</p>}
    {refreshFailed && <p role="alert">{refreshFailed}, but the view could not refresh. <button type="button" className="pb-discussion-action"
      disabled={busy || query.isFetching} onClick={() => void refresh()}>Refresh</button></p>}
  </ReadWindow>;
}
