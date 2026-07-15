package com.bruni.carscan.obd

import com.bruni.carscan.core.data.AdapterQuirks
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.ObdTransport
import com.bruni.carscan.core.transport.TransportFactory
import com.bruni.carscan.core.transport.TransportKind
import com.bruni.carscan.core.transport.ble.BleObdTransport
import com.bruni.carscan.core.transport.ble.BleTransportFactory
import com.bruni.carscan.core.transport.ble.GattProfile
import com.bruni.carscan.core.transport.ble.bluetoothUuidOf
import com.bruni.carscan.core.transport.tcp.TcpObdTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.uuid.ExperimentalUuidApi

/**
 * A transport, plus the one thing that can only be known *after* it is open.
 *
 * [learnedProfile] is the GATT layout the BLE transport ended up using. Null on the other two
 * transports, and null on BLE until [ObdTransport.open] has returned.
 */
class OpenTransport(
    val transport: ObdTransport,
    val learnedProfile: () -> GattProfile?,
)

/**
 * The transports this platform actually has, behind the one call the frozen [TransportFactory]
 * contract cannot make.
 *
 * `TransportFactory.create(target)` has nowhere to carry a remembered GATT layout, and no way to
 * hand back the one it resolved. Both of those are per-adapter state that belongs in the database,
 * so they come through here instead — and this interface is also the seam the connector's tests
 * substitute an [com.bruni.carscan.core.transport.fake.ElmEmulator] at.
 */
interface Transports {
    val supported: Set<TransportKind>

    fun discover(kind: TransportKind): Flow<DiscoveredAdapter>

    fun open(target: DiscoveredAdapter, remembered: AdapterQuirks?): OpenTransport
}

/**
 * Routes each [TransportKind] to the factory that can do it.
 *
 * [spp] is null on iOS and only there: Apple's ExternalAccessory framework reaches only
 * MFi-certified hardware and no ELM327 clone is MFi, so Bluetooth Classic is not a gap to be
 * closed later — it does not exist. [supported] is derived from what was actually supplied, which
 * is why no screen anywhere in the app branches on the platform.
 */
class DefaultTransports(
    private val ble: BleTransportFactory,
    private val spp: TransportFactory?,
) : Transports {

    override val supported: Set<TransportKind> = buildSet {
        add(TransportKind.BLE)
        if (spp != null) add(TransportKind.SPP)
        add(TransportKind.WIFI)
    }

    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> = when (kind) {
        TransportKind.BLE -> ble.discover(kind)
        TransportKind.SPP -> requireSpp().discover(kind)
        // A Wi-Fi adapter is its own access point on a fixed address; there is nothing to scan
        // for. Rediscovering it would mean probing a subnet, which is slower and less reliable
        // than the one address every one of these devices ships with.
        TransportKind.WIFI -> wifiCandidates()
    }

    override fun open(target: DiscoveredAdapter, remembered: AdapterQuirks?): OpenTransport =
        when (target.kind) {
            TransportKind.BLE -> {
                // The overload, not the interface method. There is no standard GATT layout for an
                // ELM327 over BLE — vendors used whatever serial profile their radio module
                // shipped with — so resolving it costs a connect and a full service discovery,
                // and paying that twice for the same adapter is pure waste.
                //
                // The overload also returns the concrete type, which is the only way to read back
                // the layout it settled on. `TransportFactory` cannot express either half.
                val transport = ble.create(target, remembered?.gattProfile()) as BleObdTransport
                OpenTransport(transport) { transport.profile.value }
            }

            TransportKind.SPP -> OpenTransport(requireSpp().create(target)) { null }

            TransportKind.WIFI -> {
                val (host, port) = target.address.hostAndPort()
                OpenTransport(TcpObdTransport(host, port)) { null }
            }
        }

    private fun requireSpp(): TransportFactory = requireNotNull(spp) {
        "Bluetooth Classic does not exist on this platform. Nothing should have offered it: the " +
            "picker is built from ObdConnector.supported."
    }
}

/** Every one of these adapters ships on this address, and almost none of them let you change it. */
private fun wifiCandidates(): Flow<DiscoveredAdapter> = flowOf(
    DiscoveredAdapter(TransportKind.WIFI, DEFAULT_WIFI_ADDRESS, "Wi-Fi OBD adapter"),
)

const val DEFAULT_WIFI_ADDRESS = "192.168.0.10:35000"

private fun String.hostAndPort(): Pair<String, Int> {
    val host = substringBeforeLast(':')
    val port = substringAfterLast(':').toIntOrNull()
    require(host.isNotEmpty() && port != null) { "Not a host:port address: $this" }
    return host to port
}

/**
 * The learned GATT layout, rebuilt from the three columns the `adapter` table stores it in.
 *
 * `writeWithResponse` is **inferred**, because the table has no column for it. It is not a free
 * choice: an adapter whose write characteristic only advertises `writeWithoutResponse` rejects
 * every acknowledged write, and vice versa — get it backwards and *no command reaches the
 * adapter*. Of every BLE layout in `KnownGattProfiles`, exactly one wants acknowledged writes —
 * OBDLink's `18F0` — so the service UUID determines it. If a second such adapter ever appears,
 * this stops being inferable and the table needs the column.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun AdapterQuirks.gattProfile(): GattProfile? {
    val service = gattService ?: return null
    val write = gattWrite ?: return null
    val notify = gattNotify ?: return null

    val serviceUuid = bluetoothUuidOf(service)
    return GattProfile(
        service = serviceUuid,
        write = bluetoothUuidOf(write),
        notify = bluetoothUuidOf(notify),
        writeWithResponse = serviceUuid == bluetoothUuidOf(ACKNOWLEDGED_WRITE_SERVICE),
    )
}

/** OBDLink CX / MX+ — the one adapter that wants its writes acknowledged. */
private const val ACKNOWLEDGED_WRITE_SERVICE = "18F0"
