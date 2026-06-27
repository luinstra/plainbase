import { queryOptions, type QueryClient } from "@tanstack/react-query";
import { getJson, previewRaw } from "./client";
import type {
  ChangeDetail,
  DiffResponse,
  HistoryResponse,
  ListChangesResponse,
  PageHtmlResponse,
  PageResponse,
  PreviewResponse,
  SearchResponse,
  SessionResponse,
  TreeResponse,
} from "./types";

/**
 * Re-encodes a decoded `/docs/`-relative path for the by-path endpoint. The router hands
 * us the splat percent-DECODED; the server decodes exactly once (PB-LINK-1), so each
 * segment goes back through encodeURIComponent. This is transport encoding only — slug
 * semantics stay server-owned.
 */
export function encodeTreePath(path: string): string {
  return path.split("/").map(encodeURIComponent).join("/");
}

export const treeQuery = queryOptions({
  queryKey: ["tree"],
  queryFn: () => getJson<TreeResponse>("/api/v1/tree"),
  staleTime: 60_000,
});

/**
 * Decodes a `/docs/<splat>` canonical URL back to the DECODED splat `pageByPathQuery` is keyed by
 * (the inverse of {@link encodeTreePath}, mirroring the read path's canonical-redirect resolution in
 * `PageView.tsx`). Returns null for a non-`/docs/` URL (a collision loser has no by-path key). The
 * key is the URL splat WITHOUT the `.md` extension — NOT the content file path.
 */
export function byPathKeyForUrl(url: string | null): string | null {
  if (!url || !url.startsWith("/docs/")) return null;
  return url.slice("/docs/".length).split("/").map(decodeURIComponent).join("/");
}

export const pageByPathQuery = (path: string) =>
  queryOptions({
    queryKey: ["page", "by-path", path],
    queryFn: () => getJson<PageResponse>(`/api/v1/pages/by-path/${encodeTreePath(path)}`),
    staleTime: 30_000,
  });

export const pageQuery = (id: string) =>
  queryOptions({
    queryKey: ["page", "by-id", id],
    queryFn: () => getJson<PageResponse>(`/api/v1/pages/${id}`),
    staleTime: 30_000,
  });

export const pageHtmlQuery = (id: string) =>
  queryOptions({
    queryKey: ["page", "html", id],
    queryFn: () => getJson<PageHtmlResponse>(`/api/v1/pages/${id}/html`),
    staleTime: 30_000,
  });

/**
 * W7 per-page commit history (NEWEST-FIRST). Fired ONLY when the user OPENS `?mode=history`; the read
 * view never mounts this (the footer affordance gates on the already-loaded `PageResponse.commit`).
 * `retry: false` so a 5xx from an uncapped git subprocess isn't multiplied 3× into a subprocess storm.
 */
export const historyQuery = (id: string) =>
  queryOptions({
    queryKey: ["page", "history", id],
    queryFn: () => getJson<HistoryResponse>(`/api/v1/pages/${id}/history`),
    staleTime: 30_000,
    retry: false,
  });

/**
 * A two-commit unified diff. Keyed on (id, from, to); only fires once both refs are chosen. `from`/`to`
 * are list-sourced full hex SHAs (always matching the server's `[0-9a-fA-F]{7,64}` guard) — they're
 * `encodeURIComponent`'d anyway as a belt-and-suspenders transport invariant. `retry: false` for the
 * same subprocess-storm reason as `historyQuery`.
 */
export const diffQuery = (id: string, from: string | null, to: string | null) =>
  queryOptions({
    queryKey: ["page", "diff", id, from, to],
    queryFn: () => getJson<DiffResponse>(`/api/v1/pages/${id}/diff?from=${encodeURIComponent(from!)}&to=${encodeURIComponent(to!)}`),
    enabled: from !== null && to !== null,
    staleTime: 30_000,
    retry: false,
  });

/** §A1: `limit` is 1–100; S7 always sends a fixed page of 20 (no user-controlled limit). */
export const SEARCH_LIMIT = 20;
/** §A1: `q` is ≤ 512 UTF-16 code units; the client clamps so `invalid_query` is unreachable. */
export const SEARCH_MAX_QUERY = 512;

/**
 * Full-text query (Resolution 3). Keyed on the TRIMMED `q` (plus limit/offset): TanStack
 * Query attaches each response to its key, so a slow earlier response lands on its own
 * no-longer-observed key and can never paint over the active `q` — the out-of-order race
 * fix. `enabled: q.length > 0` means a blank query never fires, so §A1 `invalid_query` is
 * impossible by construction.
 */
export function searchQuery(q: string, limit = SEARCH_LIMIT, offset = 0) {
  return queryOptions({
    queryKey: ["search", q, limit, offset],
    queryFn: () => getJson<SearchResponse>(`/api/v1/search?q=${encodeURIComponent(q)}&limit=${limit}&offset=${offset}`),
    enabled: q.length > 0,
    staleTime: 30_000,
  });
}

