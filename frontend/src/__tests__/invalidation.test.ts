import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { historyQuery, invalidateAfterWrite, pageHtmlQuery, pageQuery } from "../api/queries";

/**
 * A write clears EVERY root spelling of the written page's id-keyed cache, and nothing else's.
 *
 * The same page can be cached under its own root AND, via a bare permalink read, under a null root, so
 * invalidation goes by the id PREFIX rather than by a rooted key - which is the whole reason the root
 * sits AFTER the id in every id-keyed query. Both failure directions matter and both have a witness
 * here: too NARROW (a rooted key stops matching the bare-keyed copy, leaving a permalink reader stale
 * forever) and too WIDE (a bare namespace clears every OTHER page too).
 */
const DUP_ID = "0197c2d1-9f3b-7a4e-8d6c-1b5a7e9c3f21";
/** An unrelated page, differing in the FIRST hex group so no prefix bug can collide the two by luck. */
const OTHER_ID = "0197d3e2-4a1c-7b58-9e2f-6c8d0a3b5f14";

/** Each helper's key carries a DataTag binding it to that helper's RESPONSE type, and this row cares
 *  only that an entry EXISTS under the key, not what is in it. Spreading drops the brand so one marker
 *  value can seed all nine. */
const untagged = (key: readonly unknown[]): unknown[] => [...key];

describe("invalidateAfterWrite", () => {
  it("invalidates all three id-keyed legs in EVERY root spelling, and no other page's", () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const dup = [
      untagged(pageQuery(DUP_ID, "main").queryKey),
      untagged(pageQuery(DUP_ID, null).queryKey),
      untagged(pageHtmlQuery(DUP_ID, "main").queryKey),
      untagged(pageHtmlQuery(DUP_ID, null).queryKey),
      untagged(historyQuery(DUP_ID, "main").queryKey),
      untagged(historyQuery(DUP_ID, null).queryKey),
    ];
    // Id-keyed, deliberately NOT by-path: invalidateAfterWrite clears the whole by-path namespace by
    // design, so a by-path witness would red under correct code.
    const other = [
      untagged(pageQuery(OTHER_ID, "main").queryKey),
      untagged(pageHtmlQuery(OTHER_ID, "main").queryKey),
      untagged(historyQuery(OTHER_ID, "main").queryKey),
    ];
    for (const key of [...dup, ...other]) qc.setQueryData(key, { seeded: true });

    invalidateAfterWrite(qc, { id: DUP_ID, url: "/docs/main/permalink/hub" });

    for (const key of dup) expect(qc.getQueryState(key)?.isInvalidated, JSON.stringify(key)).toBe(true);
    for (const key of other) expect(qc.getQueryState(key)?.isInvalidated, JSON.stringify(key)).toBe(false);
  });
});
