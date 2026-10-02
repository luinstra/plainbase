import { infiniteQueryOptions, type QueryClient } from "@tanstack/react-query";
import { apiError, getJson, pageEndpoint } from "./client";
import { withCsrf } from "./csrf";
import type { DiscussionDetailResponse, DiscussionListResponse, DiscussionMutationResponse, DiscussionPreviewResponse, DiscussionQuoteRequestAnchor, DiscussionRequestAnchor } from "./types";

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

/** A 2xx whose identity cannot be trusted: the write may have happened. */
export class UncertainDiscussionOutcomeError extends Error {
  constructor() { super("The response could not confirm the outcome. Refresh and check before trying again."); }
}

export function previewDiscussionAnchor(root: string, pageId: string, anchor: DiscussionQuoteRequestAnchor): Promise<DiscussionPreviewResponse> {
  return postJson(`${pageEndpoint(pageId)}/discussions/anchor-preview?${rootPin(root)}`, anchor, isPreview, 200);
}

export function startDiscussion(root: string, pageId: string, anchor: DiscussionRequestAnchor, body: string): Promise<DiscussionMutationResponse> {
  return postJson(`${pageEndpoint(pageId)}/discussions?${rootPin(root)}`, { anchor, body }, isMutation, 201);
}

export function addDiscussionComment(root: string, id: string, body: string): Promise<DiscussionMutationResponse> {
  return postJson(`/api/v1/discussions/${encodeURIComponent(id)}/comments?${rootPin(root)}`, { body }, isMutation, 201);
}

export function editDiscussionComment(root: string, id: string, commentId: string, body: string): Promise<DiscussionMutationResponse> {
  return postJson(`/api/v1/discussions/${encodeURIComponent(id)}/comments/${encodeURIComponent(commentId)}/edit?${rootPin(root)}`, { body }, isMutation, 200);
}

export function retractDiscussionComment(root: string, id: string, commentId: string): Promise<DiscussionMutationResponse> {
  return postJson(`/api/v1/discussions/${encodeURIComponent(id)}/comments/${encodeURIComponent(commentId)}/retract?${rootPin(root)}`, {}, isMutation, 200);
}

export function purgeDiscussionComment(root: string, id: string, commentId: string): Promise<DiscussionMutationResponse> {
  return postJson(`/api/v1/discussions/${encodeURIComponent(id)}/comments/${encodeURIComponent(commentId)}/purge?${rootPin(root)}`, {}, isMutation, 200);
}

export function resolveDiscussion(root: string, id: string): Promise<DiscussionMutationResponse> {
  return postJson(`/api/v1/discussions/${encodeURIComponent(id)}/resolve?${rootPin(root)}`, {}, isMutation, 200);
}

export function reopenDiscussion(root: string, id: string): Promise<DiscussionMutationResponse> {
  return postJson(`/api/v1/discussions/${encodeURIComponent(id)}/reopen?${rootPin(root)}`, {}, isMutation, 200);
}

export function reattachDiscussion(root: string, id: string, anchor: DiscussionQuoteRequestAnchor): Promise<DiscussionMutationResponse> {
  return postJson(`/api/v1/discussions/${encodeURIComponent(id)}/reattach?${rootPin(root)}`, { anchor }, isMutation, 200);
}

async function postJson<T>(url: string, body: unknown, valid: (value: unknown) => value is T, successStatus: number): Promise<T> {
  const response = await withCsrf((headers) => fetch(url, {
    method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body),
  }));
  if (!response.ok) throw await apiError(response);
  if (response.status !== successStatus) throw new UncertainDiscussionOutcomeError();
  let data: unknown;
  try { data = await response.json(); } catch { throw new UncertainDiscussionOutcomeError(); }
  if (!valid(data)) throw new UncertainDiscussionOutcomeError();
  return data;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isMutation(value: unknown): value is DiscussionMutationResponse {
  return isRecord(value) && typeof value.id === "string" && value.id.length > 0 &&
    (value.comment_id === null || typeof value.comment_id === "string" && value.comment_id.length > 0) &&
    (value.commit === null || typeof value.commit === "string");
}

function isPreview(value: unknown): value is DiscussionPreviewResponse {
  return isRecord(value) && typeof value.content_hash === "string" &&
    Number.isSafeInteger(value.byte_start) && Number.isSafeInteger(value.byte_end) &&
    (value.byte_start as number) >= 0 && (value.byte_end as number) > (value.byte_start as number) &&
    (value.selection === "narrowed" || value.selection === "snapped") && typeof value.quote_text === "string";
}

/** Refresh only cached discussion views for the submitted target, preserving cursor windows. */
export async function refreshDiscussionViews(client: QueryClient, root: string, id?: string): Promise<void> {
  const jobs = [
    client.invalidateQueries({ queryKey: ["discussions", "root", root] }, { throwOnError: true }),
    client.invalidateQueries({ queryKey: ["discussions", "page", root] }, { throwOnError: true }),
  ];
  if (id) jobs.push(client.invalidateQueries({ queryKey: ["discussions", "detail", root, id] }, { throwOnError: true }));
  const results = await Promise.allSettled(jobs);
  if (results.some((result) => result.status === "rejected")) throw new Error("Discussion view could not refresh");
}
