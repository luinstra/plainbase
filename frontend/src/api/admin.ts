import { ApiError, getJson } from "./client";
import { withCsrf } from "./csrf";
import type {
  AuditListResponse,
  CreatedTokenResponse,
  ErrorEnvelope,
  RoleListResponse,
  SessionResponse,
  TokenListResponse,
  UserListResponse,
} from "./types";

/**
 * The A4b admin management API (server: AdminTokenRoutes.kt / AdminUserRoutes.kt). Reads use the shared `getJson`;
 * mutations go through the SHARED CSRF helper ([withCsrf]) — the same `X-CSRF-Token` mechanism every SPA mutation
 * (page PUT/POST in client.ts) uses, sourced once from `GET /api/v1/session` and cached, with a refresh-and-retry-once
 * on a stale-token 403. The token is the cookie-mode synchronizer token OR the proxy-mode double-submit token; the
 * helper is mode-agnostic.
 */

export async function getSession(): Promise<SessionResponse> {
  return getJson<SessionResponse>("/api/v1/session");
}

export async function listTokens(): Promise<TokenListResponse> {
  return getJson<TokenListResponse>("/api/v1/admin/tokens");
}

export async function mintToken(label: string, mode: string): Promise<CreatedTokenResponse> {
  return mutate<CreatedTokenResponse>("/api/v1/admin/tokens", { label, mode });
}

export async function revokeToken(id: string): Promise<void> {
  await mutateNoContent(`/api/v1/admin/tokens/${encodeURIComponent(id)}/revoke`);
}

export async function listAudit(limit = 100): Promise<AuditListResponse> {
  return getJson<AuditListResponse>(`/api/v1/admin/audit?limit=${limit}`);
}

export async function listRoles(): Promise<RoleListResponse> {
  return getJson<RoleListResponse>("/api/v1/admin/roles");
}

export async function grantRole(issuer: string, externalId: string, role: string): Promise<void> {
  await mutateNoContent("/api/v1/admin/roles", { issuer, external_id: externalId, role });
}

export async function listUsers(): Promise<UserListResponse> {
  return getJson<UserListResponse>("/api/v1/admin/users");
}

/** A JSON mutation with the shared CSRF header; returns the parsed body. Throws [ApiError] on a non-2xx (incl. 403). */
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

/** A mutation expecting 204 No Content; throws [ApiError] on a non-2xx. */
async function mutateNoContent(url: string, body?: unknown): Promise<void> {
  const response = await withCsrf((csrfHeaders) =>
    fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", ...csrfHeaders },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
  if (!response.ok) throw await apiError(response);
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
