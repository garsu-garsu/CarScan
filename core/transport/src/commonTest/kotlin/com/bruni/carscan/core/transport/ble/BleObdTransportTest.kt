package com.bruni.carscan.core.transport.ble

import app.cash.turbine.test
import com.juul.kable.WriteType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class BleObdTransportTest {

    private fun transport(peripheral: FakePeripheral, saved: GattProfile? = null) =
        BleObdTransport(savedProfile = saved) { peripheral }

    /**
     * The bug this exists to prevent, and it is BLE-only.
     *
     * A peripheral sends nothing until its CCCD descriptor is written, and Kable writes it
     * when a collector attaches to `observe()`. So if `incoming` is cold and ElmSession does
     * the natural thing — `open()`, `write("ATZ")`, *then* collect — the adapter is still mute
     * when the command goes out, and the reply is never transmitted at all. Intermittent,
     * silent, and only on Bluetooth. TCP does not have this problem: a socket buffers whether
     * or not anyone is reading, so the transport must behave the same way.
     */
    @Test
    fun `a reply that arrives before the consumer collects is not lost`() = runTest {
        val peripheral = FakePeripheral(ffe0Services())
        val transport = transport(peripheral)

        transport.open()
        transport.write("ATZ\r".encodeToByteArray())
        peripheral.notify("ELM327 v1.5\r>".encodeToByteArray())

        // Only now does the consumer show up.
        transport.incoming.test {
            awaitItem().decodeToString() shouldBe "ELM327 v1.5\r>"
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The same fact, stated directly: by the time open() returns, the adapter can talk. */
    @Test
    fun `open subscribes to notifications before it returns`() = runTest {
        val peripheral = FakePeripheral(ffe0Services())

        transport(peripheral).open()

        peripheral.observationsStarted shouldBe 1
    }

    @Test
    fun `open resolves the adapter's profile and publishes it for persisting`() = runTest {
        val peripheral = FakePeripheral(ffe0Services())
        val transport = transport(peripheral)

        transport.open()

        transport.profile.value shouldBe GattProfile(
            service = bluetoothUuidOf("FFE0"),
            write = bluetoothUuidOf("FFE1"),
            notify = bluetoothUuidOf("FFE1"),
            writeWithResponse = false,
        )
    }

    @Test
    fun `a long command is split into ATT-sized writes of the resolved type`() = runTest {
        val peripheral = FakePeripheral(ffe0Services())
        val transport = transport(peripheral)

        transport.open()
        transport.write(ByteArray(60))

        peripheral.writes.map { it.first.size } shouldBe listOf(20, 20, 20)
        peripheral.writes.map { it.second } shouldBe
            List(3) { WriteType.WithoutResponse }
    }

    /** A negotiated MTU is a throughput win, never a correctness one — but it must be used. */
    @Test
    fun `a negotiated MTU widens the writes`() = runTest {
        val peripheral = FakePeripheral(ffe0Services(), maxWriteLength = 244)
        val transport = transport(peripheral)

        transport.open()
        transport.write(ByteArray(60))

        peripheral.writes.map { it.first.size } shouldBe listOf(60)
    }

    @Test
    fun `close disconnects once, however many times it is called`() = runTest {
        val peripheral = FakePeripheral(ffe0Services())
        val transport = transport(peripheral)

        transport.open()
        transport.close()
        transport.close()
        transport.close()

        peripheral.disconnects shouldBe 1
        peripheral.closes shouldBe 1
    }

    @Test
    fun `a peripheral with nothing to say is rejected at open, not at the first read`() = runTest {
        val transport = transport(FakePeripheral(muteServices()))

        shouldThrow<NoUsableGattProfileException> { transport.open() }
    }

    /** A profile learned last time skips resolution — and must still be the one written to. */
    @Test
    fun `a saved profile is used verbatim`() = runTest {
        val saved = GattProfile(
            service = bluetoothUuidOf("FFE0"),
            write = bluetoothUuidOf("FFE1"),
            notify = bluetoothUuidOf("FFE1"),
            writeWithResponse = true,
        )
        val peripheral = FakePeripheral(ffe0Services())
        val transport = transport(peripheral, saved = saved)

        transport.open()
        transport.write(ByteArray(1))

        transport.profile.value shouldBe saved
        peripheral.writes.single().second shouldBe WriteType.WithResponse
    }
}
