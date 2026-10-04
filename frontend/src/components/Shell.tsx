import { useQuery } from "@tanstack/react-query";
import { Link, Outlet, useRouter } from "@tanstack/react-router";
import { useState, type MouseEvent } from "react";
import { sessionQuery, treeQuery } from "../api/queries";
import type { RootTree } from "../api/types";
import { interceptableHref } from "../lib/links";
import { entryFor, primaryEntry, rootAcceptsWrites, rootLabel } from "../lib/tree";
import { ROOT_UNAVAILABLE } from "./ErrorView";
import { PageActionsTarget } from "./PageActions";
import { SearchPalette } from "./SearchPalette";
import { Sidebar } from "./Sidebar";
import { ThemeToggle } from "./ThemeToggle";
import { NewPageLink, NewPageProvider, useNewPageFlow } from "./NewPageFlow";

/** Opens the (always-mounted) palette via its custom event — the click counterpart to Cmd/Ctrl+K. */
function SearchTrigger() {
  return (
    <button
      type="button"
      onClick={() => document.dispatchEvent(new CustomEvent("pb:search-open"))}
      className="pb-search-trigger flex min-w-0 items-center gap-2.5 rounded-md border border-edge bg-field px-3 py-2 text-sm text-muted hover:text-ink"
      data-pb-search-trigger
      aria-label="Search"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true" className="shrink-0">
        <circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4.5 4.5" />
      </svg>
      <span className="truncate max-sm:hidden">Search your docs…</span>
      <kbd className="ml-auto rounded border border-edge px-1.5 font-mono text-xs text-faint max-sm:hidden">⌘K</kbd>
    </button>
  );
}

/** The "New" affordance's chrome, shared by the live link and its disabled twin (see [Shell]). */
const NEW_PAGE_CLASS = "pb-new-page flex shrink-0 items-center gap-2 rounded-md border border-primary-edge bg-primary px-3 py-1.5 text-sm font-medium text-primary-ink";

function NewPageLabel() {
  return (
    <>
      <span aria-hidden="true">+</span>
      <span className="max-sm:hidden">New page</span>
    </>
  );
}

/**
 * WHY the disabled twin is disabled, for its tooltip - never a second gate ([rootAcceptsWrites] is the ONE
 * gate, and this only puts words to its answer). A down root borrows the shared outage vocabulary rather than
 * wording its own: "This root is read-only" would be flatly untrue of a root that is merely unreachable, and
 * inventing a third phrasing of an outage is what ErrorView's ROOT_UNAVAILABLE exists to stop. A tree that has
 * not loaded makes no claim at all - we do not know yet, so we say nothing.
 */
function newPageBlockedReason(target: RootTree | null): string | undefined {
  if (!target) return undefined;
  return target.available ? "This root is read-only" : ROOT_UNAVAILABLE.headline(rootLabel(target));
}

/**
 * App shell: header + tree sidebar + content outlet. One delegated click handler routes
 * every internal root-content or `/p/...` anchor (either permalink form), including sidebar links and links inside the
 * server-rendered prose — through the SPA router; external links keep native behavior
 * (lib/links.ts decides).
 */
export function Shell() {
  return <NewPageProvider><ShellContent /></NewPageProvider>;
}

function ShellContent() {
  const creation = useNewPageFlow()!;
  const router = useRouter();
  const [pageActions, setPageActions] = useState<HTMLDivElement | null>(null);
  // F8: the only available auth signal is `authenticated` (SessionResponse carries no role) — agents/anonymous
  // never approve, so the "Review" nav is gated on it. The queue itself renders for any authenticated reader;
  // an approve/reject/rebase 403 becomes the no-access state in the detail (NOT a hard client capability gate —
  // that would need a server DTO change, out of scope for this frontend-only chunk).
  const session = useQuery(sessionQuery);

  // Creation context follows the resolved page identity; its host waits for alias redirects.
  // Root write flags govern the affordance, while the server still authorizes every request.
  const tree = useQuery(treeQuery);
  const roots = tree.data?.roots;
  const target = roots ? (creation.root ? entryFor(roots, creation.root) : primaryEntry(roots)) : null;
  const canCreate = target ? rootAcceptsWrites(roots, target.root) && creation.ready : false;

  const onClick = (event: MouseEvent) => {
    const href = interceptableHref(event.nativeEvent, roots);
    if (href) {
      event.preventDefault();
      router.history.push(href);
    }
  };

  return (
    <div className="pb-shell min-h-screen bg-surface text-ink" data-pb-shell onClick={onClick}>
      <header
        className="pb-header sticky top-0 z-10 grid h-14 items-center gap-6 border-b border-edge bg-chrome px-6"
        data-pb-header
      >
        <a href="/" className="pb-logo-home flex items-center" aria-label="Plainbase" data-pb-home>
          <img className="pb-logo pb-logo-light" src="/plainbase-logo.svg" alt="" aria-hidden="true" />
          <img className="pb-logo pb-logo-dark" src="/plainbase-logo-dark.svg" alt="" aria-hidden="true" />
        </a>
        <SearchTrigger />
        <div className="pb-header-actions flex items-center justify-end gap-3">
          {session.data?.authenticated && (
            <Link
              to="/review"
              className="pb-review-nav flex items-center gap-2 rounded-md border border-edge bg-surface px-3 py-1.5 text-sm text-muted hover:text-ink lg:hidden"
              data-pb-review-nav
              aria-label="Review queue"
            >
              <span className="max-sm:hidden">Review</span>
            </Link>
          )}
          <div ref={setPageActions} className="contents" />
          {canCreate ? (
            <NewPageLink
              root={creation.root}
              folder={creation.folder}
              className={NEW_PAGE_CLASS}
              data-pb-new-page
              aria-label="New page"
            >
              <NewPageLabel />
            </NewPageLink>
          ) : (
            <button
              type="button"
              disabled
              className={`${NEW_PAGE_CLASS} cursor-not-allowed opacity-60`}
              data-pb-new-page
              aria-label="New page"
              title={newPageBlockedReason(target)}
            >
              <NewPageLabel />
            </button>
          )}
          <span className="h-5 border-l border-edge" aria-hidden="true" />
          <ThemeToggle />
        </div>
      </header>
      <div className="flex w-full">
        <Sidebar showReview={session.data?.authenticated === true} />
        <main className="pb-main min-w-0 flex-1 px-4 py-8 lg:pl-12 lg:pr-8" data-pb-main>
          <PageActionsTarget.Provider value={pageActions}><Outlet /></PageActionsTarget.Provider>
        </main>
      </div>
      <SearchPalette disabled={creation.isOpen} />
    </div>
  );
}
