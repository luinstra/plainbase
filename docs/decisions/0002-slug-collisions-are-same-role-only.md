# 2. Same-parent slug collisions are same-role only (page-vs-page, folder-vs-folder)

- **Status:** Accepted
- **Date:** 2026-06-11
- **Deciders:** luinstra
- **Context:** Phase 1, chunk 5 (canonical URLs) — `CanonicalUrlBuilder`
  (`server/src/main/kotlin/com/plainbase/domain/service/CanonicalUrlBuilder.kt`), §A4 of the
  Phase 1 plan ("Canonical URL construction", frozen — owner decision log #7).

## Context

§A4 says: "Same-parent slug collisions (two siblings producing the same segment): deterministic
winner = the entry whose raw on-disk name bytes sort first … the loser is excluded from path
space." Read literally, "two siblings" admits a PAGE and a FOLDER contesting one segment —
`setup.md` next to `setup/` — and the first implementation grouped both roles into one
`(parent, segment)` collision space, so one of them lost its URL.

But the stated rationale for exclusion is *"the path is contested"* — and a page and a Phase-1
folder never contest a path. A folder has no URL of its own in Phase 1 (`index.md` is an ordinary
page, no directory-URL special case — §A4); the page claims `/docs/.../setup` while the folder's
descendants claim `/docs/.../setup/...` — strictly longer paths. Nothing collides. Excluding the
page is an over-exclusion that breaks the common overview-page-next-to-detail-folder layout
(`setup.md` as the overview, `setup/` holding the detail pages).

Full-URL-path uniqueness survives the narrower reading: two pages with equal full URL paths
either share a parent (a page-page collision) or their ancestor chains pass through sibling
folders with an equal segment (a folder-folder collision); a page never equals a path under its
same-named sibling folder, because those are strictly longer.

## Decision

**Collision scope is same-role only.** Within one parent:

- two sibling PAGES whose terminal slugs are equal collide — raw-unsigned-byte winner owns the
  URL, every loser gets `url = null` plus a `path_slug_collision` issue (reachable via `/p/{id}`);
- two sibling FOLDERS whose directory segments are equal collide — same tie-break; a losing
  folder drops its whole subtree from path space;
- a PAGE and a FOLDER sharing a slug do **not** collide — both resolve normally
  (`setup.md` → `/docs/.../setup`; `setup/intro.md` → `/docs/.../setup/intro`) and no issue is
  raised.

The raw-unsigned-byte tie-break (`RawByteOrder`, the chunk-1 B3 rule) is unchanged within a role.

This is an interpretation of a frozen surface (§A4's collision clause), decided at implementation
time: the clause's *rationale* (a contested path) is taken over its *literal sibling wording*
where the two diverge.

## Consequences

**Positive**

- The overview-page-next-to-detail-folder layout — common in real docs trees — works: both the
  page and the folder's children keep their URLs, with no spurious `path_slug_collision`.
- Fewer pages fall out of path space; `/p/{id}` permalinks remain a fallback, not a routine.

**Reversibility (one safe direction only)**

- §A4 exclusions follow the asymmetric-freeze direction of travel: URLs that resolve today must
  keep resolving. Same-role-only is the *permissive* reading — revisiting it toward the literal
  reading would start 404-ing page URLs that resolve under this decision, so it is reversible
  only in the already-permissive direction (i.e. effectively settled).
- If a folder URL ever exists (a Phase-2+ directory page), page-vs-folder would become a genuine
  contest at that point and would need its own decision — this record does not pre-decide it.
