import type { ErrorComponentProps } from "@tanstack/react-router";
import type { ReactNode } from "react";

import { ApiError } from "../api/client";

/** The one recovery destination — shared by the anchor's `href` and the hard navigation so they can't diverge. */
const DOCS_HOME = "/docs";

/**
 * The ONE outage vocabulary. A root that is not serving is told in exactly TWO places — the full-page
 * [RootUnavailableView] and the sidebar's per-root section — because they need different MARKUP (an `<h1>`
 * belongs on a page, not inside a nav) but the SAME words. Every other surface that learns of an outage
 * (the router boundary, [QueryErrorView], PageView) DELEGATES to one of those two rather than composing
 * these constants itself: a third composer is how one of them ends up wording the headline without the
 * root name, which is precisely what the router boundary did.
 */
export const ROOT_UNAVAILABLE = {
  eyebrow: "Unavailable",
  headline: (root?: string) => (root ? `The "${root}" root is not serving right now` : "This root is not serving right now"),
  body: "Its pages still exist and are not lost: the server cannot reach its content directory. An operator has to restore it.",
};

/**
 * The branded render-error surface, installed as the router's `defaultErrorComponent` so every
 * route match gets its own boundary — a crash in one route's render keeps the Shell mounted, and
 * the boundary auto-resets on the next navigation. The recovery link keeps a real `href`
 * (copy-link/middle-click semantics) but hard-navigates on click: the Shell intercepts internal
 * anchors into a SOFT router push (lib/links.ts), and after a render crash the router/component
 * state may be poisoned — a full-document reload is the robust reset.
 *
 * An outage reaching this boundary DELEGATES, exactly as [QueryErrorView] does. It used to compose
 * ROOT_UNAVAILABLE by hand here, which made this a THIRD outage renderer that happened to word the
 * headline without the root name — the one detail that tells a reader WHICH docs are down. One
 * outage, one renderer; the boundary decides only that it IS one.
 */
export function ErrorView({ error }: ErrorComponentProps) {
  // A root that is not serving is NOT a crash and must not read like one: the content still exists, the
  // server said so explicitly, and the envelope message names the root and the remedy. Show that instead of
  // "Something went wrong", which would tell a reader their page is broken when a disk is simply unmounted.
  if (isRootUnavailable(error)) return <RootUnavailableView detail={error.message} />;
  return (
    <div className="py-16 text-center" data-pb-error-boundary>
      <p className="text-sm font-semibold uppercase tracking-wide text-faint">Error</p>
      <h1 className="mt-2 text-2xl font-bold text-ink">Something went wrong</h1>
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
 *
 * [children] is the caller's REMEDY, rendered inside this block so it lands under the message it answers - for the
 * one error whose message promises something only the caller can build (the 409 ambiguity's candidate roots, which
 * need the page id the route was addressed with). An outage ignores it: there is no remedy for an unmounted disk.
 */
export function QueryErrorView({ error, children }: { error: unknown; children?: ReactNode }) {
  if (isRootUnavailable(error)) return <RootUnavailableView detail={error.message} />;
  return (
    <div className="py-16 text-center" data-pb-error>
      <h1 className="text-2xl font-bold text-ink">Something went wrong</h1>
      <p className="mt-3 text-muted">{error instanceof Error ? error.message : String(error)}</p>
      {children}
    </div>
  );
}

/**
 * The FULL-PAGE renderer of [ROOT_UNAVAILABLE], for every way a client can learn a root is down with a
 * whole view to give it: a 503 `root_unavailable` on a page request, and (with no request to answer it)
 * the `available: false` an unavailable root's TREE entry carries, whose subtree the server empties on
 * purpose. The sidebar tells the same news in a nav section, so this is one of the vocabulary's two
 * renderers, not the one outage component.
 *
 * It must not read as "empty" and it must not read as "broken". The content still exists, it is
 * coming back, and nothing the reader can do here fixes it: the remedy is an operator's (restore
 * the path, restart), so the copy names the condition and stops. `data-pb-root-unavailable` is the
 * stable selector.
 */
export function RootUnavailableView({ root, detail }: { root?: string; detail?: string }) {
  return (
    <div className="py-16 text-center" data-pb-root-unavailable>
      <p className="text-sm font-semibold uppercase tracking-wide text-faint">{ROOT_UNAVAILABLE.eyebrow}</p>
      <h1 className="mt-2 text-2xl font-bold text-ink">{ROOT_UNAVAILABLE.headline(root)}</h1>
      {/* The server's own message when there was a request to answer (it names the root and the remedy);
          the shared body otherwise. */}
      <p className="mt-3 text-muted">{detail ?? ROOT_UNAVAILABLE.body}</p>
    </div>
  );
}
