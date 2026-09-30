import { useEffect, useRef, type FormEvent } from "react";
import { ApiError } from "../api/client";

export function commentValidation(body: string): string | null {
  if (!body.trim()) return "Enter a comment before posting.";
  for (let index = 0; index < body.length; index++) {
    const unit = body.charCodeAt(index);
    if (unit >= 0xD800 && unit <= 0xDBFF) {
      const next = body.charCodeAt(++index);
      if (!(next >= 0xDC00 && next <= 0xDFFF)) return "This comment contains a character that cannot be posted. Please correct it.";
    } else if (unit >= 0xDC00 && unit <= 0xDFFF) return "This comment contains a character that cannot be posted. Please correct it.";
  }
  if (new TextEncoder().encode(body).length > 65_536) return "This comment is too long. Shorten it before posting.";
  return null;
}

export function discussionWriteError(error: unknown): string {
  if (!(error instanceof ApiError)) return "The outcome is unclear. Refresh and check whether it posted before trying again.";
  switch (error.code) {
    case "page_changed": return "The page changed. Reload it, review the text, and try again.";
    case "anchor_too_large": return "That passage is too large. Select less text or discuss the whole page.";
    case "anchor_not_found": case "invalid_anchor": return "That passage could not be found. Reselect it or discuss the whole page.";
    case "comment_empty": return "Enter a comment before posting.";
    case "comment_too_large": case "discussion_too_large": return "This discussion is too large. Shorten the comment before posting.";
    case "page_discussion_limit": case "discussion_full": return "This page or discussion has reached its comment limit.";
    case "discussion_changed": case "stale_discussion": case "discussion_resolved": case "comment_retracted":
      return "This discussion changed. Refresh it before deciding whether to post again.";
    case "discussion_unreadable": return "This discussion cannot be read right now. Refresh before trying again.";
    case "page_not_found": case "discussion_not_found": return "The page or discussion could not be found. Your draft is still here.";
    case "ambiguous_page_id": return "This page has more than one location. Return to the page and try again.";
    case "discussions_unsupported": return "Discussions are unavailable here. Your draft is still here.";
    case "discussion_path_refused": return "This discussion could not be saved here. Your draft is still here.";
    case "root_unavailable": case "absence_unverified": return "This root is unavailable. Refresh and check before trying again.";
    case "content_unreadable": return "The content could not be read. Refresh and check whether it posted before trying again.";
    case "invalid_utf8": return "This comment contains a character that cannot be posted. Please correct it.";
  }
  if (error.status === 401) return "Your session needs attention. Your draft is still here.";
  if (error.status === 403) return "You cannot post here right now. Refresh to check access; your draft is still here.";
  if (error.status === 413) return "This comment is too large for this request. Shorten it before posting.";
  if (error.status >= 500) return "The outcome is unclear. Refresh and check whether it posted before trying again.";
  return "This comment could not be posted. Review it and try again.";
}

export function discussionPreviewError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "page_changed": return "The page changed. Reload it and reselect the passage.";
      case "anchor_too_large": return "That passage is too large. Select less text or discuss the whole page.";
      case "anchor_not_found": case "invalid_anchor": return "That passage could not be found. Reselect it or discuss the whole page.";
      case "ambiguous_page_id": return "This page has more than one location. Return to the page and try again.";
      case "discussions_unsupported": return "Discussions are unavailable here.";
    }
  }
  return "The passage could not be previewed. Refresh the page and try again.";
}

export function DiscussionComposer({ body, setBody, submit, cancel, busy, disabled, status, error, submitLabel, focusKey, onEscape }: {
  body: string;
  setBody: (body: string) => void;
  submit: () => void;
  cancel: () => void;
  busy: boolean;
  disabled?: string | null;
  status?: string | null;
  error?: string | null;
  submitLabel: string;
  focusKey?: number;
  onEscape?: () => void;
}) {
  const textarea = useRef<HTMLTextAreaElement>(null);
  useEffect(() => { textarea.current?.focus(); }, [focusKey]);
  const onSubmit = (event: FormEvent) => { event.preventDefault(); if (!busy && !disabled) submit(); };
  return <form className="pb-discussion-card space-y-3" aria-busy={busy} onSubmit={onSubmit}>
    <label className="block text-sm font-semibold" htmlFor="pb-discussion-comment">Comment</label>
    <textarea ref={textarea} id="pb-discussion-comment" className="pb-discussion-textarea" rows={5} value={body}
      onChange={(event) => { if (!busy) setBody(event.target.value); }} readOnly={busy}
      aria-describedby={disabled ? "pb-discussion-disabled" : undefined}
      onKeyDown={(event) => { if (event.key === "Escape" && onEscape && !busy) {
        event.preventDefault(); onEscape(); textarea.current?.focus();
      } }} />
    {disabled && <p id="pb-discussion-disabled">{disabled}</p>}
    {status && <p role="status" aria-label={status}>{status}</p>}
    {error && <p role="alert">{error}</p>}
    <div className="flex flex-wrap gap-2">
      <button type="submit" className="pb-discussion-action" disabled={busy || !!disabled}>{submitLabel}</button>
      <button type="button" className="pb-discussion-action" disabled={busy} onClick={cancel}>Cancel</button>
    </div>
  </form>;
}
