import type { ErrorComponentProps } from "@tanstack/react-router";

import { ApiError } from "../api/client";

/** The one recovery destination — shared by the anchor's `href` and the hard navigation so they can't diverge. */
const DOCS_HOME = "/docs";

/**
 * The branded render-error surface, installed as the router's `defaultErrorComponent` so every
 * route match gets its own boundary — a crash in one route's render keeps the Shell mounted, and
 * the boundary auto-resets on the next navigation. The recovery link keeps a real `href`
 * (copy-link/middle-click semantics) but hard-navigates on click: the Shell intercepts internal
 * anchors into a SOFT router push (lib/links.ts), and after a render crash the router/component
 * state may be poisoned — a full-document reload is the robust reset.
 */
export function ErrorView({ error }: ErrorComponentProps) {
  // A root that is not serving is NOT a crash and must not read like one: the content still exists, the
  // server said so explicitly, and the envelope message names the root and the remedy. Show that instead of
  // "Something went wrong", which would tell a reader their page is broken when a disk is simply unmounted.
  const unavailable = isRootUnavailable(error);
  return (
    <div className="py-16 text-center" data-pb-error-boundary>
      <p className="text-sm font-semibold uppercase tracking-wide text-faint">{unavailable ? "Unavailable" : "Error"}</p>
      <h1 className="mt-2 text-2xl font-bold text-ink">
        {unavailable ? "This root is not serving right now" : "Something went wrong"}
      </h1>
      <p className="mt-3 text-muted">{error instanceof Error ? error.message : String(error)}</p>
      <p className="mt-6">
        <a
          href={DOCS_HOME}
          className="font-medium text-link hover:text-link-hover hover:underline"
          onClick={(event) => {
            event.preventDefault();
            event.stopPropagation();
            window.location.assign(DOCS_HOME);
          }}
        >
          Go to the docs home
        </a>
      </p>
    </div>
  );
}

/** The one recognizer for the outage envelope — every error surface asks THIS, never re-spells the wire code. */
export function isRootUnavailable(error: unknown): error is ApiError {
  return error instanceof ApiError && error.code === "root_unavailable";
}

/**
 * The ONE surface for a FAILED QUERY, and the reason it exists: the outage-vs-crash distinction was hand-rolled at
 * five call sites and three of them forgot it, because `<h1>Something went wrong</h1>` is two lines and nothing
 * stops the next panel from typing them again. A 503 `root_unavailable` is not a crash - the pages are fine, a disk
 * is not mounted - so it renders as the outage, and everything else renders as the error it is.
 *
 * Callers keep their OWN 404 rule (it genuinely differs: a by-path 400 is a not-found for a page route, but not for
 * a proposal id) and hand every other failure here.
 */
export function QueryErrorView({ error }: { error: unknown }) {
  if (isRootUnavailable(error)) return <RootUnavailableView detail={error.message} />;
  return (
    <div className="py-16 text-center" data-pb-error>
      <h1 className="text-2xl font-bold text-ink">Something went wrong</h1>
      <p className="mt-3 text-muted">{error instanceof Error ? error.message : String(error)}</p>
    </div>
  );
}

/**
 * The ONE outage surface, for every way a client can learn a root is down: a 503 `root_unavailable`
 * on a page request, and (with no request to answer it) the `available: false` an unavailable root's
 * TREE entry carries, whose subtree the server empties on purpose.
 *
 * It must not read as "empty" and it must not read as "broken". The content still exists, it is
 * coming back, and nothing the reader can do here fixes it: the remedy is an operator's (restore
 * the path, restart), so the copy names the condition and stops. `data-pb-root-unavailable` is the
 * stable selector.
 */
export function RootUnavailableView({ root, detail }: { root?: string; detail?: string }) {
  return (
    <div className="py-16 text-center" data-pb-root-unavailable>
      <p className="text-sm font-semibold uppercase tracking-wide text-faint">Unavailable</p>
      <h1 className="mt-2 text-2xl font-bold text-ink">
        {root ? `The "${root}" root is not serving right now` : "This root is not serving right now"}
      </h1>
      <p className="mt-3 text-muted">
        {detail ?? "Its pages still exist and are not lost: the server cannot reach its content directory. An operator has to restore it."}
      </p>
    </div>
  );
}
