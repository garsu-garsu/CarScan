package com.bruni.carscan.core.data

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.asDoubleOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val SPEED_KEY = MetricKey.Metric(SuggestedMetric.SPEED)
private val RPM_KEY = MetricKey.Signal("ENGINE_RPM")

class VehicleSessionRepositoryTest {

    @Test
    fun `latest holds the newest sample for each key`() = runTest {
        val source = FakeSampleSource()
        val repo = DefaultVehicleSessionRepository(source, backgroundScope)
        runCurrent() // let the repository subscribe

        source.emit(speed(50.0, ts = 1))
        source.emit(rpm(1726.0, ts = 2))
        source.emit(speed(60.0, ts = 3))
        runCurrent()

        assertEquals(2, repo.latest.value.size)
        assertEquals(60.0, repo.latest.value.getValue(SPEED_KEY).value.asDoubleOrNull)
        assertEquals(1726.0, repo.latest.value.getValue(RPM_KEY).value.asDoubleOrNull)
    }

    /**
     * A gauge redrawing at 60 fps cannot consume a 200 Hz stream and must not try.
     * `latest` is a StateFlow, so a collector that falls behind skips the intermediate
     * frames and resumes on the newest value — rather than working through a backlog
     * to render a number that is already stale, which is what a buffered flow would do.
     */
    @Test
    fun `latest is conflated - a slow collector skips frames but never lags behind`() = runTest {
        val source = FakeSampleSource()
        val repo = DefaultVehicleSessionRepository(source, backgroundScope)

        val seen = mutableListOf<Double>()
        backgroundScope.launch {
            repo.latest.collect { snapshot ->
                snapshot[SPEED_KEY]?.value?.asDoubleOrNull?.let { seen += it }
                delay(100) // a slow gauge: 10 fps
            }
        }
        runCurrent()

        // 1000 samples, one per virtual millisecond.
        repeat(1_000) { i ->
            source.emit(speed(i.toDouble(), ts = i.toLong()))
            advanceTimeBy(1)
        }
        advanceTimeBy(500) // let the slow collector come back round

        assertTrue(seen.size < 50, "a 10 fps collector must not see 1000 frames; it saw ${seen.size}")
        assertEquals(999.0, seen.last(), "it must resume on the newest value, not on a backlog")
        assertEquals(999.0, repo.latest.value.getValue(SPEED_KEY).value.asDoubleOrNull)
    }

    /**
     * The repository must never grow with time. An eight-hour drive at 200 Hz is about
     * 5.8 million samples; anything that keeps them is an out-of-memory crash with a
     * timetable. Windowing is a presentation concern — the live chart owns a ring
     * buffer, and the disk gets 1 Hz aggregates. Here only the newest per key is held.
     */
    @Test
    fun `latest never accumulates - thirty thousand samples leave it the size of the key set`() = runTest {
        val source = FakeSampleSource()
        val repo = DefaultVehicleSessionRepository(source, backgroundScope)
        runCurrent()

        repeat(10_000) { i ->
            source.emit(speed(i.toDouble(), ts = i.toLong()))
            source.emit(rpm(i.toDouble(), ts = i.toLong()))
            source.emit(coolant(90.0, ts = i.toLong()))
            runCurrent()
        }

        assertEquals(3, repo.latest.value.size, "three signals must occupy three entries, not 30,000")
        assertEquals(9_999.0, repo.latest.value.getValue(SPEED_KEY).value.asDoubleOrNull)
    }

    /** Charts and the HUD need every sample, not just the newest. */
    @Test
    fun `samples passes each sample through`() = runTest {
        val source = FakeSampleSource()
        val repo = DefaultVehicleSessionRepository(source, backgroundScope)

        val received = mutableListOf<Double>()
        backgroundScope.launch {
            repo.samples.collect { received += it.value.asDoubleOrNull!! }
        }
        runCurrent()

        repeat(20) { i -> source.emit(speed(i.toDouble(), ts = i.toLong())) }
        runCurrent()

        assertEquals((0..19).map { it.toDouble() }, received)
    }
}
