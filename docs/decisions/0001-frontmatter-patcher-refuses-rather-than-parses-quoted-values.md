# 1. The frontmatter patcher refuses ambiguous YAML rather than parsing it

- **Status:** Accepted
- **Date:** 2026-06-11
- **Deciders:** luinstra
- **Context:** Phase 1, chunk 4a (page identity) — PB-PATCH-1 (`server/src/main/kotlin/com/plainbase/domain/service/FrontmatterPatcher.kt`), §A3 of the Phase 1 plan.

## Context

PB-PATCH-1 is the only code path that ever writes frontmatter programmatically. Its frozen
guarantee is a pure single-point byte insertion — `output = input[0..k) + insertion + input[k..n)`
— with no re-encoding, EOL normalization, quoting change, BOM change, or YAML reserialization.

To decide *whether* a block is safe to splice an `id:` line into, the patcher uses a hand-rolled
**strictest-subset** recognizer rather than a YAML library, for three reasons established during
the chunk design:

1. **Byte fidelity.** A YAML library parses to a DOM and re-serializes, mutating formatting,
   comments, and key order — the opposite of the frozen contract. A library could at most classify
   the read side, never write the result.
2. **No two-model drift.** Classifying with a parser while writing with byte offsets introduces a
   second YAML model that must agree with our offsets forever; any disagreement is a new corruption
   class. §A3 deliberately shares one grammar between detector and patcher.
3. **Native gate + supply chain.** SnakeYAML is reflection-heavy (a GraalVM closed-world hazard) and
   ingests untrusted documents in `adopt` mode, where its CVE history becomes our attack surface.

The hand-rolled recognizer was hardened with a **test-scope differential-oracle fuzz test**
(`FrontmatterPatcherOracleTest`, SnakeYAML 2.4 as a `testImplementation`-only reference — never on
`runtimeClasspath` and never in the native image) running boundary-biased iterations (currently
6000) at a pinned seed. The
oracle found five false-accepts that three model-review passes missed, most notably `k: v: w`
(a value carrying a second mapping colon — YAML's "mapping values are not allowed here" error).

Closing the `k: v: w` hole originally produced a **narrow value-side rule** (the value after a
mapping colon may carry no further whitespace-terminated colon), with the deliberate side effect of
refusing quoted values containing a colon-space:

```yaml
title: "Chapter 1: Intro"      # REFUSED — the ": " inside the quotes reads as a second mapping colon
```

This is a *false refusal*, not corruption: a real parser accepts it. Because the patcher's
acceptance invariant is one-directional (accept ⇒ the parser agrees it is a mapping; conservative
refusal is always allowed), the oracle stays green while we refuse it. Quoted titles containing a
colon are a common real-world frontmatter pattern.

A third review pass then found three more false-accept classes the narrow rule (and the oracle's
then-vocabulary) missed: values that *open* a non-plain node (`title: "unterminated`, `tags: [a, b`,
`r: *a`, `s: |` — a parser rejects or structurally reinterprets these), sequence items that do the
same (`- [unterminated`, `- "x`, `- *missing`), and Unicode-whitespace-only lines dropped as blank
by a Unicode-aware `isBlank` (a U+2000-only line is content a parser chokes on). Implementation
probing confirmed two sibling classes: value-position block-sequence openers (`k: - item`, a lone
`k: -` — "sequence entries are not allowed here") and YAML 1.1's extra line-break characters
(NEL/LS/PS), which a real parser breaks lines at while the recognizer's CR/LF line model does not.
The oracle generator's vocabulary was widened so every one of these classes is fuzzed permanently.

## Decision

**Refuse ambiguous-but-valid YAML rather than grow a quote/escape tokenizer — and require PLAIN
scalars everywhere a value can appear.** This supersedes the narrower "no whitespace-terminated
colon in the value" framing. The patcher accepts a block only when:

- every top-level value is a **plain scalar** or absent (a value-less key may open a nested block).
  The first value character must be plain-legal per YAML's `ns-plain-first`: no indicator, except
  `-` `?` `:` immediately followed by a non-space (`order: -1` and `time: 14:30:00` stay accepted;
  `k: - item` refuses). ALL quoted, flow, anchor, alias, tag, and block-scalar values refuse —
  well-formed (`k: "quoted"`, `tags: [a, b]`) and unterminated (`title: "unterminated`) alike;
- every sequence item is a **plain scalar**, a bare `-` (a null item), or a single simple
  `key: plainvalue` pair (`- alpha`, `- key: v` accept; `- [x`, `- "x`, `- *a`, `- !x` refuse);
- a line is blank only when **empty or all ASCII space/tab** — a Unicode-whitespace-only line is
  content and refuses; and the block contains none of YAML 1.1's extra break chars (NEL/LS/PS).

There is deliberately no tokenizer for quote termination or flow nesting, so the patcher does not
distinguish a well-formed quoted/flow value from a broken one — the whole non-plain class refuses.
Properly accepting quoted or flow values would require hand-rolling quote-state and escape
tokenization — precisely the YAML-tokenizer fragility the strict-subset approach exists to avoid,
and the source of the repeated corruption holes found in review.

## Consequences

**Positive**
- The recognizer stays small, auditable, frozen, and native-safe; no second YAML model ships.
- The refusal is in the safe direction under the §A3 **asymmetric freeze**: acceptances are frozen
  forever, but refusals may later be relaxed to acceptances by a documented revision — never the
  reverse. We can loosen this with data; we could never tighten it after freeze.
- No corruption risk: a refused page is never mutated.

**Negative / accepted cost**
- A document whose frontmatter uses ANY quoted, flow, anchor/alias, tagged, or block-scalar value
  (e.g. a quoted title, an inline `[a, b]` tag list) will **not** get an `id:` written into its
  frontmatter by the patcher — even when that value is perfectly valid YAML.

**Mitigation (why the cost is tolerable)**
- Such a page still resolves a **stable `/p/{id}` URL via `id_map`** — it simply isn't *materialized*
  into the file's frontmatter. Identity is preserved; only in-file id-stamping is skipped.
- `adopt --dry-run` measures the real refusal rate against actual corpora. If quoted or flow values
  prove common enough to matter, the asymmetric freeze lets us relax the rule in a later documented
  revision without breaking any frozen acceptance.

## Known-refused inputs (for reference)

Refused (safe, relaxable later): `k: v: w`, `title: "Chapter 1: Intro"`, all other quoted values
(`k: "quoted"`, `title: "unterminated`), flow values terminated or not (`tags: [a, b]`,
`tags: [a, b`, `m: {a: 1}`), anchor/alias/tag/block-scalar values (`r: &a x`, `r: *a`, `t: !!str x`,
`s: |`, `s: >`), value-position sequence openers (`k: - item`, `k: -`), non-plain sequence items
(`- [unterminated`, `- "x`, `- *missing`, `- !x`), tab-indented lines, Unicode-whitespace-led
scalars, Unicode-whitespace-ONLY lines (a U+2000-only line is content, not blank), any NEL/LS/PS
(U+0085/U+2028/U+2029) in the block, inline-comment scalars (`just text # why: because`), nested
blocks under a valued key, mixed-indent / mixed-shape continuation runs.

Still accepted (frozen): `key: value`, `key:` (null value), `two words: v`, `order: -1`,
`count: -5`, `time: 14:30:00`, `url: https://x`, `key: value # comment`, and a value-less key
opening a single-shape, single-indent nested block of plain-scalar or simple-pair items
(`tags:` + `  - alpha`, `  - key: v`, a bare `  -` null item).
