import type {
  BodyTooLargeEnvelope,
  CreatePageRequest,
  CreatedResponse,
  ErrorEnvelope,
  PageExistsEnvelope,
  PreviewResponse,
  UnsupportedEditEnvelope,
  WriteConflictEnvelope,
  WrittenButUnindexedResponse,
  WrittenResponse,
} from "./types";

/** A non-2xx API answer, carrying the frozen error-envelope code (§A4). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { accept: "application/json" } });
  if (!response.ok) throw await apiError(response);
  return response.json() as Promise<T>;
}

/**
 * Safely parses a JSON body, returning `null` on a thrown/invalid body (e.g. a proxy's HTML error page).
 * Parses a `clone()` so the ORIGINAL body stream stays unconsumed — a caller that falls back to
 * `apiError(response)` can then re-read the real body for a better message (a proxy's JSON error
 * envelope), instead of hitting an already-locked stream.
 */
async function parseJson<T>(response: Response): Promise<T | null> {
  try {
    return (await response.clone().json()) as T;
  } catch {
    return null;
  }
}

/** Parses a non-2xx body as the frozen `ErrorEnvelope`, falling back to a status-derived message. */
async function apiError(response: Response): Promise<ApiError> {
  let code = "unknown_error";
  let message = `Request failed with status ${response.status}`;
  try {
    const envelope = (await response.json()) as ErrorEnvelope;
    code = envelope.error.code;
    message = envelope.error.message;
  } catch {
    // non-envelope body (proxy error page etc.) — keep the status-derived message
  }
  return new ApiError(response.status, code, message);
}

/**
 * The PB-WRITE-1 save outcome the editor switches on (D-5). A 409/422/413 is NOT thrown — it is a
 * first-class result so the conflict UX is exhaustive over the frozen `reason`/`code` discriminators;
 * only the generic families (404/415/503/unknown) reach `error`. The buffer is NEVER discarded by the
 * client on any of these — the editor decides.
 */
export type SaveResult =
  | { kind: "saved"; written: WrittenResponse | WrittenButUnindexedResponse }
  | { kind: "conflict"; conflict: WriteConflictEnvelope["error"] }
  | { kind: "unsupported"; unsupported: UnsupportedEditEnvelope["error"] }
  | { kind: "too-large"; maxBytes: number }
  | { kind: "error"; error: ApiError };

/**
 * Saves the FULL document buffer verbatim (W6 / D-4): the body is the EXACT bytes as `text/markdown`,
 * `baseHash` rides the `If-Match` strong ETag `"sha256:<hex>"` (echoed from the GET's `content_hash`,
 * never re-derived). The 200 carries the next CAS token; the typed conflict/refusal results drive the
 * editor's UX without losing the buffer.
 */
export async function putPageRaw(id: string, body: string, baseHash: string): Promise<SaveResult> {
  let response: Response;
  try {
    response = await fetch(`/api/v1/pages/${id}`, {
      method: "PUT",
      headers: { "content-type": "text/markdown", "if-match": `"${baseHash}"` },
      body,
    });
  } catch {
    // fetch rejects on offline/timeout (TypeError) — surface it as the transient error the editor retries.
    return { kind: "error", error: networkError() };
  }
  // A non-JSON body on ANY typed status (e.g. a reverse proxy's HTML 413/200) degrades to the generic
  // error family the editor retries — never an uncaught SyntaxError that freezes "Saving…".
  if (response.ok) {
    const written = await parseJson<WrittenResponse | WrittenButUnindexedResponse>(response);
    return written ? { kind: "saved", written } : { kind: "error", error: await apiError(response) };
  }
  if (response.status === 409) {
    const envelope = await parseJson<WriteConflictEnvelope>(response);
    return envelope ? { kind: "conflict", conflict: envelope.error } : { kind: "error", error: await apiError(response) };
  }
  if (response.status === 422) {
    const envelope = await parseJson<UnsupportedEditEnvelope>(response);
    return envelope ? { kind: "unsupported", unsupported: envelope.error } : { kind: "error", error: await apiError(response) };
  }
  if (response.status === 413) {
    const envelope = await parseJson<BodyTooLargeEnvelope>(response);
    return envelope ? { kind: "too-large", maxBytes: envelope.error.max_bytes } : { kind: "error", error: await apiError(response) };
  }
  return { kind: "error", error: await apiError(response) };
}

/** The new-page create outcome — a 201 with the server `id`+`url`, or the create-collision families. */
export type CreateResult =
  | { kind: "created"; created: CreatedResponse }
  | { kind: "exists"; exists: PageExistsEnvelope["error"] }
  | { kind: "error"; error: ApiError };

/**
 * Creates a page (W6 / D-2): a JSON request; the server mints the id, derives the path/slug, and the
 * 201 returns the minted `id` + the server-authoritative canonical `url` (the client navigates straight
 * to it — no tree re-resolve, no client slug derivation). A 409 `page_exists`/`slug_conflict` carries
 * the attempted `path`.
 */
export async function createPage(request: CreatePageRequest): Promise<CreateResult> {
  let response: Response;
  try {
    response = await fetch("/api/v1/pages", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(request),
    });
  } catch {
    return { kind: "error", error: networkError() };
  }
  if (response.ok) {
    const created = await parseJson<CreatedResponse>(response);
    return created ? { kind: "created", created } : { kind: "error", error: await apiError(response) };
  }
  if (response.status === 409) {
    const envelope = await parseJson<PageExistsEnvelope>(response);
    return envelope ? { kind: "exists", exists: envelope.error } : { kind: "error", error: await apiError(response) };
  }
  return { kind: "error", error: await apiError(response) };
}

/** A `fetch` that never reached the server (offline/timeout) — modeled as a transient 503 so the UX says "retry". */
function networkError(): ApiError {
  return new ApiError(503, "network_error", "Couldn't reach the server — check your connection and retry.");
}

/**
 * Renders a Markdown buffer to HTML server-side (`POST /api/v1/preview`, NON-CONTRACTUAL): the RAW
 * `text/markdown` body is the buffer; the optional `path` is the relative-link resolution base. The
 * `html` is best-effort presentation (rendered via `<Prose>`), never a byte-equal claim.
 */
export async function previewRaw(body: string, path?: string): Promise<PreviewResponse> {
  const url = path ? `/api/v1/preview?path=${encodeURIComponent(path)}` : "/api/v1/preview";
  const response = await fetch(url, { method: "POST", headers: { "content-type": "text/markdown" }, body });
  if (!response.ok) throw await apiError(response);
  return response.json() as Promise<PreviewResponse>;
}
