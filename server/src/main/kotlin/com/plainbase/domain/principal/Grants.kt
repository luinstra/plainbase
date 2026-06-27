package com.plainbase.domain.principal

/**
 * Unforgeable typed authorization grants (A3, the compile-time floor). A mutating domain operation
 * ([com.plainbase.domain.service.WritePipeline.write]/`create`, `ContentStore.writeAssetExclusive`, the gated
 * `IndexBuilder.rebuild(grant)`, the P1a proposal status transition `ProposalService.proposeEdit`/`proposeCreate`/
 * `reject`) REQUIRES one of these as a leading parameter — so a bypassed
 * `PolicyService.check()` is a COMPILE error even if someone injects the raw mutator. The grants carry NO payload:
 * they exist purely to make "I called check()" a value the mutator can demand (a plain `class`, never a `data`
 * class — `copy()` would re-open forgery).
 *
 * UNFORGEABILITY (the threat model the debate ratified):
 *  - The constructors are `internal`, so NO module outside `:server` can construct a grant — the COMPILER is the
 *    proof against a future third-party-authored MCP module.
 *  - WITHIN `:server`, the only PRODUCTION mint site is [com.plainbase.domain.service.PolicyService]; the
 *    [grantForTests]/[createGrantForTests]/[manageGrantForTests] factories below are PUBLIC (so both `src/test`
 *    AND `src/nativeTest` can mint — `src/nativeTest` has no friend-path to `main`/`src/test`, so an `internal`
 *    test factory would be unreachable there and the native gate would not compile). A source-scan
 *    (`ChokePointArchitectureTest`/`GrantUnforgeabilityTest`) forbids any PRODUCTION reference to the
 *    constructors OR the `*ForTests` factories outside `PolicyService` — that scan IS the in-`:server` guarantee.
 */
class EditGrant internal constructor()

class CreateGrant internal constructor()

class ManageGrant internal constructor()

/** Gates the P1a proposal STATUS TRANSITION (approve/reject) — NOT a content-tree write (that is the EditGrant). */
class ApproveGrant internal constructor()

/**
 * TEST-ONLY grant mint, PUBLIC in `src/main` so `src/test` and `src/nativeTest` can both mint via `main`'s output
 * (the only seam `src/nativeTest` has). NEVER referenced from production — the source-scan tests enforce that.
 */
fun grantForTests(): EditGrant = EditGrant()

fun createGrantForTests(): CreateGrant = CreateGrant()

fun manageGrantForTests(): ManageGrant = ManageGrant()

fun approveGrantForTests(): ApproveGrant = ApproveGrant()
