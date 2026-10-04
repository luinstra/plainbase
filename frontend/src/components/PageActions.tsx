import { Link } from "@tanstack/react-router";
import { createContext, useContext } from "react";
import { createPortal } from "react-dom";
import { byPathKeyForUrl } from "../api/queries";

/** The shell supplies the slot; the loaded page owns the action and its lifetime. */
export const PageActionsTarget = createContext<HTMLElement | null>(null);

const PAGE_ACTION_CLASS = "flex items-center gap-2 rounded-md border border-edge bg-surface px-3 py-1.5 text-sm text-muted hover:text-ink disabled:opacity-50";

export function PageEditAction({ url, editable }: { url: string | null; editable: boolean }) {
  const target = useContext(PageActionsTarget);
  const splat = byPathKeyForUrl(url);
  if (!target || !editable || !splat) return null;

  return createPortal(
    <Link
      to="/$" params={{ _splat: splat }} search={{ mode: "edit" }}
      className={`pb-edit-page ${PAGE_ACTION_CLASS}`}
      data-pb-edit-page aria-label="Edit this page"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="m16 3 5 5M4 15 16.5 2.5a2.1 2.1 0 0 1 3 3L7 18l-5 1 1-5" />
        <path d="M12 21h9" />
      </svg>
      <span className="max-sm:hidden">Edit</span>
    </Link>,
    target,
  );
}

export function PageViewAction({ onView, disabled }: { onView: () => void; disabled: boolean }) {
  return (
    <button type="button" onClick={onView} disabled={disabled} className={`pb-view-page ${PAGE_ACTION_CLASS}`}
      data-pb-view-page aria-label="Done editing">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
        <circle cx="12" cy="12" r="3" />
      </svg>
      <span className="max-sm:hidden">Done</span>
    </button>
  );
}