/**
 * The editor's debounced server-preview (W6). Keyed on the (debounced) buffer + path: TanStack Query
 * attaches each render to its key, so a slow earlier render lands on its own no-longer-observed key and
 * never paints over the active buffer — the same out-of-order safety the search query relies on.
 * `enabled: text.length > 0` keeps an empty buffer from POSTing. Preview is non-contractual, so the
 * result is held only as long as it is the active buffer (a short `staleTime`, no long cache).
 *
 * `gcTime` is bounded too: the key includes the FULL buffer, so each debounced render of a large page
 * lands on its own key and `staleTime` alone would let every stale copy of source+rendered HTML linger
 * for the default 5 minutes. A short `gcTime` collects superseded entries promptly so a long editing
 * session can't hoard memory (long enough to still serve the in-flight debounced render).
 */
export function previewQuery(text: string, path?: string) {
  return queryOptions({
    queryKey: ["preview", path ?? null, text],
    queryFn: () => previewRaw(text, path),
    enabled: text.length > 0,
    staleTime: 5_000,
    gcTime: 5_000,
  });
}

/**
 * The single post-write cache-invalidation point (every save/create success path calls THIS, never an
 * ad-hoc per-site `invalidateQueries`). A write that adds/removes/changes content can stale: the tree
 * (sidebar), the page's id-keyed reads (`pageQuery`/`pageHtmlQuery`), its history (`historyQuery` — a save
 * commits, so the commit list grows; W7 master criterion 6), the destination URL's by-path read
 * (`pageByPathQuery` — keyed by the URL splat, NOT the content file path; reuse {@link byPathKeyForUrl}),
 * AND any full-text `['search', …]` result (full-text goes stale on ANY content edit). Pass whatever of
 * {id, url} the calling path knows; an absent/non-`/docs/` url no-ops its by-path leg. Covering the whole
 * `['page', 'by-path']` namespace too leaves NEITHER a stale old nor new location after a rename/recovery.
 */
export function invalidateAfterWrite(queryClient: QueryClient, { id, url }: { id?: string; url?: string | null }): void {
  void queryClient.invalidateQueries({ queryKey: treeQuery.queryKey });
  void queryClient.invalidateQueries({ queryKey: ["search"] });
  if (id) {
    void queryClient.invalidateQueries({ queryKey: pageQuery(id).queryKey });
    void queryClient.invalidateQueries({ queryKey: pageHtmlQuery(id).queryKey });
    // A save commits — the commit list grew, and the read view's freshly-non-null `commit` lights up the
    // footer history affordance (refreshed via the pageQuery/pageByPathQuery legs above) with no extra fetch.
    void queryClient.invalidateQueries({ queryKey: historyQuery(id).queryKey });
  }
  const byPathKey = byPathKeyForUrl(url ?? null);
  if (byPathKey !== null) void queryClient.invalidateQueries({ queryKey: pageByPathQuery(byPathKey).queryKey });
  // Leave neither the old nor the new by-path location stale (a rename changes the URL key; the 200 doesn't carry it).
  void queryClient.invalidateQueries({ queryKey: ["page", "by-path"] });
}

/**
 * The auth/session read (A4b) — `authenticated` / `username` / `csrf_token` / `auth_mode`, nothing more (no
 * role, no capabilities — F8). The Shell gates the "Review" nav link on `authenticated`; the review route uses
 * it too. csrf.ts keeps its OWN independent `/session` fetch (the mutation-token cache) — leave it.
 */
export const sessionQuery = queryOptions({
  queryKey: ["session"],
  queryFn: () => getJson<SessionResponse>("/api/v1/session"),
  staleTime: 60_000,
});

/**
 * PB-PROPOSE-1 review queue — `GET /api/v1/changes` returns ALL statuses (the client decides presentation —
 * F1). `checkRead` suffices, so any authenticated reader can list.
 */
export const changesQuery = queryOptions({
  queryKey: ["changes"],
  queryFn: () => getJson<ListChangesResponse>("/api/v1/changes"),
  staleTime: 30_000,
});

/** A single proposed change (`get_change`) — the stored diff + decision fields + the live `base_drifted` flag. */
export const changeQuery = (id: string) =>
  queryOptions({
    queryKey: ["change", id],
    queryFn: () => getJson<ChangeDetail>(`/api/v1/changes/${encodeURIComponent(id)}`),
    staleTime: 30_000,
  });

/**
 * The post-DECISION cache-invalidation sibling to {@link invalidateAfterWrite}: a reject/approve/rebase changes
 * the proposal row, so the list (`["changes"]`) and the detail (`changeQuery(id)`) both go stale. A successful
 * APPROVE ALSO moved the content tree (an apply writes a page) — the caller adds `invalidateAfterWrite` for the
 * affected `page_id` (re-using the one write-invalidation funnel; skipped when `page_id` is null, F4/WI-2).
 */
export function invalidateAfterDecision(queryClient: QueryClient, id: string): void {
  void queryClient.invalidateQueries({ queryKey: changesQuery.queryKey });
  void queryClient.invalidateQueries({ queryKey: changeQuery(id).queryKey });
}

export type { PreviewResponse };
