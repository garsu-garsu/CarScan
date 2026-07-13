package com.bruni.carscan.core.transport.fake

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DrivingSimulatorTest {

    private val sim = DrivingSimulator()

    @Test
    fun `the same virtual time yields the same state, every time`() {
        val times = listOf(0L, 7_000L, 12_500L, 30_000L, 52_000L, 61_000L, 199_999L)
        val first = times.map { sim.stateAt(it) }
        val second = times.map { sim.stateAt(it) }
        val fromAnotherInstance = times.map { DrivingSimulator().stateAt(it) }

        second shouldBe first
        fromAnotherInstance shouldBe first
    }

    @Test
    fun `the cycle walks idle then accelerate then cruise then brake`() {
        sim.stateAt(2_000).phase shouldBe DrivingPhase.IDLE
        sim.stateAt(17_000).phase shouldBe DrivingPhase.ACCELERATE
        sim.stateAt(30_000).phase shouldBe DrivingPhase.CRUISE
        sim.stateAt(50_000).phase shouldBe DrivingPhase.BRAKE
        // and it repeats
        sim.stateAt(62_000).phase shouldBe DrivingPhase.IDLE
    }

    @Test
    fun `idle is a stopped engine at idle rpm, cruise is moving`() {
        val idle = sim.stateAt(2_000)
        idle.speedKph shouldBe 0.0
        idle.rpm shouldBe 800.0
        idle.throttlePct shouldBe 0.0

        val cruise = sim.stateAt(30_000)
        cruise.speedKph shouldBe 100.0
        (cruise.rpm > idle.rpm) shouldBe true
    }

    @Test
    fun `speed rises monotonically through the acceleration phase`() {
        val samples = (10_000L..25_000L step 1_000).map { sim.stateAt(it).speedKph }
        samples.zipWithNext().all { (a, b) -> b >= a } shouldBe true
    }

    /** 55.5 % is the anchor: 55.5 x 2 = 111 = 0x6F, the byte the Kia fixture carries. */
    @Test
    fun `state of charge starts at the Kia anchor value and drains slowly`() {
        sim.stateAt(0).socPct shouldBe (55.5 plusOrMinus 1e-9)
        sim.stateAt(60_000).socPct shouldBe (55.0 plusOrMinus 1e-9)
    }

    @Test
    fun `coolant warms from cold to operating temperature and then holds`() {
        sim.stateAt(0).coolantC shouldBe (20.0 plusOrMinus 1e-9)
        sim.stateAt(120_000).coolantC shouldBe (90.0 plusOrMinus 1e-9)
        sim.stateAt(600_000).coolantC shouldBe (90.0 plusOrMinus 1e-9)
    }
}
