package com.bruni.carscan.core.transport.fake

import kotlin.math.min

/** Where the simulated car is in its cycle. */
enum class DrivingPhase { IDLE, ACCELERATE, CRUISE, BRAKE }

/** Everything the emulator can be asked about, in native units. */
data class VehicleState(
    val phase: DrivingPhase,
    val rpm: Double,
    val speedKph: Double,
    val coolantC: Double,
    val throttlePct: Double,
    val fuelPct: Double,
    val socPct: Double,
)

/**
 * Supplies the values the emulator answers with.
 *
 * Swap in a constant for a test that cares about one exact byte
 * (`VehicleStateSource { fixed }`), or the [DrivingSimulator] for a dashboard that
 * needs numbers that move like a car.
 */
fun interface VehicleStateSource {
    fun stateAt(elapsedMs: Long): VehicleState
}

/**
 * A pure function of elapsed time: idle, accelerate, cruise, brake, repeat.
 *
 * Purity is the whole point. The same virtual millisecond always produces the same
 * reading, so a dashboard test and a chart test can assert on exact values, and a
 * failure is always reproducible.
 *
 * Speed, rpm and throttle cycle. Coolant and charge do not — they run monotonically
 * off absolute elapsed time, because a car that has been driving for ten minutes is
 * warm and slightly emptier than one that just started.
 */
class DrivingSimulator(
    private val cycleMs: Long = 60_000,
    private val startSocPct: Double = 55.5,
    private val startFuelPct: Double = 60.0,
) : VehicleStateSource {

    override fun stateAt(elapsedMs: Long): VehicleState {
        val elapsed = elapsedMs.coerceAtLeast(0)
        val phaseMs = elapsed % cycleMs

        val idleEnd = cycleMs / 6          // 0 - 10 s
        val accelEnd = cycleMs * 5 / 12    // 10 - 25 s
        val cruiseEnd = cycleMs * 3 / 4    // 25 - 45 s

        val phase: DrivingPhase
        val rpm: Double
        val speed: Double
        val throttle: Double

        when {
            phaseMs < idleEnd -> {
                phase = DrivingPhase.IDLE
                rpm = IDLE_RPM
                speed = 0.0
                throttle = 0.0
            }

            phaseMs < accelEnd -> {
                phase = DrivingPhase.ACCELERATE
                val t = (phaseMs - idleEnd).toDouble() / (accelEnd - idleEnd)
                rpm = IDLE_RPM + (PEAK_RPM - IDLE_RPM) * t
                speed = CRUISE_KPH * t
                throttle = PEAK_THROTTLE_PCT * t
            }

            phaseMs < cruiseEnd -> {
                phase = DrivingPhase.CRUISE
                rpm = CRUISE_RPM
                speed = CRUISE_KPH
                throttle = CRUISE_THROTTLE_PCT
            }

            else -> {
                phase = DrivingPhase.BRAKE
                val t = (phaseMs - cruiseEnd).toDouble() / (cycleMs - cruiseEnd)
                rpm = CRUISE_RPM - (CRUISE_RPM - IDLE_RPM) * t
                speed = CRUISE_KPH * (1.0 - t)
                throttle = 0.0
            }
        }

        val warmup = min(1.0, elapsed.toDouble() / WARMUP_MS)
        val minutes = elapsed.toDouble() / 60_000.0

        return VehicleState(
            phase = phase,
            rpm = rpm,
            speedKph = speed,
            coolantC = COLD_C + (OPERATING_C - COLD_C) * warmup,
            throttlePct = throttle,
            fuelPct = (startFuelPct - minutes * DRAIN_PCT_PER_MINUTE).coerceAtLeast(0.0),
            socPct = (startSocPct - minutes * DRAIN_PCT_PER_MINUTE).coerceAtLeast(0.0),
        )
    }

    private companion object {
        const val IDLE_RPM = 800.0
        const val PEAK_RPM = 3500.0
        const val CRUISE_RPM = 2000.0
        const val CRUISE_KPH = 100.0
        const val PEAK_THROTTLE_PCT = 70.0
        const val CRUISE_THROTTLE_PCT = 20.0
        const val COLD_C = 20.0
        const val OPERATING_C = 90.0
        const val WARMUP_MS = 120_000.0
        const val DRAIN_PCT_PER_MINUTE = 0.5
    }
}
