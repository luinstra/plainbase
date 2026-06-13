package com.plainbase.domain.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The §B2 scheduler semantics under a fake clock — fully deterministic, no real threads, no sleeps:
 * trailing 500 ms debounce, the 5 s max-latency cap under a continuous event storm, single-flight
 * with the dirty-flag collapse (N events during a running rebuild → exactly ONE follow-up — the
 * startup-ordering case), and close() semantics. The fake [Alarm][RebuildScheduler.Alarm] records
 * due times and fires in order as [FakeTime.advanceTo] walks the clock forward.
 */
class RebuildSchedulerTest : FunSpec({

    test("trailing debounce: nothing fires while events keep arriving; one rebuild fires when the tree goes quiet") {
        val time = FakeTime()
        var rebuilds = 0
        val scheduler = RebuildScheduler(rebuild = { rebuilds++ }, clock = time::now, alarm = time.alarm)

        scheduler.schedule()
        time.advanceTo(499)
        rebuilds shouldBe 0
        time.at(499) { scheduler.schedule() } // re-arms: quiet starts over
        time.advanceTo(998)
        rebuilds shouldBe 0
        time.advanceTo(999)
        rebuilds shouldBe 1
        time.advanceTo(60_000) // quiet forever after: nothing else fires
        rebuilds shouldBe 1
    }

    test("no events, no rebuilds — the scheduler is purely event-driven") {
        val time = FakeTime()
        var rebuilds = 0
        RebuildScheduler(rebuild = { rebuilds++ }, clock = time::now, alarm = time.alarm)
        time.advanceTo(60_000)
        rebuilds shouldBe 0
    }

    test("the 5s cap: a continuous event storm cannot starve the rebuild") {
        val time = FakeTime()
        val fireTimes = mutableListOf<Long>()
        val scheduler = RebuildScheduler(rebuild = { fireTimes += time.now() }, clock = time::now, alarm = time.alarm)

        // Events every 100 ms — the 500 ms debounce never goes quiet.
        var t = 0L
        while (t <= 9_900) {
            time.at(t) { scheduler.schedule() }
            t += 100
        }
        time.advanceTo(30_000)

        // First fire at the cap (first event at 0 + 5000 ms); the storm re-accumulates from the
        // event at 5000 (it lands just after that fire) and never goes quiet through 9900, so the
        // second fire hits ITS cap too: 5000 + 5000. Never starved, never more often than the cap.
        fireTimes shouldBe listOf(5_000L, 10_000L)
    }

    test("single-flight dirty flag: N events during a running rebuild collapse into exactly ONE follow-up") {
        val time = FakeTime()
        var rebuilds = 0
        lateinit var scheduler: RebuildScheduler
        scheduler = RebuildScheduler(
            rebuild = {
                rebuilds++
                // The startup-ordering case (§B2): events land while the rebuild is in flight.
                if (rebuilds == 1) repeat(5) { scheduler.schedule() }
            },
            clock = time::now,
            alarm = time.alarm,
        )

        scheduler.schedule()
        time.advanceTo(500)
        rebuilds shouldBe 1 // the five during-flight events are pending, not yet rebuilt
        time.advanceTo(999)
        rebuilds shouldBe 1 // their debounce runs from the events' own timestamps (t=500)
        time.advanceTo(1_000)
        rebuilds shouldBe 2 // exactly one follow-up
        time.advanceTo(60_000)
        rebuilds shouldBe 2
    }

    test("close() stops everything: pending work is dropped and later events are ignored") {
        val time = FakeTime()
        var rebuilds = 0
        val scheduler = RebuildScheduler(rebuild = { rebuilds++ }, clock = time::now, alarm = time.alarm)

        scheduler.schedule()
        scheduler.close()
        scheduler.schedule()
        time.advanceTo(60_000)
        rebuilds shouldBe 0
    }

    test("a throwing rebuild is contained: the scheduler keeps serving the next event") {
        val time = FakeTime()
        var attempts = 0
        val scheduler = RebuildScheduler(
            rebuild = {
                attempts++
                if (attempts == 1) error("rebuild blew up (deliberately)")
            },
            clock = time::now,
            alarm = time.alarm,
        )

        scheduler.schedule()
        time.advanceTo(500)
        attempts shouldBe 1
        time.at(1_000) { scheduler.schedule() }
        time.advanceTo(1_500)
        attempts shouldBe 2
    }
})

/** Deterministic time: alarms are recorded with their due instant and fired in order as the clock advances. */
private class FakeTime {

    private var nowMillis = 0L
    private val alarms = mutableListOf<Pair<Long, () -> Unit>>()

    val alarm = RebuildScheduler.Alarm { delayMillis, action -> alarms += (nowMillis + delayMillis) to action }

    fun now(): Long = nowMillis

    /** Runs [action] (typically a schedule call) with the clock set to [t], firing anything due first. */
    fun at(t: Long, action: () -> Unit) {
        advanceTo(t)
        action()
    }

    /** Walks the clock to [t], firing every due alarm in due-time order (fired alarms may arm new ones). */
    fun advanceTo(t: Long) {
        while (true) {
            val next = alarms.filter { it.first <= t }.minByOrNull { it.first } ?: break
            alarms.remove(next)
            nowMillis = maxOf(nowMillis, next.first)
            next.second()
        }
        nowMillis = t
    }
}
