package com.bruni.carscan.core.data

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Health is a *product* surface, not a debug readout. A counterfeit ELM327 does 10–20
 * queries per second in total; eight gauges at 10 Hz need 80. No scheduling trick
 * closes that gap, so the app has to be able to say "your adapter: 14 queries/sec —
 * showing 6 tiles at 2 Hz" rather than silently rendering stale numbers.
 */
class SessionHealthTest {

    @Test
    fun `before anything connects, health claims nothing`() {
        val health = SessionHealth()
        assertEquals(ConnectionState.DISCONNECTED, health.connection)
        assertEquals(0.0, health.capacityHz)
        assertEquals(0.0, health.loadHz)
        assertFalse(health.isOverSubscribed)
    }

    @Test
    fun `an adapter asked for more than it can deliver is over-subscribed`() {
        val health = SessionHealth(
            connection = ConnectionState.CONNECTED,
            capacityHz = 14.0, // a €5 clone
            loadHz = 80.0, // eight gauges at 10 Hz
        )
        assertTrue(health.isOverSubscribed)
    }

    @Test
    fun `an adapter keeping up is not over-subscribed`() {
        val health = SessionHealth(
            connection = ConnectionState.CONNECTED,
            capacityHz = 100.0, // an OBDLink MX+
            loadHz = 80.0,
        )
        assertFalse(health.isOverSubscribed)
    }

    /**
     * Capacity is measured from real round trips, so it is 0.0 until the first one
     * comes back. Treating "not measured yet" as "cannot cope" would flash a warning
     * at every user on every connect, and a warning that always fires is one nobody
     * reads when it matters.
     */
    @Test
    fun `an adapter whose capacity is not yet measured is not called over-subscribed`() {
        val health = SessionHealth(
            connection = ConnectionState.CONNECTING,
            capacityHz = 0.0,
            loadHz = 80.0,
        )
        assertFalse(health.isOverSubscribed)
    }

    @Test
    fun `the repository passes the source's health straight through`() = runTest {
        val source = FakeSampleSource()
        val repo = DefaultVehicleSessionRepository(source, backgroundScope)
        runCurrent()

        assertEquals(ConnectionState.DISCONNECTED, repo.health.value.connection)

        source.report(
            SessionHealth(
                connection = ConnectionState.CONNECTED,
                capacityHz = 14.2,
                loadHz = 22.0,
                meanRttMs = 70.4,
                dropRatePct = 8.5,
                stretchedCount = 3,
            ),
        )
        runCurrent()

        val health = repo.health.value
        assertEquals(ConnectionState.CONNECTED, health.connection)
        assertEquals(14.2, health.capacityHz)
        assertEquals(22.0, health.loadHz)
        assertEquals(70.4, health.meanRttMs)
        assertEquals(8.5, health.dropRatePct)
        assertEquals(3, health.stretchedCount)
        assertTrue(health.isOverSubscribed, "22 Hz asked of a 14 Hz adapter")
    }
}
