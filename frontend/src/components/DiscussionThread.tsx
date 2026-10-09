import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useEffect, useId, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { addDiscussionComment, discussionDetailQuery, editDiscussionComment, purgeDiscussionComment, reattachDiscussion,
  refreshDiscussionViews, reopenDiscussion, resolveDiscussion, retractDiscussionComment } from "../api/discussions";
import { sessionQuery, treeQuery } from "../api/queries";
import type { DiscussionDetail, DiscussionComment, DiscussionDetailResponse, DiscussionQuoteRequestAnchor } from "../api/types";
import { useDiscussionRefresh } from "../lib/useDiscussionRefresh";
import { rootDiscussionsDisabled } from "../lib/tree";
import { formatTime } from "../lib/datetime";
import { focusDiscussionElement } from "../lib/discussionFocus";
import { retireDiscussionDraft, useDiscussionDraft } from "../lib/useDiscussionDraft";
import { DiscussionComposer, commentValidation, discussionActionError, discussionWriteError, discussionWriteRecovery } from "./DiscussionComposer";
import { DiscussionAnchor, DiscussionAvatar, DiscussionStatus, DiscussionAvailability, DiscussionReadError, DiscussionState, DiscussionSummary, formatDiscussionTime, ReadWindow } from "./DiscussionRead";
import { closeDiscussionActions, DiscussionActionHint, DiscussionActions } from "./DiscussionActions";
import { DiscussionReattach, type DiscussionSourceConnection } from "./DiscussionReattach";

type ThreadAction = { kind: "edit"; comment: DiscussionComment; body: string } |
  { kind: "retract" | "purge"; comment: DiscussionComment } | { kind: "reattach"; pageId: string };
type ActionKind = ThreadAction["kind"] | "resolve" | "reopen";
type SuppressedComment = { kind: "edit" | "retract" | "purge"; detailUpdates: number };

function OriginalPassage({ quote }: { quote: string }) {
  const quoteId = useId();
  const [expanded, setExpanded] = useState(false);
  const long = quote.length > 280 || quote.split("\n").length > 6;
  return <>
    <blockquote id={quoteId} className={`pb-discussion-quote ${long && !expanded ? "pb-discussion-quote-preview" : ""}`}>{quote}</blockquote>
    {long && <button type="button" className="pb-discussion-action pb-discussion-quiet pb-discussion-passage-toggle"
      aria-expanded={expanded} aria-controls={quoteId} onClick={() => setExpanded((current) => !current)}>
      {expanded ? "Collapse passage" : "Show full passage"}</button>}
  </>;
}

