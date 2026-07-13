import { useQuery } from "@tanstack/react-query";
import { Link, Outlet, useRouter, useRouterState } from "@tanstack/react-router";
import type { MouseEvent } from "react";
import { sessionQuery, treeQuery } from "../api/queries";
import type { RootTree } from "../api/types";
import { interceptableHref } from "../lib/links";
import { entryFor, rootAcceptsWrites, rootOfUrl } from "../lib/tree";
import { ROOT_UNAVAILABLE } from "./ErrorView";
import { SearchPalette } from "./SearchPalette";
import { Sidebar } from "./Sidebar";
import { ThemeToggle } from "./ThemeToggle";

/** Opens the (always-mounted) palette via its custom event — the click counterpart to Cmd/Ctrl+K. */
function SearchTrigger() {
  return (
    <button
      type="button"
      onClick={() => document.dispatchEvent(new CustomEvent("pb:search-open"))}
      className="pb-search-trigger flex items-center gap-2 rounded-md border border-edge bg-surface px-3 py-1.5 text-sm text-muted hover:text-ink"
      data-pb-search-trigger
      aria-label="Search"
    >
      <span aria-hidden="true">⌕</span>
      <span className="max-sm:hidden">Search</span>
      <kbd className="ml-2 rounded border border-edge px-1.5 font-mono text-xs text-faint max-sm:hidden">⌘K</kbd>
    </button>
  );
}

/** The "New" affordance's chrome, shared by the live link and its disabled twin (see [Shell]). */
const NEW_PAGE_CLASS = "pb-new-page flex items-center gap-2 rounded-md border border-edge bg-surface px-3 py-1.5 text-sm text-muted";

function NewPageLabel() {
  return (
    <>
      <span aria-hidden="true">+</span>
      <span className="max-sm:hidden">New</span>
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
  return target.available ? "This root is read-only" : ROOT_UNAVAILABLE.headline(target.root);
}

/**
 * App shell: header + tree sidebar + content outlet. One delegated click handler routes
 * every internal `/docs/...` / `/p/...` anchor — sidebar links AND links inside the
 * server-rendered prose — through the SPA router; external links keep native behavior
 * (lib/links.ts decides).
 */
export function Shell() {
  const router = useRouter();
  // F8: the only available auth signal is `authenticated` (SessionResponse carries no role) — agents/anonymous
  // never approve, so the "Review" nav is gated on it. The queue itself renders for any authenticated reader;
  // an approve/reject/rebase 403 becomes the no-access state in the detail (NOT a hard client capability gate —
  // that would need a server DTO change, out of scope for this frontend-only chunk).
  const session = useQuery(sessionQuery);

  // WHERE a new page lands: the root whose `/docs/{root}` URL space the reader is currently in, carried into
  // `/new` as `?root=`. The wire `root` is REQUIRED and has no server-side default (an omitted one is a 400
  // `invalid_root`, never permission to write into main), so SOMETHING must name it: off the docs routes the
  // create has no root of its own and `NewPage` resolves it to the reserved `main` - the one legal client-side
  // root literal.
  //
  // Until the tree RESOLVES there is no answer at all — not even "no root": the roots and their url prefixes are
  // exactly what the tree carries, so a `/new` link rendered in that window would look identical on an extra-root
  // page and on `/review`, and land the bytes in main either way. So the action is DISABLED rather than wrong: a
  // reader who has to wait a beat has lost nothing, a reader whose page silently appeared in the wrong repository
  // has. (Same reason the create payload threads the root explicitly — see api/types.ts `CreateRequest.root`.)
  const tree = useQuery(treeQuery);
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const roots = tree.data?.roots;
  const currentRoot = roots ? rootOfUrl(roots, pathname) : null;
  // WHETHER a new page can land there at all: the target root must be editable AND serving. Off the docs routes
  // the target is the `main` the create would resolve to, so main's bits decide, not "none". Read-only, down, and
  // not-yet-known all get the SAME disabled twin, for one reason: an enabled action that can only end in a 403 or
  // a 503 is a reader taken into the editor to lose their keystrokes at save. (`plainbase root add` defaults an
  // extra root to `editable = false`, so the read-only case is the ordinary state of a CLI-added root, not a
  // corner; the unavailable case is a root whose disk is not mounted, which the tree still LISTS.)
  const target = roots ? entryFor(roots, currentRoot ?? "main") : null;
  const canCreate = rootAcceptsWrites(roots, currentRoot ?? "main");

  const onClick = (event: MouseEvent) => {
    const href = interceptableHref(event.nativeEvent);
    if (href) {
      event.preventDefault();
      router.history.push(href);
    }
  };

  return (
    <div className="pb-shell min-h-screen bg-surface text-ink" data-pb-shell onClick={onClick}>
      <header
        className="pb-header sticky top-0 z-10 flex h-14 items-center justify-between border-b border-edge bg-raised px-4"
        data-pb-header
      >
        <a href="/" className="pb-logo-home flex items-center" aria-label="Plainbase" data-pb-home>
          <img className="pb-logo pb-logo-light" src="/plainbase-logo.svg" alt="" aria-hidden="true" />
          <img className="pb-logo pb-logo-dark" src="/plainbase-logo-dark.svg" alt="" aria-hidden="true" />
        </a>
        <div className="flex items-center gap-3">
          <SearchTrigger />
          {session.data?.authenticated && (
            <Link
              to="/review"
              className="pb-review-nav flex items-center gap-2 rounded-md border border-edge bg-surface px-3 py-1.5 text-sm text-muted hover:text-ink"
              data-pb-review-nav
              aria-label="Review queue"
            >
              <span className="max-sm:hidden">Review</span>
            </Link>
          )}
          {canCreate ? (
            <Link
              to="/new"
              search={currentRoot ? { root: currentRoot } : {}}
              className={`${NEW_PAGE_CLASS} hover:text-ink`}
              data-pb-new-page
              aria-label="New page"
            >
              <NewPageLabel />
            </Link>
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
          <ThemeToggle />
        </div>
      </header>
      <div className="flex w-full">
        <Sidebar />
        <main className="pb-main min-w-0 flex-1 px-6 py-8 lg:px-12" data-pb-main>
          <Outlet />
        </main>
      </div>
      <SearchPalette />
    </div>
  );
}
