package com.plainbase.frameworks.git

/**
 * C5's shared object-mode monitors, held by exactly one Koin `single` (`historyModule`) so
 * [GitCliHistoryProvider] and [GitBundleDr] share the SAME instances:
 *
 * - [repoWrite] excludes a commit's ref-mutating span (stage -> write-tree -> commit-tree ->
 *   update-ref, wrapped in [GitCliHistoryProvider.commit]) from [GitBundleDr]'s `bundle create`
 *   and boot-reconcile ref mutation, so `bundle create --all` (which reads refs) can never observe
 *   a torn ref update (HOLE B / Cluster-3a).
 * - [ship] serializes the whole bundle-ship operation (build the bundle bytes under [repoWrite],
 *   then PUT OUTSIDE any lock) so a slow, older ship can never land after a newer one, and a
 *   graceful-shutdown flush racing an in-flight cadence ship is likewise serialized (HOLE B).
 *
 * Registered UNCONDITIONALLY-but-LAZY in `historyModule` (the R9 exemplar,
 * `ContentModule.kt`'s `contentDirStoreConstructions`): resolved ONLY on the object+git-enabled
 * path, so a LOCAL boot or a git-disabled object boot never constructs one.
 */
class GitRepoLocks(val repoWrite: Any = Any(), val ship: Any = Any())
