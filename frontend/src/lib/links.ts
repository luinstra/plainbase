import type { RootTree } from "../api/types";
import { rootEntryOfUrl } from "./tree";

/**
 * Click interception for links inside server-rendered HTML (and the tree nav, which uses
 * the same plain `<a href>` elements so API URLs stay verbatim). Internal root-content,
 * `/p/...` (the prefix, so both the rooted `/p/{root}/{id}` and the bare `/p/{id}` form match),
 * `/new`, and bare `/` (the first-page redirect route — the header logo) hrefs route
 * through the SPA router; everything else — external URLs, `/assets`,
 * downloads, new-tab/modified clicks, same-page `#fragment` jumps, and percent-encoded
 * permalinks — keeps native behavior.
 *
 * When `roots` is undefined (tree pending or permanently failed), no root-content href is internal and clicks
 * fall back to full-page navigation.
 *
 * Returns the SPA-internal href (pathname + search + hash) to navigate to, or null when
 * the browser should handle the click.
 */

export function interceptableHref(event: MouseEvent, roots: RootTree[] | undefined): string | null {
  if (event.defaultPrevented || event.button !== 0) return null;
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return null;

  const anchor = (event.target as Element | null)?.closest?.("a[href]") as HTMLAnchorElement | null;
  if (!anchor) return null;
  if (anchor.target && anchor.target !== "_self") return null;
  if (anchor.hasAttribute("download")) return null;
  if (anchor.origin !== window.location.origin) return null;

  const path = anchor.pathname;
  const internal =
    path === "/" ||
    path === "/new" ||
    path.startsWith("/p/") ||
    (roots !== undefined && rootEntryOfUrl(roots, path) !== null);
  if (!internal) return null;

  // A percent-escape in a `/p/` address is the one thing a soft navigation cannot carry, and THIS anchor is
  // the last place that can see it: the router hands the route a decodeURI'd pathname, so `/p/%65xtra/{id}`
  // reaches the parse as `extra/{id}` and would render a page. The server splits the RAW tail, so its root
  // segment is `%65xtra` and it answers 400 `invalid_root` - the click would work and the reload would not.
  // Nothing legal needs the escape (root slugs and page ids are both URL-unreserved), so the browser gets to
  // ask and the reader gets the server's own verdict. An encoded DECORATIVE trailing slug pays a full page
  // load for the same rule and still lands on the page: the alternative is teaching this file which segments
  // of a permalink carry identity, which is the parse's job, not the interceptor's. KNOWN AND ACCEPTED there:
  // on `/p/{id}/%2Fstale` the two sides genuinely disagree - the server reads a bare id with a decorative tail
  // and answers for that page, while the client double-decodes into an interior empty segment and would 400
  // `invalid_page_id` on the whole splat - so the reload is the side that WORKS and narrowing the guard here
  // would trade it for an error view. Nothing emits the shape. Root-content segments are percent-encoded on
  // purpose, and the router round-trips them.
  if (path.startsWith("/p/") && path.includes("%")) return null;

  // Same-page fragment: native anchor navigation scrolls without a reload — leave it alone.
  if (anchor.hash && path === window.location.pathname) return null;

  return path + anchor.search + anchor.hash;
}
