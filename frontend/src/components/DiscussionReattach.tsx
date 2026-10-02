import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { previewDiscussionAnchor } from "../api/discussions";
import { pageHtmlQuery, pageQuery } from "../api/queries";
import type { DiscussionPreviewResponse, DiscussionQuoteRequestAnchor, PageResponse } from "../api/types";
import type { SelectionCapture } from "../lib/selectionAnchor";
import { useDiscussionSelection } from "../lib/useDiscussionSelection";
import { focusDiscussionElement } from "../lib/discussionFocus";
import { discussionActionError } from "./DiscussionComposer";
import { Prose } from "./Prose";

export interface DiscussionSourceConnection {
  root: string; pageId: string; hash: string | null; ready: boolean; busy: boolean;
  capture: () => SelectionCapture; resetSelection: () => void; reload: () => Promise<boolean>;
}

export function DiscussionReattach({ root, pageId, source, originalQuote, busy, disabled, open, submit, cancel, clearSubmitError }: {
  root: string; pageId: string; source?: DiscussionSourceConnection; originalQuote: string;
  busy: boolean; disabled: string | null; open: boolean;
  submit: (anchor: DiscussionQuoteRequestAnchor) => Promise<unknown>; cancel: () => void;
  clearSubmitError?: () => void;
}) {
  const wrapper = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const previewButton = useRef<HTMLButtonElement>(null);
  const confirmButton = useRef<HTMLButtonElement>(null);
  const submitButton = useRef<HTMLButtonElement>(null);
  const reloadButton = useRef<HTMLButtonElement>(null);
  const generation = useRef(0);
  const alive = useRef(true);
  const previewing = useRef(false);
  const [preparing, setPreparing] = useState(false);
  const [reloading, setReloading] = useState(false);
  const [capture, setCapture] = useState<DiscussionQuoteRequestAnchor | null>(null);
  const [preview, setPreview] = useState<DiscussionPreviewResponse | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [pageChanged, setPageChanged] = useState(false);
  const client = useQueryClient();
  const html = useQuery({ ...pageHtmlQuery(pageId, root), enabled: !source });
  const matching = source ? source.root === root && source.pageId === pageId : html.data?.id === pageId && html.data.root === root;
  const ready = matching && (source ? source.ready : html.isSuccess && !html.isRefetchError);
  const hash = source ? source.hash : html.data?.content_hash ?? null;
  const sourceBusy = source ? source.busy : html.isFetching;
  const { capture: captureStandalone, reset: resetStandalone } = useDiscussionSelection(wrapper, JSON.stringify([root, pageId]), hash, ready);
  const latest = useRef({ ready, hash, open, sourceBusy });
  const priorSource = useRef({ root, pageId, ready, hash, open });
  latest.current = { ready, hash, open, sourceBusy };

  function invalidate() {
    generation.current++; previewing.current = false;
    setPreparing(false); setCapture(null); setPreview(null); setConfirmed(false);
  }
  useEffect(() => {
    alive.current = true;
    resetSelection();
    focusDiscussionElement(heading.current);
    return () => { alive.current = false; generation.current++; };
  }, []);
  function resetSelection() { if (source) source.resetSelection(); else resetStandalone(); }
  useEffect(() => {
    const previous = priorSource.current;
    if (previous.root !== root || previous.pageId !== pageId || previous.hash !== hash || previous.ready !== ready || previous.open !== open) {
      invalidate(); resetSelection();
      if (previous.root !== root || previous.pageId !== pageId || !ready || !open) setStatus(null);
    }
    priorSource.current = { root, pageId, ready, hash, open };
  }, [root, pageId, hash, ready, open]);
  useEffect(() => { if (preview) focusDiscussionElement(confirmButton.current); }, [preview]);
  useEffect(() => { if (confirmed) focusDiscussionElement(submitButton.current); }, [confirmed]);

  async function prepare() {
    if (busy || reloading || !ready || sourceBusy || pageChanged || !open) return;
    clearSubmitError?.();
    const current = ++generation.current;
    previewing.current = true; setPreparing(true); setCapture(null); setPreview(null); setConfirmed(false); setError(null);
    setStatus(null);
    const selected = source ? source.capture() : captureStandalone(true);
    if ("reason" in selected || new TextEncoder().encode(selected.selected_text).length > 16_384) {
      previewing.current = false; setPreparing(false);
      setError("reason" in selected ? selected.reason.replace(/,? or discuss the whole page\.?/g, ".") :
        "That passage is too long. Select less text and preview again.");
      return;
    }
    if (selected.content_hash !== latest.current.hash) {
      invalidate(); resetSelection(); setPageChanged(true); setError("The page changed. Reload it and reselect the passage.");
      return;
    }
    setCapture(selected);
    try {
      const result = await previewDiscussionAnchor(root, pageId, selected);
      if (!alive.current || current !== generation.current || !latest.current.ready || !latest.current.open || latest.current.hash !== selected.content_hash) return;
      if (result.content_hash !== selected.content_hash) {
        invalidate(); resetSelection(); setPageChanged(true); setError("The page changed. Reload it and reselect the passage.");
      } else setPreview(result);
    } catch (failure) {
      if (!alive.current || current !== generation.current) return;
      if (failure instanceof ApiError && ["page_changed", "page_not_found", "ambiguous_page_id"].includes(failure.code)) {
        invalidate(); resetSelection(); setPageChanged(true);
      }
      setError(failure instanceof ApiError && (failure.status < 500 || ["root_unavailable", "absence_unverified"].includes(failure.code)) ? discussionActionError(failure, "reattach") :
        "The passage could not be previewed. Reload the source page and try again.");
    } finally {
      if (alive.current && current === generation.current) { previewing.current = false; setPreparing(false); }
    }
  }

  async function reload() {
    if (busy || sourceBusy || reloading) return;
    clearSubmitError?.();
    invalidate(); resetSelection(); setReloading(true); setError(null); setStatus(null);
    try {
      let recovered: boolean;
      if (source) recovered = await source.reload();
      else {
        const results = await Promise.allSettled([
          html.refetch({ throwOnError: true }),
          client.invalidateQueries({ queryKey: pageQuery(pageId, root).queryKey, exact: true }, { throwOnError: true }),
          client.invalidateQueries({ queryKey: ["page", "by-path"], predicate: (query) => {
            const cached = query.state.data as PageResponse | undefined;
            return cached?.id === pageId && cached.root === root;
          } }, { throwOnError: true }),
        ]);
        recovered = results.every((result) => result.status === "fulfilled");
      }
      if (!alive.current) return;
      setPageChanged(!recovered);
      if (recovered) setStatus("The page is ready. Reselect the passage and preview it again.");
      else setError("The source page could not be refreshed. Retry its source lookup.");
    } catch { if (alive.current) { setPageChanged(true); setError("The source page could not be refreshed. Retry its source lookup."); } }
    finally { if (alive.current) setReloading(false); }
  }

  async function reattach() {
    if (busy || reloading || previewing.current || !ready || sourceBusy || pageChanged || !open || disabled || !capture || !preview || !confirmed || capture.content_hash !== hash) return;
    const current = generation.current;
    const failure = await submit(capture);
    if (!alive.current || current !== generation.current) return;
    if (failure instanceof ApiError && (failure.status === 413 || ["page_changed", "page_not_found", "discussion_changed", "stale_discussion", "discussion_resolved",
      "anchor_too_large", "anchor_not_found", "invalid_anchor", "discussion_too_large", "ambiguous_page_id"].includes(failure.code))) {
      invalidate(); resetSelection();
      if (["page_changed", "page_not_found", "ambiguous_page_id"].includes(failure.code)) setPageChanged(true);
    }
  }

  const sourceReason = !matching && (source || html.data) ? "The source does not match this discussion's stored page and root. Retry its source lookup." :
    !source && html.isPending ? "Loading the stored source page…" :
    !ready ? html.error instanceof ApiError && html.error.code === "page_not_found" ? "The stored source page could not be found. The discussion remains available." :
      "The stored source page could not be read. Retry its source lookup." : null;
  return <section className="pb-discussion-reattach min-w-0 space-y-3" aria-label="Reattach discussion" data-pb-active-action
    onKeyDown={(event) => {
      if (event.key === "Escape" && !busy) {
        event.preventDefault(); invalidate(); setStatus(null);
        if (!pageChanged) setError(null);
        const target = !previewButton.current?.disabled ? previewButton.current :
          !reloadButton.current?.disabled ? reloadButton.current : heading.current;
        focusDiscussionElement(target);
      }
    }}>
    <h3 ref={heading} className="pb-discussion-heading" tabIndex={-1} data-pb-action-focus>
      {source ? "Select a new passage in the displayed page" : "Select a new passage"}</h3>
    <p className="pb-discussion-hint">Select page text, preview it, then confirm the passage before reattaching.</p>
    {sourceReason && <p role={!source && html.isPending ? "status" : "alert"}>{sourceReason}</p>}
    {!source && ready && html.data && <div ref={wrapper} className="min-w-0" data-pb-reattach-source><Prose html={html.data.html} /></div>}
    {disabled && <p>{disabled}</p>}
    {!open && <p>Cancel this action, then reopen the discussion before reattaching.</p>}
    <button ref={previewButton} type="button" className={`pb-discussion-action ${preview ? "pb-discussion-quiet" : "pb-discussion-primary"}`} disabled={busy || reloading || !ready || sourceBusy || pageChanged || !open || !!disabled}
      onClick={() => void prepare()}>{preview || preparing ? "Reselect" : "Preview selected passage"}</button>
    {preparing && <p role="status">Preparing passage preview…</p>}
    {preview && <div className="pb-discussion-evidence space-y-2">
      <p className="font-semibold">Original quotation</p><blockquote className="pb-discussion-quote">{originalQuote}</blockquote>
      <p className="font-semibold">New passage preview</p>
      {preview.selection === "snapped" && <p role="status" className="font-semibold">Whole block selected</p>}
      <blockquote className="pb-discussion-quote">{preview.quote_text}</blockquote>
      <button ref={confirmButton} type="button" className={`pb-discussion-action ${confirmed ? "pb-discussion-quiet" : "pb-discussion-primary"}`} disabled={busy || reloading || confirmed || !ready || sourceBusy || !open || !!disabled}
        onClick={() => setConfirmed(true)}>{confirmed ? "Passage confirmed" : "Confirm passage"}</button>
    </div>}
    {error && <p role="alert">{error}</p>}
    {status && <p role="status">{status}</p>}
    <button ref={reloadButton} type="button" className="pb-discussion-action pb-discussion-quiet" disabled={busy || sourceBusy || reloading} onClick={() => void reload()}>
      {sourceBusy || reloading ? "Reloading page…" : "Reload page"}</button>
    <div className="pb-discussion-composer-footer">
      <button type="button" className="pb-discussion-action pb-discussion-quiet" disabled={busy} onClick={cancel}>Cancel</button>
      <button ref={submitButton} type="button" className={`pb-discussion-action ${confirmed ? "pb-discussion-primary" : "pb-discussion-quiet"}`} disabled={busy || reloading || preparing || !ready || sourceBusy || pageChanged || !open || !!disabled || !confirmed || !preview || !capture || capture.content_hash !== hash}
        onClick={() => void reattach()}>Reattach discussion</button>
    </div>
  </section>;
}
