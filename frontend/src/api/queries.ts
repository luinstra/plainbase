import { queryOptions } from "@tanstack/react-query";
import { getJson } from "./client";
import type { PageHtmlResponse, PageResponse, SearchResponse, TreeResponse } from "./types";

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
