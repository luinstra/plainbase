# UI Identity Revamp — deferred work & follow-ups

The UI Identity Revamp (5 chunks) enacted the design handoff in `docs/design/handoff/` (`README.md`
is the spec; `reference/plainbase.css` the token sheet; `reference/*.html` the hi-fi prototypes).
This file records what was **intentionally deferred** — the items the handoff or the build flagged as
forward-looking — so they aren't lost in commit messages or the gitignored `.crew/` chunk addenda.
Each is a real backlog item with what's needed to unblock it.

A second pass, [design handoff v2](handoff-v2/README.md), amends v1 (wayfinding, state, contrast,
light-mode surfaces). Its own deferrals are listed at the end of this file.

## Forward-looking (needs a capability we don't have yet)

- **Editor** (handoff §7): **shipped**. CodeMirror 6 source, a preview overlay, a structured
  properties form and CAS save. v2 replaces the v1 Split view with a Write / Preview switch and moves
  properties into chips; see handoff v2 §7.

- **"Edit this page" footer affordance** (Chunk 4) — omitted, not faked. Needs **both** a write
  backend **and** a repo-base-URL config to build an honest link target (`{repoBaseUrl}/{page.path}`).

- **Page `last_modified` (Git)** — `TreePage.updated` currently carries only the *editorial*
  frontmatter date (see ADR-0007). The authoritative Git/filesystem last-modified arrives in Phase 3
  as a **distinct additive field** `last_modified` — never a repoint of `updated`.

- **Freeze the provisional tree fields** — `TreeFolder.description` / `TreeFolder.page_count` /
  `TreePage.updated` are marked *provisional pending Phase 3* (ADR-0007). Once the Git layer's needs
  are known, drop the provisional annotation and freeze the shape.

## Designed-but-deferred (buildable now, just out of this phase's scope)

- **prev/next pager** (Chunk 4 doc footer) — deferred. Needs **sibling derivation from the tree**:
  there's no `siblingsOf` helper in `lib/tree.ts`, and `PageContent` doesn't fetch the tree. Self
  contained frontend feature with its own unit-test surface.

- **Empty / loading / 404 states** (handoff §"Interactions & states") — explicitly *not yet designed*
  in the handoff ("flag for a follow-up pass"). Needs a design pass before implementation.

## Intentional non-features (recorded so they aren't re-proposed)

- **Subfolder count on the wire** — intentionally NOT a field. The SPA can derive it from the
  `children` array already on the tree response (`children.filter(c => c.type === 'folder').length`),
  with no drift, if a card label ever needs it. (Page count *is* wired because future draft/visibility
  filtering changes it server-side — see ADR-0007.)

- **Byline metadata mode** (handoff §3 alternate) — cut. It was a rejected exploration "Tweaks" knob,
  and no `plainbase.yaml` placement field exists; the metadata **Rail** is the fixed default. Would
  need a real config surface to ever ship.

- **Dark "deep"/"medium" depth + the in-prototype Tweaks panel** — the handoff's exploration knobs;
  the locked defaults (teal accent, warm-paper neutrals, dark "soft" depth) shipped. Alternate dark
  depths were rejected; if ever exposed, gate on `[data-theme="dark"][data-dark="deep"|"medium"]`.

## Related records

- ADR-0003 — folder landing pages.
- ADR-0007 — tree-node metadata: editorial dates now, provisional fields, Git last-modified distinct.
- The per-chunk executor addenda (`.crew/plans/chunk-{1..5}-*-addendum.md`) carry the full build-time
  detail; they are gitignored and local-only.

## Handoff v2 deferrals

- **Mobile layout**: out of scope for v2. Below tablet width the sidebar is hidden with no menu
  replacement, the outline is hidden, and one 471 px three-column table widens the page to 487 px on
  a 375 px screen. Needs a drawer, an outline sheet and scroll frames for wide tables.
- **Page summaries on folder landings**: the v2 landing shows one-line page summaries, but tree pages
  carry no summary field. Needs a provisional field (frontmatter description, else the first sentence)
  on the tree response, under ADR-0007.
