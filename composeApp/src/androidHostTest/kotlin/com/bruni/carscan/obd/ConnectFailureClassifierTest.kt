package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ConnectException
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectOutcome
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import com.bruni.carscan.core.transport.spp.SppBluetoothOffException
import com.bruni.carscan.core.transport.spp.SppConnectAttempt
import com.bruni.carscan.core.transport.spp.SppConnectFailedException
import com.bruni.carscan.core.transport.spp.SppConnectRung
import com.bruni.carscan.core.transport.spp.SppPermissionDeniedException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test

/**
 * The platform exceptions are JVM types living in :core:transport's `androidMain`. A commonMain
 * ViewModel cannot catch them or even *name* them, so the classification has to happen here, at
 * the edge, and what crosses into common code is [ConnectFailure].
 *
 * Get this wrong and the app's most common first-run failure — a refused Bluetooth permission —
 * is reported as `ADAPTER_UNREACHABLE`, which tells the user *"the adapter did not answer, check
 * that it is plugged in"* and sends them out to their car to jiggle a dongle. The fix was a
 * settings button already on the screen. `BLUETOOTH_PERMISSION` is the one failure with a
 * working remedy attached, and these six tests are what keep it attached.
 */
class ConnectFailureClassifierTest {

    @Test
    fun `SppPermissionDeniedException is a permission problem`() {
        SppPermissionDeniedException().asConnectFailure(TransportKind.SPP) shouldBe
            ConnectFailure.BLUETOOTH_PERMISSION
    }

    /**
     * **This is what Kable actually throws on a refused `BLUETOOTH_SCAN`** — a bare
     * `SecurityException`, not anything of ours. Without this branch the BLE path falls through
     * to the fallback and reports `ADAPTER_UNREACHABLE` for a permission the user simply denied.
     */
    @Test
    fun `a bare SecurityException is a permission problem`() {
        SecurityException("Need android.permission.BLUETOOTH_SCAN")
            .asConnectFailure(TransportKind.BLE) shouldBe ConnectFailure.BLUETOOTH_PERMISSION
    }

    @Test
    fun `SppBluetoothOffException is the radio being off`() {
        SppBluetoothOffException().asConnectFailure(TransportKind.SPP) shouldBe
            ConnectFailure.BLUETOOTH_OFF
    }

    /** All three rungs of the secure → insecure → reflection ladder failed. Clones want pairing. */
    @Test
    fun `SppConnectFailedException asks for pairing`() {
        val failed = SppConnectFailedException(
            address = "AA:BB:CC:DD:EE:03",
            attempts = listOf(SppConnectAttempt(SppConnectRung.SECURE, IOException("read failed"))),
        )

        failed.asConnectFailure(TransportKind.SPP) shouldBe ConnectFailure.SPP_PAIRING_REQUIRED
    }

    /**
     * A Wi-Fi adapter is its own access point. "Cannot reach it" nearly always means the phone is
     * still on some other network — which is a thing the user can fix, and `ADAPTER_UNREACHABLE`
     * ("check it is plugged in") is not.
     */
    @Test
    fun `an unrecognised failure on Wi-Fi means the phone is on the wrong network`() {
        IOException("Connection refused").asConnectFailure(TransportKind.WIFI) shouldBe
            ConnectFailure.WIFI_NOT_JOINED
    }

    @Test
    fun `an unrecognised failure elsewhere means nothing answered`() {
        IOException("Connection refused").asConnectFailure(TransportKind.BLE) shouldBe
            ConnectFailure.ADAPTER_UNREACHABLE
    }

    /**
     * A scan that is refused must reach the screen as `BLUETOOTH_PERMISSION`, not as the
     * `ADAPTER_UNREACHABLE` the connect screen would otherwise have to guess from the transport
     * kind alone. `discover()` therefore runs its flow through the same classifier as `connect()`.
     */
    @Test
    fun `a refused scan surfaces ConnectException(BLUETOOTH_PERMISSION)`() = runTest {
        val connector = ElmObdConnector(
            transports = ThrowingTransports(SecurityException("BLUETOOTH_SCAN denied")),
            signalsets = signalsetOf(null),
            scope = backgroundScope,
        )

        val thrown = shouldThrow<ConnectException> {
            connector.discover(TransportKind.BLE).toList()
        }

        thrown.reason shouldBe ConnectFailure.BLUETOOTH_PERMISSION
    }

    /** And the same exception on the connect path, so the two cannot disagree. */
    @Test
    fun `a refused connect surfaces BLUETOOTH_PERMISSION`() = runTest {
        val connector = ElmObdConnector(
            transports = ThrowingTransports(SppPermissionDeniedException()),
            signalsets = signalsetOf(null),
            scope = backgroundScope,
        )

        val outcome = connector
            .connect(DiscoveredAdapter(TransportKind.SPP, "AA:BB:CC:DD:EE:03"))
            .shouldBeInstanceOf<ConnectOutcome.Failed>()

        outcome.reason shouldBe ConnectFailure.BLUETOOTH_PERMISSION
    }
}
