package com.plainbase.frameworks.ktor

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * In-memory login throttle (§6): a bounded, FAIL-CLOSED map of fixed-size sliding windows keyed BOTH per-IP AND
 * per-(IP, username) composite, over a monotonic clock ([TimeSource.Monotonic] — native-safe, no scattered
 * `System.nanoTime`). [check] returns [Decision.Allowed] or [Decision.Throttle] with a backoff the route applies
 * via `kotlinx.coroutines.delay` (NOT `Thread.sleep` — that would park the CIO dispatcher).
 *
 * NEVER a global per-username lockout (a DoS lever — the synthesis is explicit): the per-IP key throttles a single
 * source hammering many usernames, and the per-(IP, username) key throttles a single source hammering one account,
 * but a username is never locked across IPs — a fresh IP for the same username is [Decision.Allowed]. In-process,
 * per-instance, and resets on restart (the declared §0.9 tradeoff — intentional, not a gap).
 *
 * [recordFailure] is called only on a FAILED login; a success does not advance the window. The route checks BEFORE
 * attempting the login and records on failure, so a throttled caller never reaches the password verify.
 *
 * The map is bounded by a fixed-capacity ACCESS-ORDER LRU ([LinkedHashMap] with `accessOrder=true` + an O(1)
 * `removeEldestEntry` eviction): once it holds [maxKeys] keys, inserting a new one evicts the COLDEST (least-recently
 * accessed) key. Crucially the limiter is FAIL-CLOSED — a new or under-attack key is ALWAYS inserted (evicting a cold
 * key to make room), so it accrues failures and throttles after [maxFailures]. An actively-attacked key stays HOT
 * (every [check]/[recordFailure] touches it, refreshing its access order), so a flood of throwaway keys evicts the
 * cold filler, never the victim. There is no per-request scan — eviction is O(1) and access is O(1). All access (both
 * [check] and [recordFailure]) is serialized under one [lock]: login is argon2-gated and low-QPS, so a single mutex is
 * ample and eliminates any check-then-act race. [LinkedHashMap] is native-image-safe (no reflection).
 */
class LoginRateLimiter(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val window: Duration = DEFAULT_WINDOW,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
    timeSource: TimeSource = TimeSource.Monotonic,
) {

    private val lock = Any()

    // Access-order LRU (the `true` 3rd arg): every get/put moves the key to the most-recently-used end, so
    // removeEldestEntry evicts the COLDEST key when the map is over capacity. Sized to maxKeys, default 0.75 load.
    private val windows = object : LinkedHashMap<String, Window>(maxKeys, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Window>): Boolean = size > maxKeys
    }

    /** Distinct tracked keys — test-visible so the bounded-LRU eviction is observable. */
    internal val trackedKeys: Int get() = synchronized(lock) { windows.size }

    /** A fixed monotonic origin captured at construction; every instant below is the elapsed-since-origin Duration. */
    private val origin = timeSource.markNow()

    private fun now(): Duration = origin.elapsedNow()

    /** Allowed when BOTH the per-IP and the per-(IP, username) windows are under the failure cap; else throttled. */
    fun check(ip: String, username: String): Decision = synchronized(lock) {
        val now = now()
        val ipDecision = decide(ipKey(ip), now)
        val userDecision = decide(userKey(ip, username), now)
        // The longer backoff of the two keys wins (whichever is more throttled gates the request).
        when {
            ipDecision is Decision.Throttle && userDecision is Decision.Throttle ->
                if (ipDecision.backoff >= userDecision.backoff) ipDecision else userDecision
            ipDecision is Decision.Throttle -> ipDecision
            userDecision is Decision.Throttle -> userDecision
            else -> Decision.Allowed
        }
    }

    /** Record a failed attempt against BOTH keys, so the next [check] sees the advanced windows. */
    fun recordFailure(ip: String, username: String) = synchronized(lock) {
        val now = now()
        record(ipKey(ip), now)
        record(userKey(ip, username), now)
    }

    /** Append [now] to the key's window (creating it if absent). The LRU evicts the coldest key if this overfills it. */
    private fun record(key: String, now: Duration) {
        // get() refreshes access order for an existing (possibly under-attack) key; put() inserts/updates and may
        // trigger removeEldestEntry — which evicts the COLDEST key, never this one (we just touched it last).
        windows[key] = (windows[key] ?: Window()).recording(now, window)
    }

    private fun decide(key: String, now: Duration): Decision {
        val tracked = windows[key] ?: return Decision.Allowed
        val pruned = tracked.pruned(now, window)
        if (pruned == null) {
            // Every failure aged out — drop the now-empty key so the map shrinks back toward idle.
            windows.remove(key)
            return Decision.Allowed
        }
        // get() above already refreshed access order; rewrite the pruned window so retained failures stay bounded.
        windows[key] = pruned
        if (pruned.failures.size < maxFailures) return Decision.Allowed
        // Backoff = the time until the OLDEST in-window failure ages out of the window (so the cap relaxes).
        return Decision.Throttle(window - (now - pruned.failures.min()))
    }

    private fun ipKey(ip: String) = "ip:$ip"

    // The NUL separator is collision-proof: neither an IP nor a username can contain it, so no (ip, username) pair can
    // collide with another by concatenation. Built from Char(0) so the source stays text-clean (no in-string NUL byte).
    private fun userKey(ip: String, username: String) = "ipuser:$ip${Char(0)}$username"

    /** A bounded list of recent failure instants (monotonic), oldest first; pruned to the window on each record. */
    private class Window(val failures: List<Duration> = emptyList()) {
        fun recording(now: Duration, window: Duration): Window =
            Window((failures.filter { now - it < window } + now).takeLast(MAX_RETAINED))

        /** This window with aged-out failures dropped; null when nothing in-window survives (so the key is evictable). */
        fun pruned(now: Duration, window: Duration): Window? =
            failures.filter { now - it < window }.takeIf { it.isNotEmpty() }?.let(::Window)

        private companion object {
            /** Cap retained instants so a sustained attack can't grow a window unboundedly. */
            const val MAX_RETAINED = 64
        }
    }

    /** The verdict for one [check]: proceed, or back off for [Decision.Throttle.backoff] before retrying. */
    sealed interface Decision {
        data object Allowed : Decision

        data class Throttle(val backoff: Duration) : Decision
    }

    private companion object {
        const val DEFAULT_MAX_FAILURES = 5
        val DEFAULT_WINDOW = 60.seconds

        /**
         * Hard ceiling on distinct tracked keys — bounds memory under IP/username rotation (the DoS guard). Generous
         * so normal operation never evicts; an attacker rotating keys only ever evicts COLD filler, never a hot victim.
         */
        const val DEFAULT_MAX_KEYS = 10_000
    }
}
