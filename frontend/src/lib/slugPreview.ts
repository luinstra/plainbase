/**
 * ADVISORY slug/path preview for the `/new` form — NEVER used for navigation (the server-returned
 * `result.created.url` is the sole navigation truth). Pure — no React, no DOM (mirrors lib/unifiedDiff.ts).
 *
 * The REAL slug authority is the server's `HeadingSlugger.slugify`
 * (`server …/domain/render/HeadingSlugger.kt:44-55`). A browser cannot replicate it bit-for-bit; this is a
 * deliberate APPROXIMATION whose only job is to help the author guess the filename. Known divergences:
 *  - **Whitespace set:** the server folds a data-pinned 25-element `White_Space=Yes` set (one `-` per code
 *    point, no run-collapsing); JS `\p{White_Space}` tracks the engine's Unicode version, which can drift
 *    from the JDK tables the server's set was frozen against (e.g. U+001C–U+001F: excluded by the server,
 *    matched by some JDK predicates the spec rejects) — so it can disagree at the Unicode fringe.
 *  - **Keep-set:** the server keeps JDK `Character.getType` Letter (Lu/Ll/Lt/Lm/Lo) + Mark (Mn/Mc/Me) +
 *    Decimal Number (Nd) + `-`/`_`. JS `\p{L}\p{M}\p{Nd}` approximates but is not provably equal across
 *    Unicode versions.
 *  - **NFC + lowercase:** order matches (lowercase BEFORE NFC, final NFC), but the JS lowercase/NFC tables
 *    can drift by a Unicode version vs. the JDK. On realistic ASCII/Latin doc titles they agree.
 */

/**
 * Approximates `HeadingSlugger.slugify` (HeadingSlugger.kt:44-55) in step order. ADVISORY ONLY — never feed
 * the result to navigation; the server is the slug authority. `fallback` mirrors `PAGE_FALLBACK` ("page").
 */
export function approxSlug(text: string, fallback = "page"): string {
  const slug = text
    .toLowerCase() // server lowercases BEFORE NFC (HeadingSlugger.kt:46)
    .normalize("NFC")
    .replace(/\p{White_Space}/gu, "-") // each whitespace code point → ONE hyphen, no run-collapsing (:48); ≈ the data-pinned 25-set (:72)
    .replace(/[^\p{L}\p{M}\p{Nd}_-]/gu, ""); // keep Letter/Mark/Decimal-Number/_/- ; drop the rest (:84-100)
  return (slug || fallback).normalize("NFC"); // empty → fallback; final NFC (:54)
}

/**
 * The advisory file path the create is likely to land at: `<folder>/<stem>.md`, or `<stem>.md` at the root.
 * The folder is shown AS-TYPED (its segments are also server-slugified, but previewing it verbatim is the
 * honest advisory minimum — the server url governs). ADVISORY ONLY (see `approxSlug`).
 */
export function previewPath(folder: string, slugOrTitle: string): string {
  const stem = approxSlug(slugOrTitle);
  return folder ? `${folder}/${stem}.md` : `${stem}.md`;
}
