import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useCallback, useEffect, useId, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { pageDiscussionsQuery, previewDiscussionAnchor, refreshDiscussionViews, startDiscussion } from "../api/discussions";
import { sessionQuery } from "../api/queries";
import { useDiscussionRefresh } from "../lib/useDiscussionRefresh";
import { focusDiscussionElement } from "../lib/discussionFocus";
import type { DiscussionDetail, DiscussionPreviewResponse, DiscussionQuoteRequestAnchor, DiscussionRequestAnchor } from "../api/types";
import { DiscussionComposer, commentValidation, discussionPreviewError, discussionWriteError, discussionWriteRecovery } from "./DiscussionComposer";
import { DiscussionAvailability, DiscussionFilters, type DiscussionStatusFilter, DiscussionListRows, DiscussionReadError, uniqueDiscussionItems } from "./DiscussionRead";
import { DiscussionThread } from "./DiscussionThread";
import type { DiscussionSourceConnection } from "./DiscussionReattach";

export interface PassageRequest { nonce: number; root: string; pageId: string; anchor: DiscussionQuoteRequestAnchor }

export function DiscussionPanel({ root, pageId, sourceHash, sourceReady, sourceBusy, request, pageRequestNonce,
  onReselect, onReload, onPostingChange, source, onActionChange, onStart, startDisabled, active = true, onPassageChange }: {
  active?: boolean; onPassageChange?: (detail: DiscussionDetail | null) => void;
  root: string; pageId: string; sourceHash: string | null; sourceReady: boolean; sourceBusy: boolean;
  request: PassageRequest | null; pageRequestNonce: number; onReselect: () => void; onReload: () => Promise<boolean>;
  onPostingChange: (busy: boolean, owner: string) => void;
  source?: DiscussionSourceConnection; onActionChange?: (active: boolean, owner: string) => void;
  onStart: () => void; startDisabled: boolean;
}) {
  const owner = useId();
  const [threadActive, setThreadActive] = useState(false);
  const [threadWriting, setThreadWriting] = useState(false);
  const threadAction = useRef(false);
  const threadBusy = useRef(false);
  const activityCallback = useRef(onActionChange);
  const postingCallback = useRef(onPostingChange);
  activityCallback.current = onActionChange; postingCallback.current = onPostingChange;
  const publishActivity = useCallback(() => {
    if (!alive.current) return;
    const busy = posting.current || threadBusy.current;
    activityCallback.current?.(threadAction.current || busy, owner); postingCallback.current(busy, owner);
  }, [owner]);
  const threadActivity = useCallback((active: boolean, busy: boolean) => {
    if (!alive.current) return;
    setThreadActive(active); setThreadWriting(busy); threadAction.current = active; threadBusy.current = busy;
    publishActivity();
  }, [publishActivity]);
  const [statusFilter, setStatusFilter] = useState<DiscussionStatusFilter>("all");
  const [selected, setSelected] = useState<string | null>(null);
  const [mode, setMode] = useState<"page" | "quote" | null>(null);
  const [body, setBody] = useState("");
  const [capture, setCapture] = useState<DiscussionQuoteRequestAnchor | null>(null);
  const [preview, setPreview] = useState<DiscussionPreviewResponse | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [creationRecovery, setCreationRecovery] = useState<"inspect" | "refresh" | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [refreshFailed, setRefreshFailed] = useState(false);
  const [pageChanged, setPageChanged] = useState(false);
  const [passageFallback, setPassageFallback] = useState(false);
  const [focusKey, setFocusKey] = useState(0);
  const [writing, setWriting] = useState(false);
  const panel = useRef<HTMLElement>(null);
  const confirmButton = useRef<HTMLButtonElement>(null);
  const generation = useRef(0);
  const posting = useRef(false);
  const lastHash = useRef(sourceHash);
  const alive = useRef(true);
  const ready = useRef(sourceReady);
  ready.current = sourceReady;
  const preparedNonce = useRef<number | null>(null);
  const pageModeNonce = useRef(0);
  const client = useQueryClient();
  const query = useInfiniteQuery({ ...pageDiscussionsQuery(root, pageId), enabled: active });
  const inspection = useRef(false);
  inspection.current = creationRecovery === "inspect";
  useDiscussionRefresh(query, active && query.data?.pages[0]?.discussions_available !== false,
    () => posting.current || threadBusy.current || inspection.current || threadAction.current);
  const session = useQuery(sessionQuery);
  const first = query.data?.pages[0];
  const latest = query.data?.pages.at(-1);
  const items = uniqueDiscussionItems(query.data?.pages ?? []);
  const visibleItems = items.filter((item) => creationRecovery === "inspect" || statusFilter === "all" || item.status === statusFilter);
  const previewMutation = useMutation({ mutationFn: (anchor: DiscussionQuoteRequestAnchor) => previewDiscussionAnchor(root, pageId, anchor), retry: false });
  const startMutation = useMutation({ mutationFn: (input: { anchor: DiscussionRequestAnchor; body: string }) =>
    startDiscussion(root, pageId, input.anchor, input.body), retry: false });

  useEffect(() => {
    alive.current = true;
    publishActivity();
    return () => {
      alive.current = false;
      activityCallback.current?.(false, owner); postingCallback.current(false, owner);
    };
  }, [owner, publishActivity]);
  useEffect(() => {
    if (posting.current) return;
    if (sourceHash === null || lastHash.current === sourceHash) return;
    lastHash.current = sourceHash;
    generation.current++;
    setCapture(null); setPreview(null); setConfirmed(false);
    setPageChanged(false);
    if (mode === "quote") setError("The page changed. Reselect the passage and preview it again.");
    else if (pageChanged) setError(null);
  }, [sourceHash, mode, pageChanged, writing]);
  useEffect(() => {
    if (preview) focusDiscussionElement(confirmButton.current);
  }, [preview]);

  async function prepare(anchor: DiscussionQuoteRequestAnchor) {
    if (posting.current || threadBusy.current || threadActive) return;
    if (pageChanged) { setError("The page changed. Reload it before selecting a passage."); return; }
    const current = ++generation.current;
    setSelected(null);
    setMode("quote"); setCapture(anchor); setPreview(null); setConfirmed(false); setError(null); setStatus(null); setPassageFallback(false);
    setCreationRecovery(null);
    setFocusKey((key) => key + 1);
    try {
      const result = await previewMutation.mutateAsync(anchor);
      if (!alive.current || !ready.current || current !== generation.current || sourceHash !== anchor.content_hash) return;
      if (result.content_hash !== anchor.content_hash) {
        setPageChanged(true); setCapture(null); setPreview(null); setConfirmed(false);
        setError("The page changed. Reload and reselect the passage.");
        return;
      }
      setPreview(result);
    } catch (failure) {
      if (alive.current && ready.current && current === generation.current) {
        if (failure instanceof ApiError && failure.code === "page_changed") {
          setPageChanged(true);
          setCapture(null); setPreview(null); setConfirmed(false);
        }
        setPassageFallback(failure instanceof ApiError && ["anchor_too_large", "anchor_not_found", "invalid_anchor"].includes(failure.code));
        setError(discussionPreviewError(failure));
      }
    }
  }

  useEffect(() => {
    if (request && preparedNonce.current !== request.nonce) {
      preparedNonce.current = request.nonce;
      void prepare(request.anchor);
    }
    // The nonce is explicit user activation; editing the body must not re-preview.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request?.nonce]);

  useEffect(() => {
    if (pageRequestNonce && pageModeNonce.current !== pageRequestNonce) {
      pageModeNonce.current = pageRequestNonce;
      pageMode();
    }
    // The nonce is an explicit user request to discuss the whole page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pageRequestNonce]);

  function pageMode() {
    if (posting.current || threadBusy.current || threadActive) return;
    generation.current++;
    setSelected(null);
    setMode("page"); setCapture(null); setPreview(null); setConfirmed(false); setError(null); setStatus(null); setPassageFallback(false);
    setCreationRecovery(null);
    setFocusKey((key) => key + 1);
  }

  function cancel() {
    if (posting.current) return;
    generation.current++;
    setMode(null); setBody(""); setCapture(null); setPreview(null); setConfirmed(false); setError(null); setStatus(null); setPassageFallback(false);
    setCreationRecovery(null);
    requestAnimationFrame(() => {
      if (focusDiscussionElement(panel.current?.querySelector<HTMLElement>("[data-pb-new-discussion]"))) return;
      focusDiscussionElement(panel.current);
    });
  }

  function retryViewRefresh() {
    const submitted = { root, id: selected ?? undefined };
    void refreshDiscussionViews(client, submitted.root, submitted.id)
      .then(() => { if (alive.current) setRefreshFailed(false); })
      .catch(() => { if (alive.current) setRefreshFailed(true); });
  }

  async function reloadSource() {
    if (sourceBusy || posting.current) return;
    const recovered = await onReload();
    if (!alive.current || !recovered) return;
    setPageChanged(false);
    if (pageChanged && mode === "quote") {
      generation.current++;
      setCapture(null); setPreview(null); setConfirmed(false);
      setError("The page is ready. Reselect the passage and preview it again.");
    } else if (pageChanged) setError(null);
  }

  async function create() {
    if (posting.current || pageChanged || !sourceHash || !sourceReady || !first?.discussions_available || query.isRefetchError) return;
    const validation = commentValidation(body);
    if (validation) { setError(validation); setCreationRecovery(null); return; }
    if (mode === "quote" && (!capture || !preview || !confirmed || capture.content_hash !== sourceHash)) return;
    if (mode !== "page" && mode !== "quote") return;
    const anchor: DiscussionRequestAnchor = mode === "quote" ? capture! : { kind: "page", content_hash: sourceHash };
    const submitted = { root, pageId, anchor, body };
    posting.current = true; setWriting(true); publishActivity();
    setError(null); setCreationRecovery(null); setStatus("Posting…"); setRefreshFailed(false);
    try {
      const result = await startMutation.mutateAsync({ anchor: submitted.anchor, body: submitted.body });
      if (alive.current) {
        setBody((current) => current === submitted.body ? "" : current);
        setMode(null); setCapture(null); setPreview(null); setConfirmed(false);
        setSelected(result.id);
        setStatus("Discussion created");
      }
      try { await refreshDiscussionViews(client, submitted.root, result.id); }
      catch { if (alive.current) setRefreshFailed(true); }
    } catch (failure) {
      if (alive.current) {
        setStatus(null); setError(discussionWriteError(failure));
        inspection.current = discussionWriteRecovery(failure) === "inspect";
        setCreationRecovery(discussionWriteRecovery(failure));
        setPassageFallback(mode === "quote" && failure instanceof ApiError &&
          ["anchor_too_large", "anchor_not_found", "invalid_anchor"].includes(failure.code));
        if (failure instanceof ApiError && failure.code === "page_changed") {
          setPageChanged(true);
          generation.current++; setCapture(null); setPreview(null); setConfirmed(false);
        }
      }
    } finally {
      posting.current = false;
      if (alive.current) { setWriting(false); publishActivity(); }
    }
  }

  const knownSignIn = session.data?.auth_mode !== "off" && session.data?.authenticated === false;
  const confirmedEmpty = first?.discussions_available && items.length === 0 && !query.hasNextPage && !query.isRefetchError && !creationRecovery;
  const disabled = pageChanged ? "Reload the page and review it before posting." :
    !sourceReady ? (sourceBusy ? "The page is loading. Wait before posting." : "The page could not be read. Reload it before posting.") :
    query.isPending ? "Checking whether discussions are available…" :
    query.isError || query.isRefetchError ? "Refresh discussions before posting." :
    first?.discussions_available === false ? "Discussions are unavailable on this page." :
    knownSignIn ? "Sign in before posting. Your draft will stay here." : null;

  return <section ref={panel} tabIndex={-1} className="pb-discussion-panel min-w-0 space-y-4" aria-label="Page discussions" data-pb-discussion-panel>
    {!selected && <>
      {!mode && <div className="pb-discussion-list-toolbar">
        <div className="pb-discussion-toolbar">
          {first?.discussions_available && <button type="button" className="pb-discussion-action pb-discussion-primary"
            data-pb-new-discussion disabled={startDisabled} onClick={onStart}>Start a discussion</button>}
        </div>
      </div>}
      {mode && <section className="space-y-3" aria-label={mode === "page" ? "New page discussion" : "New passage discussion"}>
        <h3 className="pb-discussion-heading">{mode === "page" ? "New page discussion" : "Discuss selected passage"}</h3>
        {mode === "page" ? <p className="pb-discussion-hint">Share a question or thought about this page.</p> : <>
          <p className="pb-discussion-hint">Review the passage, confirm it, then add your comment.</p>
          {previewMutation.isPending && <p role="status">Preparing passage preview…</p>}
          {preview && <div className="pb-discussion-evidence space-y-2">
            <p className="font-semibold">Passage preview</p>
            {preview.selection === "snapped" && <p className="font-semibold">Whole block selected</p>}
            <blockquote className="pb-discussion-quote">{preview.quote_text}</blockquote>
            <button ref={confirmButton} type="button" className={`pb-discussion-action ${confirmed ? "pb-discussion-quiet" : "pb-discussion-primary"}`} onClick={() => {
              if (posting.current) return;
              setConfirmed(true); setError(null);
              requestAnimationFrame(() => focusDiscussionElement(panel.current?.querySelector<HTMLTextAreaElement>("textarea")));
            }}
              onKeyDown={(event) => { if (event.key === "Escape" && !posting.current) {
                event.preventDefault(); setPreview(null); setConfirmed(false); setError(null);
                focusDiscussionElement(panel.current?.querySelector<HTMLTextAreaElement>("textarea"));
              } }}
              disabled={confirmed || writing}>{confirmed ? "Passage confirmed" : "Confirm passage"}</button>
          </div>}
          <button type="button" className="pb-discussion-action pb-discussion-quiet" disabled={writing || !sourceReady || sourceBusy} onClick={onReselect}>Reselect</button>
          {passageFallback && <button type="button" className="pb-discussion-action" disabled={writing} onClick={pageMode}>Discuss the whole page instead</button>}
        </>}
        {(pageChanged || !sourceReady) && <button type="button" className="pb-discussion-action"
          aria-busy={sourceBusy} aria-disabled={sourceBusy || writing}
          onClick={() => void reloadSource()}>
          {sourceBusy ? "Reloading page…" : "Reload page"}
        </button>}
        <DiscussionComposer body={body} setBody={setBody} submit={() => void create()} cancel={cancel} busy={writing}
          disabled={disabled || (mode === "quote" && !confirmed
            ? preview ? "Confirm the passage before creating a discussion." :
              previewMutation.isPending ? "Wait for the passage preview before posting." :
                "Reselect the passage and preview it before posting."
            : null)}
          status={status} error={error} submitLabel="Create discussion" focusKey={focusKey}
          recoveryAction={creationRecovery && <button type="button" className="pb-discussion-action pb-discussion-quiet"
            disabled={query.isFetching || writing} onClick={() => void query.refetch()}>Refresh</button>}
          onEscape={() => { if (posting.current) return; setPreview(null); setConfirmed(false);
            if (creationRecovery !== "inspect") setError(null); }} />
      </section>}
    </>}
    {status === "Discussion created" && selected && <p role="status" aria-label="Discussion created">Discussion created. <Link to="/discussions/$root/$id" params={{ root, id: selected }} className="text-link">Open full discussion</Link></p>}
    {refreshFailed && <p role="alert">Posted, but the view could not refresh. <button type="button" className="pb-discussion-action" onClick={retryViewRefresh}>Refresh</button></p>}
    {selected ? <>
      <button type="button" className="pb-discussion-action pb-discussion-quiet pb-discussion-back" aria-label="Close discussion"
        disabled={writing || threadActive || threadWriting} onClick={() => {
        if (posting.current || threadBusy.current || threadAction.current) return;
        const id = selected;
        setSelected(null);
        requestAnimationFrame(() => focusDiscussionElement(Array.from(panel.current?.querySelectorAll<HTMLButtonElement>("[data-pb-discussion-id]") ?? [])
          .find((button) => button.dataset.pbDiscussionId === id) ?? panel.current?.querySelector<HTMLButtonElement>('[aria-pressed="true"]') ?? panel.current));
      }}>← All discussions</button>
      <DiscussionThread key={`${root}/${selected}`} root={root} id={selected} inPanel source={source} active={active} onPassageChange={onPassageChange} creationPending={writing} onActionChange={threadActivity} />
      {threadActive && <p className="pb-discussion-hint">Finish this action before returning to the list.</p>}
      {first?.discussions_available && <button type="button" className="pb-discussion-action pb-discussion-quiet"
        data-pb-new-discussion disabled={startDisabled} onClick={onStart}>Start a discussion</button>}
    </> : <>
      {query.isPending && <p role="status">Loading page discussions…</p>}
      {query.isError && !query.data && <DiscussionReadError error={query.error} retry={() => void query.refetch()} />}
      {first && !first.discussions_available && <DiscussionAvailability reason={first.reason} />}
      {first?.discussions_available && <>
        {!mode && !confirmedEmpty && <DiscussionFilters value={statusFilter} onChange={setStatusFilter} more={!!query.hasNextPage} />}
        {query.isRefetchError && <p role="alert">Refresh failed. Showing earlier discussions.
          <button type="button" className="pb-discussion-action" disabled={query.isRefetching} onClick={() => void query.refetch()}>Retry</button></p>}
        {latest?.discussions.length === 0 && query.hasNextPage && <p>No discussions in this window. Load more to continue.</p>}
        {mode && creationRecovery === "inspect" && items.length > 0 && <p className="pb-discussion-eyebrow">Existing discussions</p>}
        {!mode && !confirmedEmpty && statusFilter !== "all" && visibleItems.length === 0 && <p>No {statusFilter} discussions in the loaded results.</p>}
        {(!mode || creationRecovery === "inspect") && <DiscussionListRows root={root} items={visibleItems} pageLocal onSelect={(id) => { if (!posting.current) setSelected(id); }} />}
        {query.isFetchNextPageError && <p role="alert">Could not load more discussions. Earlier rows remain available.</p>}
        {query.hasNextPage && (!mode || creationRecovery === "inspect") && <button type="button" className="pb-discussion-action" disabled={query.isFetching} onClick={() => void query.fetchNextPage({ cancelRefetch: false })}>
          {query.isFetchingNextPage ? "Loading…" : "Load more"}
        </button>}
      </>}
    </>}
  </section>;
}
