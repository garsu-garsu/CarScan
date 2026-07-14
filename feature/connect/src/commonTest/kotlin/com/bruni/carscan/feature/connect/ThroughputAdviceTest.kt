package com.bruni.carscan.feature.connect

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The arithmetic behind the one sentence this screen exists to say.
 *
 * A counterfeit ELM327 manages 10–20 queries per second in total. Eight gauges at 10 Hz
 * need 80. The gap is not closable, so the only honest move is to state the budget the
 * adapter can actually fund — and a budget that flatters the adapter is worse than none,
 * because the user then blames the app for the stutter.
 */
class ThroughputAdviceTest {

    @Test
    fun `a fourteen-query clone funds six tiles at two hertz`() {
        // The number from the brief, and the sentence the whole screen is built to say.
        adviseThroughput(capacityHz = 14.0) shouldBe ThroughputAdvice(queriesPerSec = 14, tiles = 6, hz = 2)
    }

    @Test
    fun `capacity is floored, never rounded — 14,9 measured is 14 promised`() {
        // Rounding 14.9 up to 15 would fund 6 tiles at 2 Hz *and* overcommit by a query a
        // second, forever. The screen's whole claim is that its number is not flattering.
        adviseThroughput(capacityHz = 14.9).shouldNotBeNullAnd { it.queriesPerSec shouldBe 14 }
    }

    @Test
    fun `a fast adapter is still capped at ten hertz — we do not invent a rate nobody asked for`() {
        // 100 q/s would fund 6 tiles at 16 Hz. No gauge redraws that fast and no human sees
        // it; the ladder stops at 10 because that is the fastest rate the dashboard offers.
        adviseThroughput(capacityHz = 100.0) shouldBe ThroughputAdvice(queriesPerSec = 100, tiles = 6, hz = 10)
    }

    @Test
    fun `an adapter too slow for six tiles loses tiles, not honesty`() {
        // 4 q/s cannot fund 6 tiles even at 1 Hz. Stretching six tiles to 0.67 Hz and calling
        // it 1 Hz is the lie; dropping to four tiles is the truth.
        adviseThroughput(capacityHz = 4.0) shouldBe ThroughputAdvice(queriesPerSec = 4, tiles = 4, hz = 1)
    }

    @Test
    fun `zero capacity has no advice — an unmeasured adapter must not read as a dead one`() {
        // capacityHz is 0 until the first round trip returns. Rendering "0 queries/sec" there
        // would libel every adapter on every connect, and a warning that always fires is one
        // nobody reads when it finally matters.
        adviseThroughput(capacityHz = 0.0).shouldBeNull()
    }

    @Test
    fun `a sub-one-query adapter has no advice either — there is no budget to state`() {
        adviseThroughput(capacityHz = 0.6).shouldBeNull()
    }

    @Test
    fun `the tile budget is what the caller asks for, not a constant`() {
        // The dashboard decides how many tiles it wants; this only decides what they can afford.
        adviseThroughput(capacityHz = 14.0, desiredTiles = 2) shouldBe
            ThroughputAdvice(queriesPerSec = 14, tiles = 2, hz = 5)
    }

    private inline fun <T : Any> T?.shouldNotBeNullAnd(block: (T) -> Unit) {
        checkNotNull(this) { "expected advice, got null" }.also(block)
    }
}
