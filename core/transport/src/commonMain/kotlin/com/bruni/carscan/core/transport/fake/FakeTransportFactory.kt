package com.bruni.carscan.core.transport.fake

import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.ObdTransport
import com.bruni.carscan.core.transport.TransportFactory
import com.bruni.carscan.core.transport.TransportKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [TransportFactory] the app binds behind a debug flag, so the whole product runs
 * with no car and no adapter: discovery finds three plausible fakes, and connecting to
 * any of them hands back a live [ElmEmulator].
 */
class FakeTransportFactory(
    override val supported: Set<TransportKind> =
        setOf(TransportKind.BLE, TransportKind.SPP, TransportKind.WIFI),
    private val adapters: List<DiscoveredAdapter> = DEFAULT_ADAPTERS,
    private val clock: ElmClock = ElmClock.monotonic(),
    private val config: ElmEmulatorConfig = ElmEmulatorConfig(),
    private val vehicle: VehicleStateSource = DrivingSimulator(),
    /** Fakes appear one at a time, the way a real scan trickles them in. */
    private val discoveryDelay: Duration = 300.milliseconds,
) : TransportFactory {

    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> = flow {
        for (adapter in adapters.filter { it.kind == kind }) {
            delay(discoveryDelay)
            emit(adapter)
        }
    }

    override fun create(target: DiscoveredAdapter): ObdTransport =
        ElmEmulator(clock = clock, config = config, vehicle = vehicle)

    companion object {
        val DEFAULT_ADAPTERS: List<DiscoveredAdapter> = listOf(
            DiscoveredAdapter(TransportKind.BLE, "AA:BB:CC:DD:EE:01", "OBDII", rssi = -55),
            DiscoveredAdapter(TransportKind.BLE, "AA:BB:CC:DD:EE:02", "Vgate iCar Pro", rssi = -71),
            DiscoveredAdapter(TransportKind.SPP, "AA:BB:CC:DD:EE:03", "OBDII"),
            DiscoveredAdapter(TransportKind.WIFI, "192.168.0.10:35000", "V-LINK"),
        )
    }
}
