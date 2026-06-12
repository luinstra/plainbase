/**
 * Click interception for links inside server-rendered HTML (and the tree nav, which uses
 * the same plain `<a href>` elements so API URLs stay verbatim). Internal `/docs/...`,
 * `/p/...`, and bare `/` (the first-page redirect route — the header logo) hrefs route
 * through the SPA router; everything else — external URLs, `/assets`,
 * downloads, new-tab/modified clicks, same-page `#fragment` jumps — keeps native behavior.
 *
 * Returns the SPA-internal href (pathname + search + hash) to navigate to, or null when
 * the browser should handle the click.
 */
export function interceptableHref(event: MouseEvent): string | null {
  if (event.defaultPrevented || event.button !== 0) return null;
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return null;

  const anchor = (event.target as Element | null)?.closest?.("a[href]") as HTMLAnchorElement | null;
  if (!anchor) return null;
  if (anchor.target && anchor.target !== "_self") return null;
  if (anchor.hasAttribute("download")) return null;
  if (anchor.origin !== window.location.origin) return null;

  const path = anchor.pathname;
  const internal = path === "/" || path === "/docs" || path.startsWith("/docs/") || path.startsWith("/p/");
  if (!internal) return null;

  // Same-page fragment: native anchor navigation scrolls without a reload — leave it alone.
  if (anchor.hash && path === window.location.pathname) return null;

  return path + anchor.search + anchor.hash;
}
