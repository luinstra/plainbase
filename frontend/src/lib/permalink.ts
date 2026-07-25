/**
 * The client half of the `/p/` permalink contract. Two mirrors of server code, deliberately in one
 * file so emission and parse cannot drift:
 *  - `permalinkOf` mirrors `domain/root/RootedPageId.kt` `Permalink.of` (rooted since C5);
 *  - `parsePermalink` mirrors `frameworks/ktor/routes/PermalinkRoute.kt`'s dispatch ORDER: a leading
 *    id-shaped segment is a BARE permalink (trailing segments decorative), otherwise a leading root
 *    segment followed by a CANONICAL-id one is the ROOTED form.
 *
 * TWO id gates, not one, because the server has two and they are deliberately different:
 *  - [PAGE_ID_TEXT] mirrors the BARE arm's `PageId.of`, i.e. `Uuid.parseOrNull`: the 36-char
 *    hyphenated form AND the 32-char hyphenless hex form, either case. A first segment matching THIS
 *    is a bare id, and any trailing segments are decorative.
 *  - [CANONICAL_PAGE_ID] mirrors the ROOTED arm's STRICT `canonicalPageId`: the 36-char hyphenated
 *    shape only. The SECOND segment is gated by this one.
 *
 * Gating the FIRST segment strictly would be a bug, not a tightening: the SERVER's dispatcher reads
 * `/p/{32-hex}/{uuid}` as BARE (id = the 32-hex, `{uuid}` decorative), and a strict gate re-reads the
 * same address as ROOTED, so the client classifies the segments differently from the dispatcher it
 * claims to mirror. Both readings end in an error view (the by-id API gate is canonical-only, so the
 * bare read is 400 `invalid_page_id` and the rooted misreading sends `?root=<32-hex>` and gets 400
 * `invalid_root`), so the cost is not a working address turned broken; it is a wrong VERDICT (blaming
 * a root the address has no room for) plus a spurious request.
 *
 * Before either arm runs, the STRUCTURAL gate the server applies first: contiguous TRAILING empty
 * segments are decorative, a LEADING or INTERIOR one is malformed. That one is not a divergence we
 * tolerate but a bug we fixed: without it `/p/extra/{id}//stale` RENDERS on an SPA click and 400s on
 * reload or when shared, so whether the address works depends on how the reader arrived at it.
 *
 * Neither id gate REJECTS: they only tell a root segment from an id segment. Deciding a page id is
 * invalid stays the server's job (400 `invalid_page_id`), so an unrecognised - or structurally
 * malformed - splat falls through as a bare id and lets the API say so.
 *
 * KNOWN, BOUNDED divergences, both in the direction of a spurious request that ends in an error view:
 *  - The client does not apply `RootName.of`'s slug grammar to the first segment (lowercase slug, max
 *    32 chars, never page-id-parsable). So `/p/Extra/{uuid}` or `/p/a_b/{uuid}` parses ROOTED here
 *    while the server's dispatcher answers 400 `invalid_root`. The client then sends `?root=Extra` on
 *    the id read and `pinnedRootOrRefuse()` refuses it with the SAME 400 and the same CODE: an error
 *    view, never a wrong page. Gating it here would swap that agreement for a disagreement - the first
 *    segment would become the bare id and the read would answer `invalid_page_id` instead. Nothing
 *    emits these shapes; not chased.
 * The percent-encoding split is NOT on that list, because it is closed - just not here. The server validates
 * the RAW path while the router hands this function a doubly-decoded splat (`decodeURI` on the pathname, then
 * `decodeURIComponent` on the splat param), so `/p/%65xtra/{id}` arrives as `extra/{id}` with the escape already
 * gone: no gate written here can see it, and one keyed on a surviving `%` would only fire on shapes the server
 * and client already answer alike. The seam that CAN see it is the click interceptor, whose anchor still holds
 * the raw pathname - `lib/links.ts` refuses to soft-navigate a `/p/` href carrying one, so the browser asks the
 * server and the reader gets its 400 instead of a render that dies on reload.
 */
const PAGE_ID_TEXT = /^(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/;
const CANONICAL_PAGE_ID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

const PERMALINK_PREFIX = "/p/";

export function permalinkOf(root: string, id: string): string {
  return `${PERMALINK_PREFIX}${root}/${id}`;
}

/**
 * The `$` splat of a `/p/...` pathname, or null when the location is not a permalink at all. The prefix
 * arithmetic lives HERE rather than at each caller: a slice off by one segment hands [parsePermalink] a
 * string it will happily parse into a plausible, wrong answer.
 */
export function permalinkSplat(pathname: string): string | null {
  return pathname.startsWith(PERMALINK_PREFIX) ? pathname.slice(PERMALINK_PREFIX.length) : null;
}

/**
 * A parsed `/p/$` splat. `root` is null for a bare permalink (still legal, still served as the
 * shell). `prefix` is the `/p/...` address this parse was resolved FOR - the canonical-replace guard
 * compares against THIS rather than rebuilding a string, so the guard cannot drift from the parse.
 */
export interface ParsedPermalink {
  root: string | null;
  id: string;
  prefix: string;
}

export function parsePermalink(splat: string): ParsedPermalink {
  const segments = splat.split("/");
  // The server's structural gate, on the same shape (`dropLastWhile { it.isEmpty() }`, then reject any
  // remaining empty segment). A malformed splat rides through WHOLE as the id so the by-id read answers
  // the same 400 `invalid_page_id` the dispatcher would have - picking a segment out of it instead would
  // render a page (interior empty) or blame an empty root (leading empty) at an address the server refuses.
  while (segments.length > 0 && segments[segments.length - 1] === "") segments.pop();
  if (segments.length === 0 || segments.includes("")) return { root: null, id: splat, prefix: `${PERMALINK_PREFIX}${splat}` };
  const [first, second] = segments;
  // `second !== undefined` mirrors the server route's explicit two-segment check. It is inert on its
  // own (an undefined second segment stringifies and fails the gate anyway), so no test guards it.
  if (!PAGE_ID_TEXT.test(first) && second !== undefined && CANONICAL_PAGE_ID.test(second)) {
    return { root: first, id: second, prefix: permalinkOf(first, second) };
  }
  return { root: null, id: first, prefix: `${PERMALINK_PREFIX}${first}` };
}
