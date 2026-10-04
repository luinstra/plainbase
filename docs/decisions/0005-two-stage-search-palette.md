# 5. Search palette: instant titles and automatic content matches

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** luinstra (after a four-model design debate: Opus, Codex/gpt-5.5, Gemini 2.5 Pro, Sonnet 4.6)
- **Context:** Phase 2 (Search) chunk S7 — the SPA search surface (Cmd/Ctrl+K palette). Settles the
  interaction model **before** the build loop. Supersedes the AUTO-BLEND model that the
  measure-twice S7 addendum was originally built around; the addendum
  (`.crew/plans/tighten-phase-2-chunk-s7-spa-search-ui-quick-switc.md`) is amended to match this ADR.
  Touches no frozen surface: PB-SEARCH-1 is unchanged, this is UI behavior only (§A4 — the
  quick-switcher and palette UX are explicitly "freely evolvable, no contract surface").

## Amendment: 2026-10-03

The owner-approved UI handoff replaces the historical two-stage interaction below. The current palette keeps cached title/path matches instant and appends server full-text matches automatically after a 150 ms debounce. The bridge and Back step are removed; Escape closes the palette in one step.

Quick matches remain capped at eight and precede content matches. Each section groups its results by configured space order; results retain fuzzy or server rank within a space. Scope chips select All spaces or one configured available space. A root-scoped request includes optional `root` on `GET /api/v1/search`; the engine applies that bound root predicate to both hits and total before pagination. Omitted root retains the existing global behavior and response shape. Invalid/repeated root grammar returns `invalid_root`; unregistered scope returns that error after the read authorization gate; a configured unavailable scope returns `root_unavailable`. The MCP search schema stays unchanged.

Selection is tracked by root-qualified result identity, including result kind and content heading. Arriving results cannot replace the selected destination by shifting array indices. Query or scope changes clear selection, and content hits for the previous query are immediately hidden during the next debounce. Enter opens the selected row, or the first current result when no selection was made; with no result it does nothing. Status and group labels are never selectable. Tab cycles the input and scope buttons; arrows navigate results only from the input. Opening the palette reads the existing tree cache without starting a tree request.

This amendment intentionally accepts the richer combined list requested by the owner, with the identity and async-state safeguards the original debate identified. Title navigation remains usable when full-text search fails. Server snippets, highlight offsets, canonical URLs and rooted permalink fallbacks remain authoritative. No schema, dependency or index-format change is required.

## Historical decision (2026-06-13)

The context, decision and consequences below record the original reasoning. They are superseded by the amendment above where they describe stages or the bridge.

## Context

S7 puts two search capabilities behind one Cmd/Ctrl+K surface:

1. **Quick-switcher** — instant, client-side fuzzy match over page titles/paths from the
   already-cached `/api/v1/tree` (zero network), for known-item navigation ("take me to the Deploy
   Guide"). This is the ~90% case for a docs product.
2. **Full-text** — server search via `GET /api/v1/search`, 150 ms debounced, for content discovery
   ("where do we mention kubectl?"). The fallback case.

The original design (owner-chosen in measure-twice) was **AUTO-BLEND**: one overlay showing both a
"Jump to" section (instant) and a "Search results" section (debounced) simultaneously, with a single
keyboard cursor roaming across the concatenated list.

A four-model design debate flagged the blended model as the weakest part of the spec — **unanimously
on the same root cause**, 3-of-4 recommending a fix-in-place and 1 (Gemini) recommending a reshape.
The root cause: a single selection cursor crossing two sections that have **different latencies**
(instant vs debounced — async results arrive and grow the list under the cursor) **and different
action semantics** (navigate-to-known-page vs open-a-content-hit — so "Enter" means different things
depending on where the cursor happens to sit). Everything else the panel raised about the palette
(default-selection ambiguity, "cap Jump-to so it doesn't bury search", identity-tracked selection to
survive list reflow) were all symptoms of that one root.

## Decision

**The palette is two-stage. Only one homogeneous result list is visible at a time.**

- **Stage 1 (default on open):** the quick-switcher. Typing filters page titles/paths via the
  client-side fuzzy scorer; arrow/Enter navigate to the selected page (`node.url ?? '/p/{root}/{id}'`,
  reusing `lib/tree.ts pageHref` verbatim). Zero network.
- **The bridge:** a stable, always-present bottom row — `Search all docs for "{query}" ↵` — is the
  only affordance that crosses into full-text. It does not move or reflow as the user types.
- **Stage 2 (on activating the bridge):** the view transitions to **full-text results only**
  (`GET /api/v1/search`). Selection and Enter are now unambiguous because the list is homogeneous —
  every item is a content hit, Enter always opens it (`hit.url ?? '/p/{root}/{page_id}'` + `#heading_id`
  when present). Esc/Backspace-at-empty returns to Stage 1.

Because exactly one list is ever navigable, the selection cursor is always within a single
homogeneous, stable-ordered list: no cross-section index arithmetic, no identity-tracking to survive
async reflow, no default-selection ambiguity, no "Enter does different things", no need to cap
quick-switcher density so it doesn't bury search. The entire class of complexity the panel flagged
is **eliminated rather than managed**.

This also fits the docs usage profile: the common case (jump to a known page) stays instant and
uncluttered; full-text is a deliberate, explicit second step rather than ambient noise streaming in
beneath every keystroke.

## Consequences

**Positive**

- The flagged hazard is gone by construction — selection is always over one homogeneous list.
- Less code and less to get subtly wrong, which lets the S7 risk budget go to the part the panel
  considered genuinely hard: hand-rolled combobox a11y correctness (focus-trap, `aria-activedescendant`
  with real DOM ids, scroll-lock, focus-return).
- Matches mature known-item-first palettes (VS Code `Cmd-P`); the common docs case is optimized.

**Trade-offs**

- Full-text costs **one extra action** (activate the bridge row) versus appearing ambiently. For a
  product where full-text is the fallback, not the primary, this is an accepted cost.
- The "results appear as you type" feel the owner originally liked in AUTO-BLEND is not in Stage 1.
  Recorded as the reason to revisit (below) if usage shows full-text is more central than assumed.

**Reversibility — high (UI-only, no contract surface).** If we later want the richer ambient model,
two roads were explicitly considered and are preserved here for a clean revisit:

- **Option B — Constrained auto-blend:** keep both sections visible, but the cursor does *not* roam
  across both — arrows stay in quick-switcher, **Enter with no deliberate selection runs full-text**,
  full-text hits are click/explicit-focus. Recovers most of the ambient feel without the cross-section
  cursor hazard. This is the recommended path *if* the ambient feel proves wanted.
- **Option C — Auto-blend + identity-tracked selection:** the original blended list, made correct by
  keying selection on a stable item identity (`jump:{id}` / `search:{id}:{heading_id}`), resetting to
  first-visible only on query change, with an explicit default-selection/Enter rule and a Jump-to row
  cap. The 3-of-4 panel fix-in-place. Most work of the three; only worth it if the simultaneous
  two-section view is specifically desired over Option B.

Switching to either is a contained frontend change with no server, contract, or data consequence —
which is why shipping the simpler two-stage model first carries no lock-in cost.

Debate synthesis archived at
`~/.claude-octopus/debates/<session>/s7-design/synthesis.md` (verdicts 3–1 SOUND-WITH-CHANGES; the
splitter-clamp, combobox-a11y, and `scrollRestoration` findings are folded into the S7 addendum
independently of this interaction-model decision).
