import type { SessionResponse } from "./types";

/**
 * The ONE CSRF mechanism every SPA state-mutation shares (A4b). A mutation cannot send `X-CSRF-Token`
 * out of thin air: the token is minted server-side and surfaced by `GET /api/v1/session` as `csrf_token`
 * — the cookie-mode synchronizer token (builtin) OR the stateless double-submit token (proxy). Both modes
 * expose it the same way, so this helper is mode-agnostic.
 *
 * The token is fetched once and CACHED (a per-session value rarely changes); [withCsrf] attaches it to a
 * request and, on a 403 `csrf_failed` (a token that went stale after a session change), re-fetches `/session`
 * ONCE and retries. `off` mode does NOT register `/session` (it is wired only when builtin or proxy auth is
 * enabled), so the helper maps the resulting non-ok response to a null token; off-mode mutations are unenforced,
 * so the null token is sent as an empty header and the mutation still succeeds.
 */

let cached: Promise<string | null> | null = null;

/** Fetches `GET /api/v1/session` and returns its `csrf_token` (null in `off` mode / when unauthenticated). */
async function fetchCsrfToken(): Promise<string | null> {
  const response = await fetch("/api/v1/session", { headers: { accept: "application/json" } });
  if (!response.ok) return null;
  const session = (await response.json()) as SessionResponse;
  return session.csrf_token;
}

/** The cached CSRF token, fetching `/session` on first use (null in `off` mode). [forceRefresh] re-fetches (a stale-token retry). */
export async function csrfToken(forceRefresh = false): Promise<string | null> {
  if (forceRefresh || cached === null) {
    cached = fetchCsrfToken().catch(() => null);
  }
  return cached;
}

/** Clears the cached token (a session change — login/logout — must re-mint on the next mutation). */
export function clearCsrfToken(): void {
  cached = null;
}

/**
 * Runs a state-mutating fetch with the cached `X-CSRF-Token` attached, refreshing the token and retrying ONCE
 * when the first attempt is a 403 `csrf_failed` (a token gone stale after a session change). [send] receives the
 * header bag to merge into its request; it owns the method/body/url. The non-csrf 403 (a real authz denial) is
 * returned as-is — only `csrf_failed` triggers the refresh-retry.
 */
export async function withCsrf(send: (csrfHeaders: Record<string, string>) => Promise<Response>): Promise<Response> {
  const token = await csrfToken();
  const first = await send({ "X-CSRF-Token": token ?? "" });
  if (first.status !== 403 || !(await isCsrfFailure(first))) return first;
  const refreshed = await csrfToken(true);
  return send({ "X-CSRF-Token": refreshed ?? "" });
}

/** True when a 403 response carries the frozen `csrf_failed` code (a stale token) — NOT a real authz denial. */
async function isCsrfFailure(response: Response): Promise<boolean> {
  try {
    const envelope = (await response.clone().json()) as { error?: { code?: string } };
    return envelope.error?.code === "csrf_failed";
  } catch {
    return false;
  }
}
