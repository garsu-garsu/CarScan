package com.bruni.carscan.core.transport.spp

import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

private val OBDII = DiscoveredAdapter(TransportKind.SPP, "00:1D:A5:68:98:8B", "OBDII")
private val HEADSET = DiscoveredAdapter(TransportKind.SPP, "AA:BB:CC:DD:EE:FF", "WH-1000XM4")

class SppTransportFactoryTest {

    @Test
    fun `it supports Bluetooth Classic and nothing else`() {
        SppTransportFactory(FakeBluetoothHost()).supported shouldBe setOf(TransportKind.SPP)
    }

    @Test
    fun `discover lists bonded devices, with no scan running`() = runTest {
        // Bonded-only is the real-world case: users pair an ELM327 in system settings
        // (PIN 1234 / 0000) and never touch an in-app scan.
        val factory = SppTransportFactory(FakeBluetoothHost(bonded = listOf(OBDII, HEADSET)))

        factory.discover(TransportKind.SPP).toList() shouldContainExactly listOf(OBDII, HEADSET)
    }

    @Test
    fun `discover rejects kinds it does not own`() {
        val factory = SppTransportFactory(FakeBluetoothHost())

        assertFailsWith<IllegalArgumentException> { factory.discover(TransportKind.BLE) }
        assertFailsWith<IllegalArgumentException> { factory.discover(TransportKind.WIFI) }
    }

    @Test
    fun `discover with Bluetooth off says so`() = runTest {
        val factory = SppTransportFactory(FakeBluetoothHost(enabled = false))

        assertFailsWith<SppBluetoothOffException> { factory.discover(TransportKind.SPP).toList() }
    }

    @Test
    fun `discover without BLUETOOTH_CONNECT reports the permission, not a SecurityException`() = runTest {
        // Reading bondedDevices without the permission throws SecurityException, which
        // would reach the UI as an unexplained crash.
        val factory = SppTransportFactory(
            FakeBluetoothHost(bondedFailure = SecurityException("Need BLUETOOTH_CONNECT permission")),
        )

        assertFailsWith<SppPermissionDeniedException> { factory.discover(TransportKind.SPP).toList() }
    }

    @Test
    fun `create rejects targets it does not own`() {
        val factory = SppTransportFactory(FakeBluetoothHost())

        assertFailsWith<IllegalArgumentException> {
            factory.create(DiscoveredAdapter(TransportKind.WIFI, "192.168.0.10:35000"))
        }
    }

    @Test
    fun `create builds an SPP transport for the target`() {
        val factory = SppTransportFactory(FakeBluetoothHost())

        (factory.create(OBDII) is SppObdTransport) shouldBe true
    }
}
