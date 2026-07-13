package com.bruni.carscan.core.transport.ble

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ResolveProfileTest {

    /**
     * The one people get wrong: on the cheap FFE0 clones the *same* characteristic is
     * both the write sink and the notify source. A resolver that looks for two distinct
     * characteristics finds nothing and falls through to a heuristic — or to null.
     */
    @Test
    fun `FFE0 clone writes and notifies on the same characteristic`() {
        val services = listOf(
            genericAccess(),
            svc("FFE0", chr("FFE1", writeWithoutResponse = true, notify = true)),
        )

        val profile = resolveProfile(services)

        profile shouldBe GattProfile(
            service = bluetoothUuidOf("FFE0"),
            write = bluetoothUuidOf("FFE1"),
            notify = bluetoothUuidOf("FFE1"),
            writeWithResponse = false,
        )
    }

    @Test
    fun `vLinker FFF0 splits write FFF2 from notify FFF1`() {
        val services = listOf(
            svc(
                "FFF0",
                chr("FFF1", notify = true),
                chr("FFF2", writeWithoutResponse = true, write = true),
            ),
            deviceInformation(),
        )

        val profile = resolveProfile(services)

        profile shouldBe GattProfile(
            service = bluetoothUuidOf("FFF0"),
            write = bluetoothUuidOf("FFF2"),
            notify = bluetoothUuidOf("FFF1"),
            writeWithResponse = false,
        )
    }

    @Test
    fun `Nordic UART resolves by its 128-bit uuids`() {
        val services = listOf(
            svc(
                "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
                chr("6E400002-B5A3-F393-E0A9-E50E24DCCA9E", writeWithoutResponse = true),
                chr("6E400003-B5A3-F393-E0A9-E50E24DCCA9E", notify = true),
            ),
        )

        val profile = resolveProfile(services)

        profile shouldBe GattProfile(
            service = bluetoothUuidOf("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            write = bluetoothUuidOf("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
            notify = bluetoothUuidOf("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
            writeWithResponse = false,
        )
    }

    /** OBDLink is the one that wants an acknowledged write. */
    @Test
    fun `OBDLink 18F0 asks for write-with-response`() {
        val services = listOf(
            svc(
                "18F0",
                chr("2AF0", notify = true),
                chr("2AF1", write = true, writeWithoutResponse = true),
            ),
        )

        val profile = resolveProfile(services)

        profile shouldBe GattProfile(
            service = bluetoothUuidOf("18F0"),
            write = bluetoothUuidOf("2AF1"),
            notify = bluetoothUuidOf("2AF0"),
            writeWithResponse = true,
        )
    }

    /**
     * The table states a *preference*, not a fact. If this adapter's 2AF1 only accepts
     * unacknowledged writes, honouring the table's `writeWithResponse = true` makes every
     * write fail. The declared preference must be reconciled with the real properties.
     */
    @Test
    fun `declared write type yields to what the characteristic actually supports`() {
        val services = listOf(
            svc(
                "18F0",
                chr("2AF0", notify = true),
                chr("2AF1", writeWithoutResponse = true),
            ),
        )

        resolveProfile(services)?.writeWithResponse shouldBe false
    }

    @Test
    fun `unknown service falls back to the first with both a writable and a notifiable characteristic`() {
        val services = listOf(
            genericAccess(),
            deviceInformation(),
            svc(
                "0000ABCD-0000-1000-8000-00805F9B34FB",
                chr("0000ABCE-0000-1000-8000-00805F9B34FB", write = true),
                chr("0000ABCF-0000-1000-8000-00805F9B34FB", indicate = true),
            ),
        )

        val profile = resolveProfile(services)

        profile shouldBe GattProfile(
            service = bluetoothUuidOf("0000ABCD-0000-1000-8000-00805F9B34FB"),
            write = bluetoothUuidOf("0000ABCE-0000-1000-8000-00805F9B34FB"),
            notify = bluetoothUuidOf("0000ABCF-0000-1000-8000-00805F9B34FB"),
            writeWithResponse = true,
        )
    }

    /** A known layout must win even when an earlier service would satisfy the heuristic. */
    @Test
    fun `known profile beats an earlier heuristic candidate`() {
        val services = listOf(
            svc("FEE7", chr("FEC7", write = true), chr("FEC8", indicate = true)),
            svc("FFE0", chr("FFE1", writeWithoutResponse = true, notify = true)),
        )

        resolveProfile(services)?.service shouldBe bluetoothUuidOf("FFE0")
    }

    @Test
    fun `no service with both a writable and a notifiable characteristic resolves to null`() {
        val services = listOf(
            genericAccess(),
            deviceInformation(),
            svc("FFE0", chr("FFE1", notify = true)),
            svc("FFF0", chr("FFF2", write = true)),
        )

        resolveProfile(services).shouldBeNull()
    }

    @Test
    fun `empty service list resolves to null`() {
        resolveProfile(emptyList()).shouldBeNull()
    }
}
