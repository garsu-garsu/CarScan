package com.bruni.carscan.core.transport.spp

import android.content.Context
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.ObdTransport
import com.bruni.carscan.core.transport.TransportFactory
import com.bruni.carscan.core.transport.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * Discovers and creates Bluetooth Classic transports.
 *
 * Discovery is **bonded devices only**. That is not a shortcut: an ELM327 clone is paired
 * in system Bluetooth settings with PIN 1234 or 0000, and pairing is where a scan would
 * have to end up anyway. Listing bonded devices needs no scan, no location permission and
 * no `BroadcastReceiver`, and it works while a scan is impossible. An in-app scan can be
 * added later for the rare unpaired adapter, and it changes nothing here.
 *
 * Bonded devices are returned unfiltered — headphones and all. Guessing which MAC is an
 * OBD adapter from its name hides the one adapter that named itself something else, and
 * the user knows which one they plugged into the car.
 */
class SppTransportFactory internal constructor(
    private val host: BluetoothHost,
) : TransportFactory {

    constructor(context: Context) : this(AndroidBluetoothHost(context))

    override val supported: Set<TransportKind> = setOf(TransportKind.SPP)

    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> {
        require(kind == TransportKind.SPP) { "SppTransportFactory discovers SPP adapters, not $kind" }
        if (!host.isEnabled()) throw SppBluetoothOffException()

        val bonded = try {
            host.bondedDevices()
        } catch (e: SecurityException) {
            throw SppPermissionDeniedException(e)
        }
        return bonded.asFlow()
    }

    override fun create(target: DiscoveredAdapter): ObdTransport {
        require(target.kind == TransportKind.SPP) { "SppTransportFactory creates SPP transports, not ${target.kind}" }
        return SppObdTransport(target.address, host)
    }
}
