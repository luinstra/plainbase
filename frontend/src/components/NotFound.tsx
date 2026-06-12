/** The 404 view — unknown paths get the shell (200) from the server; the SPA owns not-found UI. */
export function NotFoundView() {
  return (
    <div className="py-16 text-center" data-pb-not-found>
      <p className="text-sm font-semibold uppercase tracking-wide text-faint">404</p>
      <h1 className="mt-2 text-2xl font-bold text-ink">Page not found</h1>
      <p className="mt-3 text-muted">Nothing lives at this address. It may have moved — head back to the docs home and navigate from there.</p>
      <p className="mt-6">
        <a href="/" className="font-medium text-link hover:text-link-hover hover:underline">
          Go to the docs home
        </a>
      </p>
    </div>
  );
}
