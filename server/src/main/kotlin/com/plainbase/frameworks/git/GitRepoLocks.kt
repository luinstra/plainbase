package com.plainbase.frameworks.git

/**
 * C5's shared object-mode monitors, supplied by the prepared root-history selection so
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
 * Materialized only for object+git-enabled history; LOCAL and git-disabled object boots leave it unconstructed.
 */
class GitRepoLocks(val repoWrite: Any = Any(), val ship: Any = Any())
