import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPage, putPageRaw } from "../api/client";
import { clearCsrfToken } from "../api/csrf";

/**
 * FIX 4 (dual-model review): a `fetch` that rejects (offline/timeout → TypeError) must surface as the
 * typed error variant of the write unions — never an unhandled rejection that leaves "Saving…" stuck.
 */
const HASH = "sha256:0000000000000000000000000000000000000000000000000000000000000000";

// The CSRF helper caches a module-level token across calls — reset it so each test starts from a cold cache and
// the first mutation re-fetches `/session`.
beforeEach(() => clearCsrfToken());
afterEach(() => {
  vi.unstubAllGlobals();
  clearCsrfToken();
});

describe("write client network-error handling", () => {
  it("putPageRaw returns a typed transient error when fetch rejects (does not throw)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    const result = await putPageRaw("id", "buffer", HASH);
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.status).toBe(503);
      expect(result.error.message).toContain("Couldn't reach the server");
    }
  });

  it("createPage returns a typed transient error when fetch rejects (does not throw)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    const result = await createPage({ title: "X" });
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.status).toBe(503);
    }
  });
});

/**
 * Multi-model review: a non-JSON body on a TYPED status (a reverse proxy's HTML page — most notably a
 * 413 from `client_max_body_size` before the request reaches the app, or a buffered gateway 200/409)
 * must degrade to the generic `error` family, NOT throw a `SyntaxError` that freezes "Saving…".
 */
function htmlResponse(status: number): Response {
  return new Response("<html><body>413 Request Entity Too Large</body></html>", {
    status,
    headers: { "content-type": "text/html" },
  });
}

describe("write client non-JSON body handling", () => {
  it("putPageRaw degrades a 413 with an HTML proxy body to a typed error (does not throw)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => htmlResponse(413)),
    );
    const result = await putPageRaw("id", "buffer", HASH);
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.status).toBe(413);
    }
  });

  it("putPageRaw degrades a 200 with a non-JSON body to a typed error (editor does not freeze)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => htmlResponse(200)),
    );
    const result = await putPageRaw("id", "buffer", HASH);
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.status).toBe(200);
    }
  });

  it("createPage degrades a 409 with a non-JSON body to a typed error (does not throw)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => htmlResponse(409)),
    );
    const result = await createPage({ title: "X" });
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.status).toBe(409);
    }
  });
});

/**
 * P5 (diverse-lens review): a 202 `DegradedToProposalResponse` is `response.ok`, so without a dedicated branch
 * `putPageRaw` would misclassify an UN-applied agent write as `{kind:"saved"}` (advancing the baseline + showing
 * "Saved"). It MUST surface a distinct `degraded` kind. Latent for the cookie-auth SPA, but the type demands it.
 */
function degradedResponse(): Response {
  return new Response(
    JSON.stringify({ degraded: true, proposal_id: "0198abc", status: "PENDING", unified_diff: "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n" }),
    { status: 202, headers: { "content-type": "application/json" } },
  );
}

describe("write client 202 degrade-to-proposal handling", () => {
  it("putPageRaw maps a 202 degraded response to the `degraded` kind, NEVER `saved`", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => degradedResponse()),
    );
    const result = await putPageRaw("id", "buffer", HASH);
    expect(result.kind).toBe("degraded");
    if (result.kind === "degraded") {
      expect(result.proposalId).toBe("0198abc");
      expect(result.status).toBe("PENDING");
      expect(result.unifiedDiff).toContain("@@");
    }
  });
});

/**
 * FIX F-a (multi-model review): `parseJson` now parses a `clone()`, leaving the ORIGINAL body stream
 * unconsumed. On a status with no typed branch (here 503), the generic `apiError(response)` fallback can
 * therefore re-read the real body and surface the proxy's actual JSON error envelope — its code/message,
 * not a status-derived placeholder. Without the clone the body would already be locked.
 */
function envelopeResponse(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({ error: { code, message } }), {
    status,
    headers: { "content-type": "application/json" },
  });
}

describe("write client error-envelope fallback (clone keeps the body readable)", () => {
  it("putPageRaw surfaces the JSON error envelope's code/message on an untyped status", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => envelopeResponse(503, "content_unreadable", "the file is temporarily locked")),
    );
    const result = await putPageRaw("id", "buffer", HASH);
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.status).toBe(503);
      expect(result.error.code).toBe("content_unreadable");
      expect(result.error.message).toBe("the file is temporarily locked");
    }
  });
});

