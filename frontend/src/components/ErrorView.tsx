import type { ErrorComponentProps } from "@tanstack/react-router";

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
