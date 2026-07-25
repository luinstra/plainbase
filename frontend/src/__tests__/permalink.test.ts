import { describe, expect, it } from "vitest";
import { parsePermalink, permalinkOf } from "../lib/permalink";

/**
 * The client half of the `/p/` contract, pinned against the server's own dispatch ORDER
 * (PermalinkRoute: a leading id-shaped segment is BARE with the rest decorative, otherwise
 * root-then-canonical-id is ROOTED). The whole ParsedPermalink is asserted, `prefix` included:
 * `prefix` has exactly one consumer (PermalinkPage's canonical-replace guard) and this table is its
 * only guard.
 *
 * The two id gates are deliberately DIFFERENT strengths, mirroring the two the server has: the first
 * segment is tested leniently (`PageId.of`, so the 32-char hex form counts as an id), the second
 * strictly (canonical 36-char only). It takes BOTH directions of the 32-hex form to pin that split -
 * `hex32/uuid` (case i) for the lenient first gate, `root/hex32` (case n) for the strict second one.
 * Case i alone leaves the second gate free to be loosened to the first gate's regex with the table green.
 */
const U1 = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";
const U2 = "0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d99";
const U1_UPPER = U1.toUpperCase();
const U1_HEX32 = U1.replaceAll("-", "");

describe("parsePermalink", () => {
  it("a: a bare canonical id is the BARE arm (pin: pre-C6 behaviour)", () => {
    expect(parsePermalink(U1)).toEqual({ root: null, id: U1, prefix: `/p/${U1}` });
  });

  it("b: a trailing stale slug after a bare id is decorative, and out of the prefix", () => {
    expect(parsePermalink(`${U1}/stale-slug`)).toEqual({ root: null, id: U1, prefix: `/p/${U1}` });
  });

  it("c: an id-shaped FIRST segment wins over a canonical second one (the server tries bare first)", () => {
    expect(parsePermalink(`${U1}/${U2}`)).toEqual({ root: null, id: U1, prefix: `/p/${U1}` });
  });

  it("d: root + canonical id is the ROOTED arm", () => {
    expect(parsePermalink(`extra/${U1}`)).toEqual({ root: "extra", id: U1, prefix: `/p/extra/${U1}` });
  });

  it("e: a trailing stale slug after a ROOTED id is decorative too", () => {
    expect(parsePermalink(`extra/${U1}/stale-slug`)).toEqual({ root: "extra", id: U1, prefix: `/p/extra/${U1}` });
  });

  it("f: the second-segment gate is case-insensitive", () => {
    expect(parsePermalink(`extra/${U1_UPPER}`)).toEqual({ root: "extra", id: U1_UPPER, prefix: `/p/extra/${U1_UPPER}` });
  });

  it("g: a non-id second segment leaves the first segment as the id, not as a root", () => {
    expect(parsePermalink("extra/not-a-uuid")).toEqual({ root: null, id: "extra", prefix: "/p/extra" });
  });

  it("h: an unrecognised splat is a bare id, so the API gets to answer 400 (pin: never reject here)", () => {
    expect(parsePermalink("not-a-uuid")).toEqual({ root: null, id: "not-a-uuid", prefix: "/p/not-a-uuid" });
  });

  it("i: a 32-hex first segment is a BARE id, because the server's bare arm accepts that form", () => {
    expect(parsePermalink(`${U1_HEX32}/${U2}`)).toEqual({ root: null, id: U1_HEX32, prefix: `/p/${U1_HEX32}` });
  });

  it("n: a 32-hex SECOND segment is NOT a rooted id - the rooted arm's gate is the strict canonical one", () => {
    // The other half of case i, and the only row that can catch the second gate being loosened to the
    // first's: the server's rooted arm gates segment two with `canonicalPageId` (36-char hyphenated only),
    // so `/p/extra/{32-hex}` is not rooted there and must not be rooted here. It falls to the bare arm and
    // the by-id read answers the same 400 `invalid_page_id` the dispatcher would.
    expect(parsePermalink(`extra/${U1_HEX32}`)).toEqual({ root: null, id: "extra", prefix: "/p/extra" });
  });

  /**
   * The STRUCTURAL gate, mirroring PermalinkRoute's `dropLastWhile { it.isEmpty() }` + `any { it.isEmpty() }`:
   * contiguous TRAILING empty segments are decorative, a LEADING or INTERIOR one is malformed. Without it the
   * same address renders on an SPA click and 400s on reload or when shared - a success-vs-reject split decided
   * by how the reader arrived. The malformed splat rides through WHOLE as the id so the API answers the server's
   * own 400 `invalid_page_id`, rather than the client picking a segment and blaming a root or a page of its own.
   */
  it("j: an INTERIOR empty segment is malformed, never a rooted parse", () => {
    expect(parsePermalink(`extra/${U1}//stale`)).toEqual({ root: null, id: `extra/${U1}//stale`, prefix: `/p/extra/${U1}//stale` });
  });

  it("k: an interior empty segment is malformed on the BARE arm too (the server gate runs before either arm)", () => {
    expect(parsePermalink(`${U1}//stale`)).toEqual({ root: null, id: `${U1}//stale`, prefix: `/p/${U1}//stale` });
  });

  it("l: a LEADING empty segment is malformed, and must never become an empty `?root=`", () => {
    // The pre-gate parse read this as ROOTED with `root: ""`, so the read sent `?root=` and got 400
    // `invalid_root` where the server's own dispatcher says `invalid_page_id`.
    expect(parsePermalink(`/${U1}`)).toEqual({ root: null, id: `/${U1}`, prefix: `/p//${U1}` });
  });

  it("m: contiguous TRAILING empty segments stay decorative (the half a blanket empty-segment gate would break)", () => {
    expect(parsePermalink(`extra/${U1}//`)).toEqual({ root: "extra", id: U1, prefix: `/p/extra/${U1}` });
    expect(parsePermalink(`${U1}/`)).toEqual({ root: null, id: U1, prefix: `/p/${U1}` });
  });
});

describe("permalinkOf", () => {
  it("emits the rooted form the server emits", () => {
    expect(permalinkOf("extra", U1)).toBe(`/p/extra/${U1}`);
  });
});
