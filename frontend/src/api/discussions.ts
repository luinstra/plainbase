import { infiniteQueryOptions } from "@tanstack/react-query";
import { getJson, pageEndpoint } from "./client";
import type { DiscussionDetailResponse, DiscussionListResponse } from "./types";

const rootPin = (root: string) => `root=${encodeURIComponent(root)}`;
const cursorQuery = (cursor: string | null) => cursor === null ? "" : `&cursor=${encodeURIComponent(cursor)}`;
const ROOT_DISCUSSION_LIMIT = 50;
const PAGE_DISCUSSION_LIMIT = 200;
const COMMENT_LIMIT = 50;

export function rootDiscussionsUrl(root: string, state: string | null, after: string | null): string {
  return `/api/v1/discussions?${rootPin(root)}${state === null ? "" : `&state=${encodeURIComponent(state)}`}&limit=${ROOT_DISCUSSION_LIMIT}${cursorQuery(after)}`;
}

export function pageDiscussionsUrl(root: string, pageId: string, after: string | null): string {
  return `${pageEndpoint(pageId)}/discussions?${rootPin(root)}&limit=${PAGE_DISCUSSION_LIMIT}${cursorQuery(after)}`;
}

export function discussionDetailUrl(root: string, id: string, after: string | null): string {
  return `/api/v1/discussions/${encodeURIComponent(id)}?${rootPin(root)}&limit=${COMMENT_LIMIT}${cursorQuery(after)}`;
}

export const rootDiscussionsQuery = (root: string, state: string | null) => infiniteQueryOptions({
  queryKey: ["discussions", "root", root, state, ROOT_DISCUSSION_LIMIT],
  queryFn: ({ pageParam }) => getJson<DiscussionListResponse>(rootDiscussionsUrl(root, state, pageParam)),
  initialPageParam: null as string | null,
  getNextPageParam: (page) => page.next,
});

export const pageDiscussionsQuery = (root: string, pageId: string) => infiniteQueryOptions({
  queryKey: ["discussions", "page", root, pageId, PAGE_DISCUSSION_LIMIT],
  queryFn: ({ pageParam }) => getJson<DiscussionListResponse>(pageDiscussionsUrl(root, pageId, pageParam)),
  initialPageParam: null as string | null,
  getNextPageParam: (page) => page.next,
});

export const discussionDetailQuery = (root: string, id: string) => infiniteQueryOptions({
  queryKey: ["discussions", "detail", root, id, COMMENT_LIMIT],
  queryFn: ({ pageParam }) => getJson<DiscussionDetailResponse>(discussionDetailUrl(root, id, pageParam)),
  initialPageParam: null as string | null,
  getNextPageParam: (page) => page.next,
});
