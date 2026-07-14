package com.bruni.carscan.feature.connect

import com.bruni.carscan.core.data.AdapterQuirks
import com.bruni.carscan.core.data.AdapterRepository
import com.bruni.carscan.core.data.AdapterSummary
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectOutcome
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import com.bruni.carscan.core.transport.fake.FakeTransportFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * The port, faked.
 *
 * Discovery is delegated to the real [FakeTransportFactory], so adapters arrive the way a scan
 * actually delivers them — one at a time, not as a batch. [connect] is scripted, because the
 * ELM327 ladder behind the real connector lives in :core:obd and no feature module can see it.
 */
class FakeObdConnector(
    override val supported: Set<TransportKind> =
        setOf(TransportKind.BLE, TransportKind.SPP, TransportKind.WIFI),
    private val outcome: (DiscoveredAdapter, AdapterQuirks?) -> ConnectOutcome,
) : ObdConnector {

    private val transports = FakeTransportFactory(supported = supported)

    val calls = mutableListOf<Pair<DiscoveredAdapter, AdapterQuirks?>>()

    /** Set to make a scan of this transport blow up, the way a refused permission does. */
    var discoveryFailsOn: TransportKind? = null

    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> =
        if (kind == discoveryFailsOn) {
            flow { throw IllegalStateException("scan refused") }
        } else {
            transports.discover(kind)
        }

    override suspend fun connect(target: DiscoveredAdapter, remembered: AdapterQuirks?): ConnectOutcome {
        calls += target to remembered
        return outcome(target, remembered)
    }

    override suspend fun disconnect() = Unit

    companion object {
        val ALL_THREE = setOf(TransportKind.BLE, TransportKind.SPP, TransportKind.WIFI)

        fun readyWith(summary: AdapterSummary, supported: Set<TransportKind> = ALL_THREE) =
            FakeObdConnector(supported) { _, _ -> ConnectOutcome.Ready(summary) }

        fun failingWith(reason: ConnectFailure) =
            FakeObdConnector { _, _ -> ConnectOutcome.Failed(reason) }
    }
}

class FakeAdapterRepository(seed: List<AdapterQuirks> = emptyList()) : AdapterRepository {
    private val rows = seed.associateBy { it.address }.toMutableMap()

    override suspend fun remember(quirks: AdapterQuirks) {
        rows[quirks.address] = quirks
    }

    override suspend fun recall(address: String): AdapterQuirks? = rows[address]

    override suspend fun all(): List<AdapterQuirks> = rows.values.toList()

    override suspend fun updateRtt(address: String, ewmaRttMs: Double, atMs: Long) {
        rows[address] = rows.getValue(address).copy(ewmaRttMs = ewmaRttMs, lastUsedMs = atMs)
    }

    override suspend fun forget(address: String) {
        rows -= address
    }
}

/** Only [health] matters to this screen; the sample streams are here to satisfy the port. */
class FakeSessionRepository(initial: SessionHealth = SessionHealth()) : VehicleSessionRepository {
    private val _health = MutableStateFlow(initial)
    override val health: StateFlow<SessionHealth> = _health.asStateFlow()

    override val latest: StateFlow<Map<MetricKey, SensorSample>> = MutableStateFlow(emptyMap())
    override val samples: SharedFlow<SensorSample> = MutableSharedFlow()

    fun emit(health: SessionHealth) {
        _health.value = health
    }
}

/** A healthy-looking clone: not an STN, but it connected and it answers. */
fun summary(
    address: String = "AA:BB:CC:DD:EE:01",
    isStn: Boolean = false,
    supportsExpectedFrames: Boolean = true,
    protocolNum: Int? = 6,
    rttMs: Long = 70,
) = AdapterSummary(
    identity = "ELM327 v1.5",
    isStn = isStn,
    protocolNum = protocolNum,
    rttMs = rttMs,
    quirks = quirks(
        address = address,
        isStn = isStn,
        supportsExpectedFrames = supportsExpectedFrames,
        protocolNum = protocolNum?.toLong(),
        ewmaRttMs = rttMs.toDouble(),
    ),
)

fun quirks(
    address: String,
    isStn: Boolean = false,
    supportsExpectedFrames: Boolean = true,
    protocolNum: Long? = 6L,
    ewmaRttMs: Double? = 70.0,
    lastUsedMs: Long? = null,
) = AdapterQuirks(
    address = address,
    kind = "BLE",
    name = "OBDII",
    gattService = "0000fff0-0000-1000-8000-00805f9b34fb",
    gattWrite = "0000fff2-0000-1000-8000-00805f9b34fb",
    gattNotify = "0000fff1-0000-1000-8000-00805f9b34fb",
    isStn = isStn,
    supportsExpectedFrames = supportsExpectedFrames,
    echoSuppressionNeeded = false,
    maxWriteChunk = 20,
    protocolNum = protocolNum,
    ewmaRttMs = ewmaRttMs,
    lastUsedMs = lastUsedMs,
)
