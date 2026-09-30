import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";
import type { DiscussionItem, DiscussionListResponse, DiscussionReadAnchor } from "../api/types";
import { formatTime } from "../lib/datetime";
import { permalinkOf } from "../lib/permalink";

export const DISCUSSION_STATES = ["page_level", "exact", "moved", "ambiguous", "changed", "orphaned", "unavailable", "unreadable", "incomplete"] as const;

export function discussionAvailability(reason: string | null): string {
  if (reason === "read_only_root") return "Discussions are not available on this read-only root";
  if (reason === "object_storage") return "Discussions are not available for this storage type";
  return "Discussions are not available on this root";
}

export function DiscussionAvailability({ reason }: { reason: string | null }) {
  return <p className="pb-discussion-notice" role="status">{discussionAvailability(reason)}</p>;
}

export function DiscussionReadError({ error, retry }: { error: Error; retry: () => void }) {
  return <div className="pb-discussion-notice" role="alert"><p>{error.message}</p><button type="button" className="pb-discussion-action" onClick={retry}>Retry</button></div>;
}

export function DiscussionListRows({ root, items, onSelect }: { root: string; items: DiscussionItem[]; onSelect?: (id: string) => void }) {
  return <ul className="pb-discussion-list">{items.map((item) =>
    <li key={item.id} className="pb-discussion-card">
      {onSelect ?
        <button type="button" className="pb-discussion-title" data-pb-discussion-id={item.id} onClick={() => onSelect(item.id)}>{item.page.path ?? item.page.id ?? item.id}</button> :
        <Link to="/discussions/$root/$id" params={{ root, id: item.id }} className="pb-discussion-title">
          {item.page.path ?? item.page.id ?? item.id}
        </Link>}
      <DiscussionSummary item={item} root={root} showPreview />
    </li>,
  )}</ul>;
}

export function DiscussionSummary({ item, root, showPreview = false }: { item: DiscussionItem; root: string; showPreview?: boolean }) {
  const state = item.state;
  return <div className="min-w-0 space-y-2 text-sm text-muted">
    <p><strong className="text-ink">{stateLabel(state)}</strong>
      {item.status ? ` · ${item.status === "resolved" ? "Resolved" : "Open"}` : ""}
      {` · ${item.comment_count} ${item.comment_count === 1 ? "comment" : "comments"}`}
    </p>
    {showPreview && item.quote && <blockquote className="pb-discussion-quote pb-discussion-preview">{item.quote}</blockquote>}
    {item.starter && <p>Started by {item.starter.label}{item.starter.kind === "agent" ? " · Agent" : ""}</p>}
    {item.created && <p>Created <time dateTime={item.created} title={item.created}>{formatTime(item.created)}</time>
      {item.updated && item.updated !== item.created && <> · Updated <time dateTime={item.updated} title={item.updated}>{formatTime(item.updated)}</time></>}</p>}
    {item.reason && <>{state === "unreadable" && <p>Discussion details could not be read.</p>}
      <details><summary>Diagnostic reason</summary><code>{item.reason}</code></details></>}
    {state === "ambiguous" && item.candidates && <>
      <p>{item.candidates.count} possible locations.</p>
      {item.candidates.truncated && <p>More matches exist</p>}
      {item.candidates.items.length > 0 && <details><summary>Source locations</summary><ol>
        {item.candidates.items.map((candidate, index) => <li key={`${candidate.byte_start}-${candidate.byte_end}-${index}`}>
          Possible location {index + 1}: source bytes {candidate.byte_start}–{candidate.byte_end}
        </li>)}
      </ol></details>}
    </>}
    {state === "changed" && item.placement?.kind === "heading" && item.placement.id && item.page.id &&
      <p>Approximate placement near <a className="text-link" href={`${permalinkOf(root, item.page.id)}#${encodeURIComponent(item.placement.id)}`}>heading {item.placement.id}</a>.</p>}
    {state === "changed" && item.placement?.kind === "line" && item.placement.line !== null &&
      <details><summary>Source location</summary>Approximate source line {item.placement.line}</details>}
    {(state === "orphaned" || state === "unavailable" || state === "incomplete") &&
      <p>Target: {item.page.path ?? item.page.id ?? "Unknown page"}{item.page.path && item.page.id ? ` · ${item.page.id}` : ""}</p>}
  </div>;
}

export function stateLabel(state: DiscussionItem["state"]): string {
  switch (state) {
    case "page_level": return "Page discussion";
    case "exact": return "Quote found";
    case "moved": return "Quote moved";
    case "ambiguous": return "Matches several places";
    case "changed": return "Was around here";
    case "orphaned": return "Page no longer found";
    case "unavailable": return "Temporarily unavailable";
    case "unreadable": return "Unreadable discussion";
    case "incomplete": return "Incomplete discussion";
  }
}

export function DiscussionAnchor({ label, anchor, inPanel = false }: { label: string; anchor: DiscussionReadAnchor | null; inPanel?: boolean }) {
  if (!anchor) return null;
  return <section className="pb-discussion-evidence">
    {inPanel ? <h4 className="font-semibold text-ink">{label}</h4> : <h2 className="font-semibold text-ink">{label}</h2>}
    {anchor.kind === "page" ? <p>Page discussion</p> : <>
      <blockquote className="pb-discussion-quote">{anchor.quote}</blockquote>
      {anchor.heading_path.length > 0 && <p>Near {anchor.heading_path.map((part) => part.text).join(" › ")}</p>}
    </>}
    <details><summary>Source evidence</summary>
      {anchor.kind === "quote" && <>
        <p>Selection: {anchor.selection}; original line {anchor.line}; source bytes {anchor.byte_start}–{anchor.byte_end}.</p>
        {anchor.prefix && <p>Before: {anchor.prefix}</p>}
        {anchor.suffix && <p>After: {anchor.suffix}</p>}
      </>}
      <p>Source hash {anchor.content_hash}{anchor.commit ? ` · commit ${anchor.commit}` : ""}</p>
    </details>
  </section>;
}

export function uniqueDiscussionItems(pages: DiscussionListResponse[]): DiscussionItem[] {
  const seen = new Set<string>();
  return pages.flatMap((page) => page.discussions.filter((item) => {
    if (seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  }));
}

export function ReadWindow({ children }: { children: ReactNode }) {
  return <div className="pb-discussions mx-auto max-w-[72ch] min-w-0 space-y-5" data-pb-discussions>{children}</div>;
}