/**
 * B2 (cross-model review): the editor/new-page mutations are CSRF-enforced server-side under `auth.mode`, so EVERY
 * SPA mutation must source the `X-CSRF-Token` from `GET /api/v1/session` and attach it — and refresh+retry once on a
 * stale-token 403. The whole CLASS of mutations (page PUT, page POST, admin POSTs — admin covered separately) shares
 * the ONE csrf.ts helper. These pin the editor flows specifically (the gap B2 found).
 */
function sessionResponse(token: string | null): Response {
  return new Response(JSON.stringify({ authenticated: true, username: "u", csrf_token: token, auth_mode: "builtin" }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function writtenResponse(): Response {
  return new Response(JSON.stringify({ content_hash: "sha256:abc", commit: null }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function createdResponse(): Response {
  return new Response(JSON.stringify({ id: "p1", url: "/docs/p1", content_hash: "sha256:abc", commit: null }), {
    status: 201,
    headers: { "content-type": "application/json" },
  });
}

function csrfFailed(): Response {
  return new Response(JSON.stringify({ error: { code: "csrf_failed", message: "Missing or invalid X-CSRF-Token" } }), {
    status: 403,
    headers: { "content-type": "application/json" },
  });
}

/** A fetch stub that answers `/session` from a queue of tokens (one per refresh) and routes mutations to [onMutation]. */
function stubFetchWithSession(tokens: string[], onMutation: (csrf: string | null) => Response) {
  let sessionCall = 0;
  const spy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    if (url === "/api/v1/session") return sessionResponse(tokens[sessionCall++] ?? tokens.at(-1) ?? null);
    const csrf = (init?.headers as Record<string, string> | undefined)?.["X-CSRF-Token"] ?? null;
    return onMutation(csrf);
  });
  vi.stubGlobal("fetch", spy);
  return spy;
}

describe("write client CSRF wiring (editor + new-page flows)", () => {
  it("putPageRaw attaches the X-CSRF-Token sourced from /session", async () => {
    let sentCsrf: string | null = null;
    stubFetchWithSession(["tok-1"], (csrf) => {
      sentCsrf = csrf;
      return writtenResponse();
    });
    const result = await putPageRaw("id", "buffer", HASH);
    expect(result.kind).toBe("saved");
    expect(sentCsrf).toBe("tok-1");
  });

  it("createPage attaches the X-CSRF-Token sourced from /session", async () => {
    let sentCsrf: string | null = null;
    stubFetchWithSession(["tok-1"], (csrf) => {
      sentCsrf = csrf;
      return createdResponse();
    });
    const result = await createPage({ title: "X" });
    expect(result.kind).toBe("created");
    expect(sentCsrf).toBe("tok-1");
  });

  it("a stale-token 403 csrf_failed refreshes /session and retries ONCE with the fresh token", async () => {
    const seen: (string | null)[] = [];
    const spy = stubFetchWithSession(["stale", "fresh"], (csrf) => {
      seen.push(csrf);
      return seen.length === 1 ? csrfFailed() : writtenResponse();
    });
    const result = await putPageRaw("id", "buffer", HASH);
    expect(result.kind).toBe("saved");
    // First attempt with the stale token, then the refreshed one — exactly two mutation attempts.
    expect(seen).toEqual(["stale", "fresh"]);
    // /session fetched twice (initial + the refresh), mutation twice → four fetches total.
    expect(spy).toHaveBeenCalledTimes(4);
  });

  it("a 403 that is NOT csrf_failed is surfaced as-is (no refresh, no retry)", async () => {
    const seen: (string | null)[] = [];
    const forbidden = new Response(JSON.stringify({ error: { code: "forbidden", message: "no" } }), {
      status: 403,
      headers: { "content-type": "application/json" },
    });
    stubFetchWithSession(["tok-1"], (csrf) => {
      seen.push(csrf);
      return forbidden;
    });
    const result = await putPageRaw("id", "buffer", HASH);
    expect(result.kind).toBe("error");
    if (result.kind === "error") expect(result.error.code).toBe("forbidden");
    expect(seen).toEqual(["tok-1"]); // exactly one attempt — a real authz denial is not a CSRF retry
  });
});
