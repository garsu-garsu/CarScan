package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.obd.poll.PollerHealth
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * `PollerHealth` and `SessionHealth` mirror each other field for field, deliberately: :core:data
 * declares the port and cannot see :core:obd. That makes this mapping the one place the two can
 * drift apart — and a field silently mapped to the wrong one is a number on screen that is wrong
 * in a way nobody notices, because it is still plausible.
 */
class SessionHealthMappingTest {

    @Test
    fun `every field crosses the boundary, and none of them swap`() {
        val poller = PollerHealth(
            capacityHz = 14.0,
            loadHz = 22.5,
            meanRttMs = 71.4,
            dropRatePct = 12.5,
            stretchedCount = 3,
        )

        val health = poller.asSessionHealth(ConnectionState.CONNECTED)

        health.connection shouldBe ConnectionState.CONNECTED
        health.capacityHz shouldBe 14.0
        health.loadHz shouldBe 22.5
        health.meanRttMs shouldBe 71.4
        health.dropRatePct shouldBe 12.5
        health.stretchedCount shouldBe 3
    }

    /**
     * The connection state is not in `PollerHealth` — the poller has no idea whether anything is
     * connected — so it has to come from the connector. A mapping that hard-coded it would leave
     * the connect screen claiming CONNECTED while the adapter was unplugged.
     */
    @Test
    fun `the connection state is the connector's, not the poller's`() {
        PollerHealth().asSessionHealth(ConnectionState.DISCONNECTED).connection shouldBe
            ConnectionState.DISCONNECTED
        PollerHealth().asSessionHealth(ConnectionState.CONNECTING).connection shouldBe
            ConnectionState.CONNECTING
    }
}
