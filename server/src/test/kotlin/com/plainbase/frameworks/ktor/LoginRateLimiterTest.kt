package com.plainbase.frameworks.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A test [TimeSource] the test advances explicitly — deterministic window/backoff assertions. */
private class FakeTimeSource : TimeSource {
    var elapsed: Duration = Duration.ZERO
    override fun markNow(): TimeMark = object : TimeMark {
        private val origin = elapsed
        override fun elapsedNow(): Duration = elapsed - origin
    }
}

/**
 * LoginRateLimiter (A4a WI-4, §6): repeated failures past the threshold for one (IP,username) throttle; a fresh IP
 * for the SAME username is Allowed (NEVER a global per-username lockout — the DoS-lever guard); the SAME IP
 * hitting different usernames is throttled by the per-IP key; a fresh limiter instance (simulating restart) is
 * Allowed (the §0.9 reset-on-restart tradeoff).
 *
 * The limiter is a BOUNDED, FAIL-CLOSED access-order LRU (review B2): under a flood of distinct throwaway keys it
 * stays at the cap by evicting the COLDEST key, never the actively-attacked (hot) one, and a brand-new key under the
 * cap is ALWAYS tracked — so it throttles after the cap rather than being perpetually Allowed (no fail-open bypass).
 */
class LoginRateLimiterTest : FunSpec({

    fun limiter(time: FakeTimeSource) = LoginRateLimiter(maxFailures = 3, window = 60.seconds, timeSource = time)

    test("repeated failures past the threshold for one (IP, username) → Throttle") {
        val time = FakeTimeSource()
        val rl = limiter(time)
        repeat(3) { rl.recordFailure("1.2.3.4", "alice") }
        rl.check("1.2.3.4", "alice").shouldBeInstanceOf<LoginRateLimiter.Decision.Throttle>()
    }

    test("a DIFFERENT IP for the SAME username is Allowed — never a global per-username lockout") {
        val time = FakeTimeSource()
        val rl = limiter(time)
        repeat(10) { rl.recordFailure("1.2.3.4", "alice") } // hammer alice from one IP
        rl.check("9.9.9.9", "alice").shouldBeInstanceOf<LoginRateLimiter.Decision.Allowed>()
    }

    test("the SAME IP across DIFFERENT usernames is throttled by the per-IP key") {
        val time = FakeTimeSource()
        val rl = limiter(time)
        // 3 distinct usernames, all from one IP → the per-IP window crosses the cap even though no single
        // (IP, username) did.
        rl.recordFailure("1.2.3.4", "a")
        rl.recordFailure("1.2.3.4", "b")
        rl.recordFailure("1.2.3.4", "c")
        rl.check("1.2.3.4", "d").shouldBeInstanceOf<LoginRateLimiter.Decision.Throttle>()
    }

    test("the window relaxes after it elapses") {
        val time = FakeTimeSource()
        val rl = limiter(time)
        repeat(3) { rl.recordFailure("1.2.3.4", "alice") }
        rl.check("1.2.3.4", "alice").shouldBeInstanceOf<LoginRateLimiter.Decision.Throttle>()
        time.elapsed = 61.seconds // past the 60s window — the failures age out
        rl.check("1.2.3.4", "alice").shouldBeInstanceOf<LoginRateLimiter.Decision.Allowed>()
    }

    test("a fresh limiter instance (restart) is Allowed — the in-process reset-on-restart tradeoff") {
        val time = FakeTimeSource()
        val rl = limiter(time)
        repeat(10) { rl.recordFailure("1.2.3.4", "alice") }
        // A new instance has no memory of the prior process's windows.
        limiter(time).check("1.2.3.4", "alice").shouldBeInstanceOf<LoginRateLimiter.Decision.Allowed>()
    }

    test("a fully-aged key is dropped on the next check — the map shrinks back toward idle") {
        val time = FakeTimeSource()
        val rl = limiter(time)
        // Distinct IPs each add an ip-key AND an ipuser-key → the map grows with traffic.
        repeat(50) { rl.recordFailure("10.0.0.$it", "alice") }
        (rl.trackedKeys > 0) shouldBe true
        time.elapsed = 61.seconds // past the 60s window — every failure has aged out
        // A check over each prior key prunes it (a fully-aged-out window is removed); the map returns to empty.
        repeat(50) { rl.check("10.0.0.$it", "alice") }
        rl.trackedKeys shouldBe 0
    }

    test("the map stays bounded under many distinct keys — never exceeds the hard cap") {
        val time = FakeTimeSource()
        val rl = LoginRateLimiter(maxFailures = 3, window = 60.seconds, maxKeys = 64, timeSource = time)
        // Hammer FAR more distinct (IP, username) pairs than the cap — an IP-rotating attacker. Each pair adds an
        // ip-key + an ipuser-key, so traffic would blow past 64 keys without the LRU ceiling.
        repeat(1_000) { rl.recordFailure("10.${it / 256}.${it % 256}.7", "u$it") }
        rl.trackedKeys shouldBeLessThanOrEqual 64
    }

    test("FAIL-CLOSED: a brand-new key under attack still throttles even with the map saturated") {
        val time = FakeTimeSource()
        val rl = LoginRateLimiter(maxFailures = 3, window = 60.seconds, maxKeys = 8, timeSource = time)
        // Saturate the map with distinct dummy keys (the flood an attacker uses to evict tracking).
        repeat(1_000) { rl.recordFailure("7.${it / 256}.${it % 256}.3", "dummy$it") }
        rl.trackedKeys shouldBeLessThanOrEqual 8
        // NOW a fresh victim (IP, username) repeats failures: the old fail-OPEN limiter refused to track it past the
        // cap and returned Allowed forever. The fail-CLOSED LRU evicts a cold dummy to make room, so the victim
        // accrues failures and MUST throttle after maxFailures.
        repeat(3) { rl.recordFailure("5.5.5.5", "victim") }
        rl.check("5.5.5.5", "victim").shouldBeInstanceOf<LoginRateLimiter.Decision.Throttle>()
    }

    test("an actively-attacked key survives a flood of distinct keys — it stays HOT, never evicted") {
        val time = FakeTimeSource()
        val rl = LoginRateLimiter(maxFailures = 3, window = 60.seconds, maxKeys = 8, timeSource = time)
        // Throttle one victim pair to the cap.
        repeat(3) { rl.recordFailure("1.2.3.4", "alice") }
        rl.check("1.2.3.4", "alice").shouldBeInstanceOf<LoginRateLimiter.Decision.Throttle>()
        // Now flood distinct pairs AND keep touching the victim between bursts (the real attack keeps the victim hot
        // via check/record on every probe). Access-order keeps the victim at the MRU end, so the cold flood evicts
        // first — the victim's throttle survives.
        repeat(1_000) {
            rl.recordFailure("9.${it / 256}.${it % 256}.1", "u$it")
            rl.check("1.2.3.4", "alice")
        }
        rl.trackedKeys shouldBeLessThanOrEqual 8
        rl.check("1.2.3.4", "alice").shouldBeInstanceOf<LoginRateLimiter.Decision.Throttle>()
    }
})
