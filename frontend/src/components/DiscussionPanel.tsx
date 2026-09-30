import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { pageDiscussionsQuery, previewDiscussionAnchor, refreshDiscussionViews, startDiscussion } from "../api/discussions";
import { sessionQuery } from "../api/queries";
import type { DiscussionPreviewResponse, DiscussionQuoteRequestAnchor, DiscussionRequestAnchor } from "../api/types";
import { DiscussionComposer, commentValidation, discussionPreviewError, discussionWriteError } from "./DiscussionComposer";
import { DiscussionAvailability, DiscussionListRows, DiscussionReadError, uniqueDiscussionItems } from "./DiscussionRead";
import { DiscussionThread } from "./DiscussionThread";

export interface PassageRequest { nonce: number; root: string; pageId: string; anchor: DiscussionQuoteRequestAnchor }

export function DiscussionPanel({ root, pageId, sourceHash, sourceReady, sourceBusy, request, pageRequestNonce,
  onReselect, onReload, onClose, onPostingChange }: {
  root: string; pageId: string; sourceHash: string | null; sourceReady: boolean; sourceBusy: boolean;
  request: PassageRequest | null; pageRequestNonce: number; onReselect: () => void; onReload: () => Promise<boolean>; onClose: () => void;
  onPostingChange: (busy: boolean) => void;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const [mode, setMode] = useState<"page" | "quote" | null>(null);
  const [body, setBody] = useState("");
  const [capture, setCapture] = useState<DiscussionQuoteRequestAnchor | null>(null);
  const [preview, setPreview] = useState<DiscussionPreviewResponse | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
  const query = useInfiniteQuery(pageDiscussionsQuery(root, pageId));
  const session = useQuery(sessionQuery);
  const first = query.data?.pages[0];
  const latest = query.data?.pages.at(-1);
  const items = uniqueDiscussionItems(query.data?.pages ?? []);
  const previewMutation = useMutation({ mutationFn: (anchor: DiscussionQuoteRequestAnchor) => previewDiscussionAnchor(root, pageId, anchor), retry: false });
  const startMutation = useMutation({ mutationFn: (input: { anchor: DiscussionRequestAnchor; body: string }) =>
    startDiscussion(root, pageId, input.anchor, input.body), retry: false });

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);
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
    if (preview) confirmButton.current?.focus();
  }, [preview]);

  async function prepare(anchor: DiscussionQuoteRequestAnchor) {
    if (posting.current) return;
    if (pageChanged) { setError("The page changed. Reload it before selecting a passage."); return; }
    const current = ++generation.current;
    setSelected(null);
    setMode("quote"); setCapture(anchor); setPreview(null); setConfirmed(false); setError(null); setStatus(null); setPassageFallback(false);
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
    if (posting.current) return;
    generation.current++;
    setMode("page"); setCapture(null); setPreview(null); setConfirmed(false); setError(null); setStatus(null); setPassageFallback(false);
    setFocusKey((key) => key + 1);
  }

  function cancel() {
    if (posting.current) return;
    generation.current++;
    setMode(null); setBody(""); setCapture(null); setPreview(null); setConfirmed(false); setError(null); setStatus(null); setPassageFallback(false);
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
    if (validation) { setError(validation); return; }
    if (mode === "quote" && (!capture || !preview || !confirmed || capture.content_hash !== sourceHash)) return;
    if (mode !== "page" && mode !== "quote") return;
    const anchor: DiscussionRequestAnchor = mode === "quote" ? capture! : { kind: "page", content_hash: sourceHash };
    const submitted = { root, pageId, anchor, body };
    posting.current = true; setWriting(true); onPostingChange(true);
    setError(null); setStatus("Posting…"); setRefreshFailed(false);
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
        setPassageFallback(mode === "quote" && failure instanceof ApiError &&
          ["anchor_too_large", "anchor_not_found", "invalid_anchor"].includes(failure.code));
        if (failure instanceof ApiError && failure.code === "page_changed") {
          setPageChanged(true);
          generation.current++; setCapture(null); setPreview(null); setConfirmed(false);
        }
      }
    } finally {
      posting.current = false;
      if (alive.current) setWriting(false);
      onPostingChange(false);
    }
  }

  const knownSignIn = session.data?.auth_mode !== "off" && session.data?.authenticated === false;
  const disabled = pageChanged ? "Reload the page and review it before posting." :
    !sourceReady ? (sourceBusy ? "The page is loading. Wait before posting." : "The page could not be read. Reload it before posting.") :
    query.isPending ? "Checking whether discussions are available…" :
    query.isError || query.isRefetchError ? "Refresh discussions before posting." :
    first?.discussions_available === false ? "Discussions are unavailable on this page." :
    knownSignIn ? "Sign in before posting. Your draft will stay here." : null;

  return <section ref={panel} className="pb-discussion-panel min-w-0 space-y-4" aria-label="Page discussions" data-pb-discussion-panel>
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h2 className="text-xl font-bold">Discussions</h2>
      <button type="button" className="pb-discussion-action" onClick={onClose}>Back to page</button>
    </div>
    {!selected && <>
      {first?.discussions_available && !mode && <button type="button" className="pb-discussion-action" onClick={pageMode}>New page discussion</button>}
      {mode && <section className="space-y-3" aria-label={mode === "page" ? "New page discussion" : "New passage discussion"}>
        <h3 className="text-lg font-semibold">{mode === "page" ? "New page discussion" : "Discuss selected passage"}</h3>
        {mode === "page" ? <p>This discussion concerns the whole page.</p> : <>
          {previewMutation.isPending && <p role="status">Preparing passage preview…</p>}
          {preview && <div className="pb-discussion-evidence space-y-2">
            <p className="font-semibold">Passage preview</p>
            {preview.selection === "snapped" && <p className="font-semibold">Whole block selected</p>}
            <blockquote className="pb-discussion-quote">{preview.quote_text}</blockquote>
            <button ref={confirmButton} type="button" className="pb-discussion-action" onClick={() => {
              if (posting.current) return;
              setConfirmed(true); setError(null);
              requestAnimationFrame(() => panel.current?.querySelector<HTMLTextAreaElement>("textarea")?.focus());
            }}
              onKeyDown={(event) => { if (event.key === "Escape" && !posting.current) {
                event.preventDefault(); setPreview(null); setConfirmed(false); setError(null);
                panel.current?.querySelector<HTMLTextAreaElement>("textarea")?.focus();
              } }}
              disabled={confirmed || writing}>{confirmed ? "Passage confirmed" : "Confirm passage"}</button>
          </div>}
          <button type="button" className="pb-discussion-action" disabled={writing || !sourceReady} onClick={onReselect}>Reselect</button>
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
          onEscape={() => { if (posting.current) return; setPreview(null); setConfirmed(false); setError(null); }} />
      </section>}
    </>}
    {status === "Discussion created" && selected && <p role="status" aria-label="Discussion created">Discussion created. <Link to="/discussions/$root/$id" params={{ root, id: selected }} className="text-link">Open full discussion</Link></p>}
    {refreshFailed && <p role="alert">Posted, but the view could not refresh. <button type="button" className="pb-discussion-action" onClick={retryViewRefresh}>Refresh</button></p>}
    {selected ? <>
      <button type="button" className="pb-discussion-action" onClick={() => {
        const id = selected;
        setSelected(null);
        requestAnimationFrame(() => Array.from(panel.current?.querySelectorAll<HTMLButtonElement>("[data-pb-discussion-id]") ?? [])
          .find((button) => button.dataset.pbDiscussionId === id)?.focus());
      }}>Back to page discussions</button>
      <DiscussionThread key={`${root}/${selected}`} root={root} id={selected} inPanel />
    </> : <>
      <Link to="/discussions/$root" params={{ root }} className="text-sm text-link">All discussions in {root}</Link>
      <button type="button" className="pb-discussion-action" disabled={query.isRefetching} onClick={() => void query.refetch()}>Refresh</button>
      {query.isPending && <p role="status">Loading page discussions…</p>}
      {query.isError && !query.data && <DiscussionReadError error={query.error} retry={() => void query.refetch()} />}
      {first && !first.discussions_available && <DiscussionAvailability reason={first.reason} />}
      {first?.discussions_available && <>
        {query.isRefetchError && <p role="alert">Refresh failed. Showing earlier discussions.</p>}
        {items.length === 0 && !query.hasNextPage && <p>No discussions on this page.</p>}
        {latest?.discussions.length === 0 && query.hasNextPage && <p>No discussions in this window. Load more to continue.</p>}
        <DiscussionListRows root={root} items={items} onSelect={(id) => { if (!posting.current) setSelected(id); }} />
        {query.isFetchNextPageError && <p role="alert">Could not load more discussions. Earlier rows remain available.</p>}
        {query.hasNextPage && <button type="button" className="pb-discussion-action" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
          {query.isFetchingNextPage ? "Loading…" : "Load more"}
        </button>}
      </>}
    </>}
  </section>;
}
