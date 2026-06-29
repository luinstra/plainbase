import { describe, expect, it } from "vitest";
import { approxSlug, previewPath } from "./slugPreview";

/**
 * Representative cases for the ADVISORY client slug approximation — NOT an exhaustive Unicode oracle (that
 * is the server's `HeadingSlugger` job; this is a hint, not a contract). Covers the everyday ASCII/Latin
 * shapes a doc title hits, plus the empty→fallback and accented-letter cases.
 */
describe("approxSlug (advisory; ≈ HeadingSlugger.slugify)", () => {
  it("lowercases and folds spaces to hyphens", () => {
    expect(approxSlug("My New Page")).toBe("my-new-page");
  });

  it("drops punctuation and folds the space", () => {
    expect(approxSlug("Hello, World!")).toBe("hello-world");
  });

  it("falls back to 'page' when the result is empty", () => {
    expect(approxSlug("")).toBe("page");
    expect(approxSlug("###")).toBe("page");
  });

  it("respects a custom fallback", () => {
    expect(approxSlug("###", "untitled")).toBe("untitled");
  });

  it("keeps accented letters (Letter + Mark categories, NFC)", () => {
    expect(approxSlug("café")).toBe("café");
  });
});

describe("previewPath (advisory path shape)", () => {
  it("joins a folder and the slugified stem", () => {
    expect(previewPath("guides", "My Page")).toBe("guides/my-page.md");
  });

  it("drops the folder segment at the root", () => {
    expect(previewPath("", "My Page")).toBe("my-page.md");
  });

  it("trims a trailing slash so the folder join never doubles up", () => {
    expect(previewPath("guides/", "My Page")).toBe("guides/my-page.md");
  });
});
