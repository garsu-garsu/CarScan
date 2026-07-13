package com.bruni.carscan.core.transport.ble

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class BluetoothUuidTest {

    /**
     * Adapters advertise `FFE0`; GATT works in 128 bits. Comparing the short form against
     * a discovered UUID never matches, so the lookup table would silently miss every
     * adapter it names.
     */
    @Test
    fun `a 16-bit alias expands against the Bluetooth base uuid`() {
        bluetoothUuidOf("FFE0").toString() shouldBe "0000ffe0-0000-1000-8000-00805f9b34fb"
    }

    @Test
    fun `a 32-bit alias expands against the Bluetooth base uuid`() {
        bluetoothUuidOf("0000FFE0").toString() shouldBe "0000ffe0-0000-1000-8000-00805f9b34fb"
    }

    @Test
    fun `a full uuid passes through, case-insensitively`() {
        bluetoothUuidOf("6E400001-B5A3-F393-E0A9-E50E24DCCA9E") shouldBe
            bluetoothUuidOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    }

    @Test
    fun `a profile round-trips through the strings the adapter table stores`() {
        val profile = GattProfile(
            service = bluetoothUuidOf("FFE0"),
            write = bluetoothUuidOf("FFE1"),
            notify = bluetoothUuidOf("FFE1"),
            writeWithResponse = false,
        )

        val restored = GattProfile(
            service = bluetoothUuidOf(profile.service.toString()),
            write = bluetoothUuidOf(profile.write.toString()),
            notify = bluetoothUuidOf(profile.notify.toString()),
            writeWithResponse = profile.writeWithResponse,
        )

        restored shouldBe profile
    }

    @Test
    fun `garbage is rejected rather than silently mangled`() {
        shouldThrowAny { bluetoothUuidOf("not-a-uuid") }
    }
}
