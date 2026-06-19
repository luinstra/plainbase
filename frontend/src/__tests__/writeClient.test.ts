import { afterEach, describe, expect, it, vi } from "vitest";
import { createPage, putPageRaw } from "../api/client";

/**
 * FIX 4 (dual-model review): a `fetch` that rejects (offline/timeout → TypeError) must surface as the
 * typed error variant of the write unions — never an unhandled rejection that leaves "Saving…" stuck.
 */
const HASH = "sha256:0000000000000000000000000000000000000000000000000000000000000000";

afterEach(() => vi.unstubAllGlobals());

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
