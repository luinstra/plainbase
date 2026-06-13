import { describe, expect, it } from "vitest";
import { SEARCH_LIMIT, SEARCH_MAX_QUERY, searchQuery } from "../api/queries";

/**
 * Resolution 3 — full-text query construction (criterion 14/16). The query key is the
 * trimmed `q` plus limit/offset, `enabled` gates on a non-empty `q`, and the request URL
 * carries the §A1-clamped parameters. The stale-response race itself (a later `q` painting
 * over an earlier slow one) is exercised end-to-end in searchPalette.test.tsx.
 */
describe("searchQuery", () => {
  it("keys on the trimmed q plus limit/offset", () => {
    expect(searchQuery("rolling deploy").queryKey).toEqual(["search", "rolling deploy", SEARCH_LIMIT, 0]);
    expect(searchQuery("a", 5, 10).queryKey).toEqual(["search", "a", 5, 10]);
  });

  it("is disabled for an empty q so invalid_query is impossible by construction", () => {
    expect(searchQuery("").enabled).toBe(false);
    expect(searchQuery("x").enabled).toBe(true);
  });

  it("fixes the default limit at SEARCH_LIMIT = 20 (≤ 100)", () => {
    expect(SEARCH_LIMIT).toBe(20);
    expect(searchQuery("x").queryKey[2]).toBe(20);
  });

  it("§A1 cap is 512 UTF-16 code units", () => {
    expect(SEARCH_MAX_QUERY).toBe(512);
  });

  it("encodes the query into the request URL", async () => {
    // The queryFn fetches; stub global fetch to capture the URL without a network call.
    let captured = "";
    const originalFetch = globalThis.fetch;
    globalThis.fetch = (async (url: string) => {
      captured = url;
      return new Response(JSON.stringify({ query: "a b", engine: "embedded", limit: 20, offset: 0, total: 0, hits: [] }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }) as typeof fetch;
    try {
      await searchQuery("a b").queryFn!({} as never);
    } finally {
      globalThis.fetch = originalFetch;
    }
    expect(captured).toBe("/api/v1/search?q=a%20b&limit=20&offset=0");
  });
});