export function DiscussionThread({ root, id, inPanel = false, source, creationPending = false, onActionChange, active = true, onPassageChange, drafts }: {
  drafts?: Map<string, string>;
  active?: boolean; onPassageChange?: (detail: DiscussionDetail | null) => void;
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
  const replyDraftKey = JSON.stringify([root, id, "reply"]);
  const [body, setBody] = useDiscussionDraft(drafts, replyDraftKey);
  const [composing, setComposing] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [errorNeedsRefresh, setErrorNeedsRefresh] = useState(false);
  const [refreshFailed, setRefreshFailed] = useState<string | null>(null);
  const [action, setAction] = useState<ThreadAction | null>(null);
  // Page panels supply their surviving map; a direct thread keeps edit text only for its own lifetime.
  const localEditDrafts = useRef(new Map<string, string>());
  const editDrafts = drafts ?? localEditDrafts.current;
  const [restoredEdit, setRestoredEdit] = useState(false);
  const [writing, setWriting] = useState(false);
  const [inspectRequired, setInspectRequired] = useState(false);
  const [suppressed, setSuppressed] = useState<Record<string, SuppressedComment>>({});
  const origin = useRef<{ button: HTMLButtonElement; trigger: HTMLElement } | null>(null);
  const threadTrigger = useRef<HTMLElement>(null);
  const replyButton = useRef<HTMLButtonElement>(null);
  const recoveryRef = useRef<HTMLButtonElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const client = useQueryClient();
  const session = useQuery(sessionQuery);
  useEffect(() => { if (inPanel) focusDiscussionElement(heading.current); }, [inPanel, id]);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; activityCallback.current?.(false, false); };
  }, []);
  useEffect(() => { if (action?.kind === "retract" || action?.kind === "purge") focusDiscussionElement(cancelButton.current); }, [action?.kind]);
  const tree = useQuery(treeQuery);
  const configuredDisabled = rootDiscussionsDisabled(tree.data?.roots, root);
  const query = useInfiniteQuery({ ...discussionDetailQuery(root, id), enabled: active && (!!tree.data || tree.isError) && !configuredDisabled });
  useDiscussionRefresh(query, active && (!!tree.data || tree.isError) && !configuredDisabled && !query.data?.pages.some((page) => !page.discussions_available),
    () => posting.current || creationPending || inspection.current);
  const replyMutation = useMutation({ mutationFn: (submitted: string) => addDiscussionComment(root, id, submitted), retry: false });
  useEffect(() => { onActionChange?.(!!action || composing || inspectRequired, writing || replyMutation.isPending); },
    [action, composing, inspectRequired, writing, replyMutation.isPending, onActionChange]);
  const first = query.data?.pages[0];
  const unavailable = query.data?.pages.find((page) => !page.discussions_available);
  const knownDisabled = configuredDisabled || unavailable?.reason === "disabled_by_config";
  useEffect(() => {
    if (knownDisabled) { setAction(null); setComposing(false); origin.current = null; }
  }, [knownDisabled]);
  const detail = knownDisabled ? null : first?.discussion;
  const passageCallback = useRef(onPassageChange);
  passageCallback.current = onPassageChange;
  useEffect(() => {
    passageCallback.current?.(active && query.isSuccess && !query.isRefetchError && !query.isFetching ? detail ?? null : null);
    return () => passageCallback.current?.(null);
  }, [active, root, id, detail, query.isSuccess, query.isRefetchError, query.isFetching]);
  const title = detail?.page.path ? `Discussion on ${detail.page.path}` : "Discussion";
  const seen = new Set<string>();
  const comments = configuredDisabled || unavailable ? [] : query.data?.pages.flatMap((page) => page.comments.filter((comment) => {
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
  const reattachDisabled = actionDisabled || localDisabled || (competing ? "Finish the current action before reattaching." :
    detail?.status === "resolved" ? "Reopen this discussion before reattaching." :
    detail?.status !== "open" ? "This discussion cannot currently be reattached." :
    !detail.page.id ? "This discussion has no stored source page ID for reattachment." :
    inPanel && (!source || source.root !== root || source.pageId !== detail.page.id)
      ? "Open full discussion to reattach using its stored source page." : null);

  function begin(next: ThreadAction, button: HTMLButtonElement) {
    if (posting.current || creationPending || inspection.current || action || composing ||
      (next.kind === "purge" ? accessDisabled : actionDisabled) || "comment" in next && suppressed[next.comment.id]) return;
    origin.current = { button, trigger: closeDiscussionActions(button) };
    setRestoredEdit(next.kind === "edit" && editDrafts.has(editDraftKey(next.comment.id)));
    setAction(next.kind === "edit" ? { ...next, body: editDrafts.get(editDraftKey(next.comment.id)) ?? next.body } : next);
    setError(null); setStatus(null);
  }

  function editDraftKey(commentId: string) { return JSON.stringify([root, id, "edit", commentId]); }

  function cancelAction() {
    if (posting.current) return;
    if (action?.kind === "edit") editDrafts.delete(editDraftKey(action.comment.id));
    setAction(null); setError(null);
    const initiating = origin.current;
    requestAnimationFrame(() => {
      if (focusDiscussionElement(initiating?.button)) return;
      if (focusDiscussionElement(initiating?.trigger)) return;
      focusDiscussionElement(heading.current);
    });
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
      if (validation) { setError(validation); setErrorNeedsRefresh(false); return; }
    }
    if (kind === "reattach" && (action?.kind !== "reattach" || !anchor || detail?.status !== "open" || detail.page.id !== action.pageId)) return;
    const submitted = { root, id, action, anchor,
      editDraftKey: action?.kind === "edit" ? editDraftKey(action.comment.id) : null };
    const commentId = submitted.action && "comment" in submitted.action ? submitted.action.comment.id : null;
    posting.current = true; activityCallback.current?.(true, true); setWriting(true); setStatus(null); setError(null);
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
      if (kind === "edit" && submitted.action?.kind === "edit" && submitted.editDraftKey)
        retireDiscussionDraft(editDrafts, submitted.editDraftKey, submitted.action.body);
      if (commentId && (kind === "retract" || kind === "purge"))
        editDrafts.delete(JSON.stringify([submitted.root, submitted.id, "edit", commentId]));
      if (alive.current) {
        if (commentId && (kind === "edit" || kind === "retract" || kind === "purge")) {
          const detailUpdates = client.getQueryState(discussionDetailQuery(submitted.root, submitted.id).queryKey)?.dataUpdateCount ?? 0;
          setSuppressed((current) => ({ ...current, [commentId]: { kind, detailUpdates } }));
        }
        setAction(null); setStatus(success.text); setRefreshFailed(null);
        focusDiscussionElement(kind === "resolve" || kind === "reopen" ? threadTrigger.current : heading.current);
      }
      try { await refreshDiscussionViews(client, submitted.root, submitted.id); }
      catch { if (alive.current) setRefreshFailed(success.verb); }
    } catch (failure) {
      if (alive.current) {
        setError(discussionActionError(failure, kind));
        setErrorNeedsRefresh(kind !== "edit" || !!discussionWriteRecovery(failure, "edit"));
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
    if (validation) { setError(validation); setErrorNeedsRefresh(false); return; }
    const submitted = { root, id, body, draftKey: replyDraftKey };
    posting.current = true; activityCallback.current?.(true, true); setWriting(true); setStatus("Posting…"); setError(null);
    try {
      const result = await replyMutation.mutateAsync(submitted.body);
      retireDiscussionDraft(drafts, submitted.draftKey, submitted.body);
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
        setErrorNeedsRefresh(!!discussionWriteRecovery(failure));
        if (!(failure instanceof ApiError) || failure.status >= 500) { inspection.current = true; setInspectRequired(true); }
        if (failure instanceof ApiError && ["discussion_changed", "stale_discussion", "discussion_resolved", "comment_retracted"].includes(failure.code)) {
          void query.refetch();
        }
      }
    } finally { posting.current = false; if (alive.current) setWriting(false); }
  }
  const recovery = query.isRefetchError || inspectRequired || !!refreshFailed || !!error && errorNeedsRefresh;
  const inlineRecovery = recovery && !!error && (composing || action?.kind === "edit") && !refreshFailed;
  const recoveryButton = <button ref={recoveryRef} type="button" className="pb-discussion-action" disabled={busy || query.isFetching}
    onClick={() => void refresh()}>Refresh</button>;
  if (configuredDisabled || unavailable?.reason === "disabled_by_config") return <ReadWindow>
    <Link to="/discussions" className="text-sm text-link">All roots</Link>
    <DiscussionAvailability reason="disabled_by_config" />
  </ReadWindow>;
  return <ReadWindow>
    {!inPanel && <Link to="/discussions/$root" params={{ root }} className="text-sm text-link">Discussions in {root}</Link>}
    <div className="pb-discussion-thread-header">
    <div className="pb-discussion-thread-title">
    {inPanel ?
      <h3 ref={heading} className="pb-discussion-heading" tabIndex={-1} aria-label={title}>Conversation</h3> :
      <h1 ref={heading} className="text-3xl font-bold break-words" tabIndex={-1}>{title}</h1>}
    {detail && <div className="pb-discussion-thread-status"><DiscussionStatus status={detail.status} />
      <DiscussionState item={{ ...detail, status: null }} /></div>}
    </div>
    {detail && <DiscussionActions label="Discussion actions" triggerRef={threadTrigger}>
      {detail.status && <button type="button" className="pb-discussion-action pb-discussion-quiet" disabled={competing || !!actionDisabled || !!localDisabled}
        onClick={(event) => { focusDiscussionElement(closeDiscussionActions(event.currentTarget));
          void submitAction(detail.status === "resolved" ? "reopen" : "resolve"); }}>
        {detail.status === "resolved" ? "Reopen discussion" : "Resolve discussion"}</button>}
      {detail.anchor?.kind === "quote" && <DiscussionActionHint label="Reattach" reason={reattachDisabled}>
        <button type="button" className="pb-discussion-action pb-discussion-quiet"
          disabled={!!reattachDisabled}
          onClick={(event) => { if (detail.page.id) begin({ kind: "reattach", pageId: detail.page.id }, event.currentTarget); }}>Reattach</button>
      </DiscussionActionHint>}
      {inPanel && <Link to="/discussions/$root/$id" params={{ root, id }} className="pb-discussion-action pb-discussion-quiet">Open full discussion</Link>}
    </DiscussionActions>}
    </div>
    {query.isPending && <p role="status">Loading discussion…</p>}
    {query.isError && !query.data && (query.error instanceof ApiError && query.error.code === "discussion_not_found" ?
      <p role="alert">Discussion not found</p> :
      <DiscussionReadError error={query.error} retry={() => void query.refetch()} />)}
    {unavailable && <DiscussionAvailability reason={unavailable.reason} />}
    {first?.discussions_available && !detail && <p role="alert">Discussion details are unavailable. Try refreshing.</p>}
    {detail && <>
      {query.isRefetchError && <p role="alert">Refresh failed. Showing earlier discussion content; retry before relying on it.</p>}
      {actionDisabled && <p className="pb-discussion-notice">{actionDisabled}</p>}
      {detail.anchor && <section className="pb-discussion-context">
        <p className="pb-discussion-eyebrow">{detail.anchor.kind === "page" ? "About this page" : detail.reattachment ? "Original passage" : "Quoted passage"}</p>
        {detail.anchor.kind === "quote" && <OriginalPassage key={detail.anchor.quote} quote={detail.anchor.quote} />}
        {detail.reattachment && <p className="pb-discussion-hint">Newer attachment available in discussion details below.</p>}
      </section>}
      {(comments.length > 0 || detail.state !== "incomplete" && detail.state !== "unreadable") && <section className="pb-discussion-comments space-y-4">
        {inPanel ? <h4 className="pb-discussion-eyebrow">Comments</h4> : <h2 className="pb-discussion-eyebrow">Comments</h2>}
        {comments.length === 0 && <p>{query.hasNextPage ? "No comments in this window. Load more to continue." : "No comments yet."}</p>}
        {comments.map((comment) => <article key={comment.id} className="pb-discussion-comment">
          <div className="pb-discussion-comment-header">
            <p className="pb-discussion-meta"><strong className="pb-discussion-author"><DiscussionAvatar label={comment.author.label} />{comment.author.label}{comment.author.kind === "agent" ? " · Agent" : ""}</strong>
              <time dateTime={comment.created} title={comment.created}>{formatDiscussionTime(comment.created)}</time>
              {comment.edited_at && <span>Edited <time dateTime={comment.edited_at} title={comment.edited_at}>{formatDiscussionTime(comment.edited_at)}</time></span>}
              {comment.retracted && <span>Retracted</span>}</p>
          {!suppressed[comment.id] && <DiscussionActions label={`Actions for ${comment.author.label}'s comment`}>
            {!comment.retracted && <>
              <button type="button" className="pb-discussion-action pb-discussion-quiet" disabled={competing || !!actionDisabled || !!localDisabled}
                onClick={(event) => begin({ kind: "edit", comment, body: comment.markdown }, event.currentTarget)}>Edit comment</button>
              <button type="button" className="pb-discussion-action pb-discussion-quiet pb-discussion-danger" disabled={competing || !!actionDisabled || !!localDisabled}
                onClick={(event) => begin({ kind: "retract", comment }, event.currentTarget)}>Retract comment</button>
            </>}
            <button type="button" className="pb-discussion-action pb-discussion-quiet pb-discussion-danger" disabled={competing || !!accessDisabled || !!localDisabled}
              onClick={(event) => begin({ kind: "purge", comment }, event.currentTarget)}>
              {session.data?.auth_mode === "off" ? "Purge comment" : "Purge comment (admin)"}</button>
          </DiscussionActions>}
          </div>
          {suppressed[comment.id] ?
            <p>Comment {suppressed[comment.id].kind === "purge" ? "removed" : suppressed[comment.id].kind === "retract" ? "retracted" : "saved"}; refresh to update this view.</p> :
            <div className="pb-discussion-body" dangerouslySetInnerHTML={{ __html: comment.html }} />}
        </article>)}
        {query.isFetchNextPageError && <p role="alert">Could not load more comments. Earlier comments remain available.</p>}
        {query.hasNextPage && <button type="button" className="pb-discussion-action" disabled={query.isFetching} onClick={() => void query.fetchNextPage({ cancelRefetch: false })}>
          {query.isFetchingNextPage ? "Loading…" : "Load more"}
        </button>}
      </section>}
    </>}
    {action?.kind === "reattach" && <DiscussionReattach root={root} pageId={action.pageId} source={inPanel ? source : undefined}
      originalQuote={detail?.anchor?.kind === "quote" ? detail.anchor.quote : ""} busy={busy}
      disabled={actionDisabled || localDisabled || (detail?.page.id !== action.pageId ? "The stored source identity changed. Cancel and review this discussion." : null)}
      open={detail?.status === "open"} submit={(anchor) => submitAction("reattach", anchor)} cancel={cancelAction}
      clearSubmitError={() => setError(null)} />}
    {action?.kind === "edit" && <div data-pb-active-action>
      {restoredEdit && <p role="status">Restored unsaved edit. Compare it with the current comment before saving.</p>}
      <DiscussionComposer body={action.body}
        setBody={(value) => {
          if (action?.kind === "edit") editDrafts.set(editDraftKey(action.comment.id), value);
          setAction((current) => current?.kind === "edit" ? { ...current, body: value } : current);
        }}
        submit={() => void submitAction("edit")} cancel={cancelAction} busy={busy}
        disabled={actionDisabled || targetDisabled || localDisabled} error={error} submitLabel="Save comment" onEscape={() => setError(null)}
        recoveryAction={inlineRecovery ? recoveryButton : undefined} /></div>}
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
        <button type="button" className="pb-discussion-action pb-discussion-danger" disabled={writing || !!(action.kind === "purge" ? accessDisabled : actionDisabled) || !!targetDisabled || !!localDisabled}
          onClick={() => void submitAction(action.kind)}>{action.kind === "purge" ? "Purge comment" : "Retract comment"}</button>
        <button ref={cancelButton} type="button" className="pb-discussion-action" data-pb-action-focus disabled={writing} onClick={cancelAction}>Cancel</button>
      </div>
    </section>}
    {first?.discussions_available && detail?.status === "open" && detail.state !== "incomplete" && detail.state !== "unreadable" && !composing &&
      <button ref={replyButton} type="button" className={`pb-discussion-action ${competing ? "pb-discussion-quiet" : "pb-discussion-primary"}`} disabled={competing || !!disabled || !!localDisabled} onClick={() => {
        if (posting.current || creationPending || inspection.current || disabled || action || composing) return;
        setComposing(true); setError(null); setStatus(null);
      }}>Reply</button>}
    {composing && <DiscussionComposer body={body} setBody={setBody} submit={() => void reply()}
      cancel={() => { if (posting.current) return; setComposing(false); setBody(""); setError(null);
        requestAnimationFrame(() => {
          if (focusDiscussionElement(replyButton.current)) return;
          if (focusDiscussionElement(recoveryRef.current)) return;
          focusDiscussionElement(heading.current);
        }); }} busy={busy}
      disabled={disabled || localDisabled} error={error} status={status} submitLabel="Post reply" onEscape={() => setError(null)}
      recoveryAction={inlineRecovery ? recoveryButton : undefined} />}
    {!action && !composing && localDisabled && <p role="alert">{localDisabled}</p>}
    {!composing && status && <p role="status" aria-label={status}>{status}</p>}
    {!composing && action?.kind !== "edit" && error && <p role="alert">{error}</p>}
    {refreshFailed && <p role="alert">{refreshFailed}, but the view could not refresh. <button ref={recoveryRef} type="button" className="pb-discussion-action"
      disabled={busy || query.isFetching} onClick={() => void refresh()}>Refresh</button></p>}
    {(recovery || !detail) && !refreshFailed && !inlineRecovery && recoveryButton}
    {detail && <details className="pb-discussion-history"><summary>Discussion details</summary>
      <div className="space-y-3">
        <details><summary>Discussion ID</summary><code>{id}</code></details>
        <DiscussionSummary item={detail} root={root} showState={false} />
        <DiscussionAnchor label="Original anchor" anchor={detail.anchor} inPanel={inPanel} showQuote={false} />
        {detail.reattachment && <section className="space-y-2">
          <p className="pb-discussion-hint">Reattached by {detail.reattachment.by.label} at <time dateTime={detail.reattachment.at} title={detail.reattachment.at}>{formatTime(detail.reattachment.at)}</time></p>
          <DiscussionAnchor label="Latest reattachment" anchor={detail.reattachment.anchor} inPanel={inPanel} />
        </section>}
        <details><summary>Who can take action?</summary><p>{session.data?.auth_mode === "off" ? "Local access allows discussion actions without signing in." :
          "Only the comment author can edit or retract. The starter or an editor, including an admin, can change status or reattach."}</p></details>
      </div>
    </details>}
  </ReadWindow>;
}
