import { queryOptions } from "@tanstack/react-query";
import { getJson } from "./client";
import type { PageHtmlResponse, PageResponse, TreeResponse } from "./types";

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
