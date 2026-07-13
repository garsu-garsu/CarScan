package com.bruni.carscan.core.transport.ble

import com.juul.kable.State
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test

class BleConnectionStateTest {

    /** Kable hands the connection scope out with the state; nothing here looks at it. */
    private fun connected() = State.Connected(CoroutineScope(Job()))

    /**
     * Kable reports three distinct rungs while a link is coming up (radio, service
     * discovery, subscription). None of them is usable, so all three collapse to one
     * state the UI can render.
     */
    @Test
    fun `every connecting rung maps to Connecting`() {
        asConnectionState(State.Connecting.Bluetooth) shouldBe BleConnectionState.Connecting
        asConnectionState(State.Connecting.Services) shouldBe BleConnectionState.Connecting
        asConnectionState(State.Connecting.Observes) shouldBe BleConnectionState.Connecting
    }

    @Test
    fun `Connected maps to Connected`() {
        asConnectionState(connected()) shouldBe BleConnectionState.Connected
    }

    @Test
    fun `Disconnecting maps to Disconnecting`() {
        asConnectionState(State.Disconnecting) shouldBe BleConnectionState.Disconnecting
    }

    /** Disconnected carries a status — a lost link and a clean close are the same state. */
    @Test
    fun `Disconnected maps to Disconnected regardless of status`() {
        asConnectionState(State.Disconnected()) shouldBe BleConnectionState.Disconnected
        asConnectionState(State.Disconnected(State.Disconnected.Status.PeripheralDisconnected)) shouldBe
            BleConnectionState.Disconnected
        asConnectionState(State.Disconnected(State.Disconnected.Status.Timeout)) shouldBe
            BleConnectionState.Disconnected
    }
}
