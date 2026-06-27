import { ApiError } from "./client";
import { withCsrf } from "./csrf";
import type { ApplyResultResponse, ChangeDetail, ErrorEnvelope, RebasedResponse } from "./types";

/**
 * PB-PROPOSE-1 (P1a/P1b) decision mutations — approve / reject / rebase a proposed change. Mirrors the
 * `api/admin.ts` `mutate` idiom: a `withCsrf` POST that throws [ApiError] on ANY non-2xx (incl. 403/409),
 * so the ReviewDetail component catches and branches on `error.status` / `error.code` — a leaner shape than a
 * typed-result union, and the 409-conflict path is best handled by refetching the detail (which then shows
 * `CONFLICTED` + `base_drifted` → the banner/rebase appear), not by threading a conflict body through here.
 *
 * Casing asymmetry (F4): approve + rebase send NO request body (the server reads none / does no content-type
 * check); reject carries the reviewer `{comment}` (the comment is reject-only). The shared `mutate` sets
 * `content-type: application/json` unconditionally — harmless for the bodyless calls — so it's reused verbatim.
 */

/** Approve (apply) a PENDING edit. 200 → [ApplyResultResponse]; 409 `conflicted` / 422 / 409 `not_pending` throw. */
export async function approveChange(id: string): Promise<ApplyResultResponse> {
  return mutate<ApplyResultResponse>(`/api/v1/changes/${encodeURIComponent(id)}/approve`);
}

/** Reject a PENDING change with an optional reviewer comment. 200 → the updated [ChangeDetail]. */
export async function rejectChange(id: string, comment: string | null): Promise<ChangeDetail> {
  return mutate<ChangeDetail>(`/api/v1/changes/${encodeURIComponent(id)}/reject`, { comment });
}

/** Rebase a CONFLICTED edit onto the current base. 200 → [RebasedResponse]; the proposal returns to PENDING. */
export async function rebaseChange(id: string): Promise<RebasedResponse> {
  return mutate<RebasedResponse>(`/api/v1/changes/${encodeURIComponent(id)}/rebase`);
}

/** A JSON mutation with the shared CSRF header; returns the parsed body. Throws [ApiError] on a non-2xx. */
async function mutate<T>(url: string, body?: unknown): Promise<T> {
  const response = await withCsrf((csrfHeaders) =>
    fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", ...csrfHeaders },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
  if (!response.ok) throw await apiError(response);
  return response.json() as Promise<T>;
}

async function apiError(response: Response): Promise<ApiError> {
  let code = "unknown_error";
  let message = `Request failed with status ${response.status}`;
  try {
    const envelope = (await response.json()) as ErrorEnvelope;
    code = envelope.error.code;
    message = envelope.error.message;
  } catch {
    // non-envelope body — keep the status-derived message
  }
  return new ApiError(response.status, code, message);
}
