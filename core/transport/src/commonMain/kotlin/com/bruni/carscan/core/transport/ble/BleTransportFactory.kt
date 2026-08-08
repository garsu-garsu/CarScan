package com.bruni.carscan.core.transport.ble

import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.ObdTransport
import com.bruni.carscan.core.transport.TransportFactory
import com.bruni.carscan.core.transport.TransportKind
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.PlatformAdvertisement
import com.juul.kable.Scanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Discovers and connects BLE adapters. Works on both platforms; on iOS it is the *only*
 * Bluetooth that works at all.
 */
class BleTransportFactory(
    private val scanner: Scanner<PlatformAdvertisement> = Scanner(),
    /** How long [create]'s transport will look for the target before giving up. */
    private val rediscoveryTimeout: Duration = 30.seconds,
) : TransportFactory {

    override val supported: Set<TransportKind> = setOf(TransportKind.BLE)

    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> {
        require(kind == TransportKind.BLE) { "BleTransportFactory only discovers BLE, not $kind." }
        return scanner.advertisements.map { it.asDiscoveredAdapter() }
    }

    override fun create(target: DiscoveredAdapter): ObdTransport = create(target, savedProfile = null)

    /**
     * As [create], but reusing a GATT layout already learned from this adapter.
     *
     * The frozen [TransportFactory] contract has nowhere to carry one, and the layout is
     * per-adapter state that lives in the database, so it comes in through this overload.
     */
    fun create(target: DiscoveredAdapter, savedProfile: GattProfile?): ObdTransport {
        require(target.kind == TransportKind.BLE) { "Not a BLE adapter: $target" }

        // Kable's common API can only build a Peripheral from an Advertisement — an address
        // alone is not enough, because CoreBluetooth hands out object references, not MACs.
        // So a connect always starts with a scan for the target. It is fast (adapters
        // advertise several times a second) and it is the honest shape of BLE.
        return BleObdTransport(savedProfile) { Peripheral(awaitAdvertisement(target.address)) }
    }

    private suspend fun awaitAdvertisement(address: String): Advertisement =
        withTimeout(rediscoveryTimeout) {
            scanner.advertisements.first { it.identifier.toString() == address }
        }
}

internal fun Advertisement.asDiscoveredAdapter() = DiscoveredAdapter(
    kind = TransportKind.BLE,
    // Android gives a MAC here; iOS gives CoreBluetooth's peripheral UUID. Both are stable
    // for the lifetime of the pairing, which is all a reconnect needs.
    address = identifier.toString(),
    name = name ?: peripheralName,
    rssi = rssi,
)
