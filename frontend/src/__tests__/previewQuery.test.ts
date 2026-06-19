import { describe, expect, it } from "vitest";
import { previewQuery } from "../api/queries";

/**
 * W6 debounced server-preview query construction (FIX 4). The key includes the FULL Markdown buffer, so
 * each debounced render lands on its own key; a bounded `gcTime` collects superseded entries promptly so
 * a long editing session can't hoard copies of source + rendered HTML (`staleTime` alone does NOT bound
 * retention). The queryFn still returns the rendered HTML.
 */
describe("previewQuery", () => {
  it("keys on the buffer + path and is disabled for an empty buffer", () => {
    expect(previewQuery("# hi", "guides/x.md").queryKey).toEqual(["preview", "guides/x.md", "# hi"]);
    expect(previewQuery("").enabled).toBe(false);
    expect(previewQuery("x").enabled).toBe(true);
  });

  it("sets a bounded gcTime so stale preview entries are collected (not held the default ~5min)", () => {
    const q = previewQuery("# hi");
    expect(q.gcTime).toBeDefined();
    // Short enough to GC promptly — well under TanStack's 5-minute default.
    expect(q.gcTime!).toBeLessThanOrEqual(10_000);
  });

  it("still returns the rendered HTML from POST /api/v1/preview", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = (async () =>
      new Response(JSON.stringify({ html: "<p>rendered</p>", headings: [] }), {
        status: 200,
        headers: { "content-type": "application/json" },
      })) as typeof fetch;
    try {
      const result = await previewQuery("# hi").queryFn!({} as never);
      expect(result.html).toBe("<p>rendered</p>");
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
