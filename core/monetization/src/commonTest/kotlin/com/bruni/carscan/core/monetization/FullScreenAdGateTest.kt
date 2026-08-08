package com.bruni.carscan.core.monetization

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The one gate every full-screen ad format (interstitial + app open) shares, so they can never
 * fire back-to-back and a fresh install is never met with an ad in its first two minutes.
 */
class FullScreenAdGateTest {

    private var now = 0L
    private val clock = { now }
    private var connected = false

    private fun gate(config: AdCadenceConfig = AdCadenceConfig()) =
        FullScreenAdGate(clock, config, isScannerConnected = { connected })

    @Test
    fun `inside the warmup window, shouldShow is false`() {
        val gate = gate()
        now = 60_000L // one minute in, warmup is 120s

        gate.shouldShow() shouldBe false
    }

    @Test
    fun `once the warmup has elapsed, the first call is true`() {
        val gate = gate()
        now = 120_000L

        gate.shouldShow() shouldBe true
    }

    @Test
    fun `immediately after record, shouldShow is false — the minimum interval has not passed`() {
        val gate = gate()
        now = 120_000L
        gate.record()
        now += 1_000L

        gate.shouldShow() shouldBe false
    }

    @Test
    fun `once the minimum interval passes, shouldShow is true again`() {
        val gate = gate()
        now = 120_000L
        gate.record()
        now += 75_000L

        gate.shouldShow() shouldBe true
    }

    @Test
    fun `blocks once maxPerSession has been shown`() {
        val gate = gate(AdCadenceConfig(warmupMs = 0, minIntervalMs = 0, maxPerSession = 2))

        gate.record()
        gate.record()

        gate.shouldShow() shouldBe false
    }

    @Test
    fun `interstitial and app open share one gate — the second cannot fire inside the interval`() {
        // Two ad formats consulting the same gate instance, the way they will in production —
        // an interstitial firing on disconnect must not leave a window for an app-open ad to
        // also fire moments later.
        val gate = gate()
        now = 120_000L

        gate.shouldShow() shouldBe true // the interstitial asks first
        gate.record()

        now += 10_000L
        gate.shouldShow() shouldBe false // the app-open ad asks next — blocked by the shared gate
    }

    @Test
    fun `a connected scanner blocks a full-screen ad the cadence would otherwise allow`() {
        // The driving context: the phone is in a cradle in a moving car. A full-screen ad there —
        // one of which shipped with no close button — is not a lost impression, it is a driver
        // pressing Back four times on AdActivity.
        val gate = gate()
        now = 120_000L
        connected = true

        gate.shouldShow() shouldBe false
    }

    @Test
    fun `once the scanner disconnects, the cadence decides again`() {
        val gate = gate()
        now = 120_000L
        connected = true
        gate.shouldShow() shouldBe false

        connected = false

        // The connection is a veto, not a counter: blocking during the drive must not have burned
        // a session slot or restarted the interval.
        gate.shouldShow() shouldBe true
    }
}
