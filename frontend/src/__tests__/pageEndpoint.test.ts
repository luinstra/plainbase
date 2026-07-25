import { afterEach, describe, expect, it, vi } from "vitest";
import { pageEndpoint } from "../api/client";
import { diffQuery, historyQuery, pageHtmlQuery, pageQuery } from "../api/queries";

/**
 * Transport encoding for the ID half of every id-addressed read. `root` has always been
 * `encodeURIComponent`'d and the id, on the same line, was not - so an id spliced raw could carry path
 * syntax of its own into `fetch`. It is not a hypothetical source: an id arrives from AUTHORED markdown
 * (a `/p/...` href the Shell intercepts and lib/permalink.ts parses), and a segment with no `/` in it
 * survives that parse whole.
 *
 * The four rows below drive the real `queryFn`s rather than asserting on {@link pageEndpoint} alone,
 * because the defect was never in a helper - it was four call sites that did not use one.
 */

/** A page id shaped like the traversal an author can put in a link: no `/`, so the permalink parse keeps it. */
const HOSTILE = "..\\..\\..\\api\\v1\\admin\\tokens";
const SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

/** Runs a query's own `queryFn` (they take no arguments) and returns the url it fetched. */
async function urlOf(options: { queryFn?: unknown }): Promise<string> {
  const calls: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      calls.push(String(input));
      return new Response("{}", { status: 200, headers: { "content-type": "application/json" } });
    }),
  );
  await (options.queryFn as () => Promise<unknown>)();
  return calls[0];
}

afterEach(() => vi.unstubAllGlobals());

describe("id-addressed request urls", () => {
  it("encodes the id at every sink, so an authored id cannot choose the endpoint", async () => {
    const encoded = encodeURIComponent(HOSTILE);
    expect(await urlOf(pageQuery(HOSTILE, "extra"))).toBe(`/api/v1/pages/${encoded}?root=extra`);
    expect(await urlOf(pageHtmlQuery(HOSTILE, "extra"))).toBe(`/api/v1/pages/${encoded}/html?root=extra`);
    expect(await urlOf(historyQuery(HOSTILE, "extra"))).toBe(`/api/v1/pages/${encoded}/history?root=extra`);
    expect(await urlOf(diffQuery(HOSTILE, "extra", SHA_A, SHA_B))).toBe(
      `/api/v1/pages/${encoded}/diff?from=${SHA_A}&to=${SHA_B}&root=extra`,
    );
    // The encoding is what keeps the request ON the endpoint it named: raw, WHATWG resolves the
    // backslashes into a path of the author's choosing.
    expect(new URL(`/api/v1/pages/${HOSTILE}`, "http://x").pathname).toBe("/api/v1/admin/tokens");
    expect(new URL(pageEndpoint(HOSTILE), "http://x").pathname).toBe(`/api/v1/pages/${encoded}`);
  });

  it("leaves a canonical page id byte-identical - encoding is transport, not a reshape", async () => {
    const id = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
    expect(pageEndpoint(id)).toBe(`/api/v1/pages/${id}`);
    expect(await urlOf(pageQuery(id, null))).toBe(`/api/v1/pages/${id}`);
  });
});
