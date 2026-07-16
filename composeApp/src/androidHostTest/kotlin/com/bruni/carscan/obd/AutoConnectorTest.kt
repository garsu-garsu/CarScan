package com.bruni.carscan.obd

import com.bruni.carscan.core.data.AdapterQuirks
import com.bruni.carscan.core.data.AdapterRepository
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectOutcome
import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.SampleSource
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One attempt at launch, and only when there is something real to try: a saved adapter, the
 * preference on, and no session already up. Everything else is the manual picker's job.
 */
class AutoConnectorTest {

    private val quirks = AdapterQuirks(
        address = "AA:BB:CC:DD:EE:01",
        kind = "BLE",
        name = "OBDII",
        gattService = "0000fff0-0000-1000-8000-00805f9b34fb",
        gattWrite = "0000fff2-0000-1000-8000-00805f9b34fb",
        gattNotify = "0000fff1-0000-1000-8000-00805f9b34fb",
        isStn = false,
        supportsExpectedFrames = true,
        echoSuppressionNeeded = false,
        maxWriteChunk = 20,
        protocolNum = 6,
        ewmaRttMs = 70.0,
        lastUsedMs = 9_000,
    )

    @Test
    fun `auto-connects to the last adapter when enabled and disconnected`() = runTest {
        val connector = FakeObdConnector()
        val autoConnector = AutoConnector(
            connector = connector,
            source = FakeConnectionSource(ConnectionState.DISCONNECTED),
            adapters = FakeAdapterRepository(quirks),
            settings = FakeAutoSettings(autoReconnect = true),
        )

        autoConnector.start(this)
        runCurrent()

        assertEquals(1, connector.calls.size)
        val (target, remembered) = connector.calls.single()
        assertEquals(DiscoveredAdapter(TransportKind.BLE, quirks.address, quirks.name), target)
        assertEquals(quirks, remembered)
    }

    @Test
    fun `does nothing when autoReconnect is off`() = runTest {
        val connector = FakeObdConnector()
        val autoConnector = AutoConnector(
            connector = connector,
            source = FakeConnectionSource(ConnectionState.DISCONNECTED),
            adapters = FakeAdapterRepository(quirks),
            settings = FakeAutoSettings(autoReconnect = false),
        )

        autoConnector.start(this)
        runCurrent()

        assertTrue(connector.calls.isEmpty())
    }

    @Test
    fun `does nothing when no adapter has ever been saved`() = runTest {
        val connector = FakeObdConnector()
        val autoConnector = AutoConnector(
            connector = connector,
            source = FakeConnectionSource(ConnectionState.DISCONNECTED),
            adapters = FakeAdapterRepository(seeded = null),
            settings = FakeAutoSettings(autoReconnect = true),
        )

        autoConnector.start(this)
        runCurrent()

        assertTrue(connector.calls.isEmpty())
    }

    @Test
    fun `does not connect when already connected`() = runTest {
        val connector = FakeObdConnector()
        val autoConnector = AutoConnector(
            connector = connector,
            source = FakeConnectionSource(ConnectionState.CONNECTED),
            adapters = FakeAdapterRepository(quirks),
            settings = FakeAutoSettings(autoReconnect = true),
        )

        autoConnector.start(this)
        runCurrent()

        assertTrue(connector.calls.isEmpty())
    }
}

private class FakeObdConnector : ObdConnector {
    override val supported: Set<TransportKind> = setOf(TransportKind.BLE)

    /** Every (target, remembered) pair actually handed to [connect], in order. */
    val calls = mutableListOf<Pair<DiscoveredAdapter, AdapterQuirks?>>()

    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> =
        throw UnsupportedOperationException("AutoConnector never scans")

    override suspend fun connect(target: DiscoveredAdapter, remembered: AdapterQuirks?): ConnectOutcome {
        calls += target to remembered
        return ConnectOutcome.Failed(ConnectFailure.IGNITION_OFF)
    }

    override suspend fun disconnect() = Unit
}

private class FakeConnectionSource(connection: ConnectionState) : SampleSource {
    override val samples: SharedFlow<SensorSample> = MutableSharedFlow()
    override val health: StateFlow<SessionHealth> =
        MutableStateFlow(SessionHealth(connection = connection)).asStateFlow()
}

private class FakeAdapterRepository(seeded: AdapterQuirks? = null) : AdapterRepository {
    private var stored: AdapterQuirks? = seeded

    override suspend fun remember(quirks: AdapterQuirks) {
        stored = quirks
    }

    override suspend fun recall(address: String): AdapterQuirks? =
        stored?.takeIf { it.address == address }

    override suspend fun all(): List<AdapterQuirks> = listOfNotNull(stored)

    override suspend fun updateRtt(address: String, ewmaRttMs: Double, atMs: Long) = Unit

    override suspend fun forget(address: String) {
        if (stored?.address == address) stored = null
    }

    override suspend fun lastUsed(): AdapterQuirks? = stored
}

private class FakeAutoSettings(autoReconnect: Boolean) : SettingsRepository {
    override val settings: Flow<Settings> = MutableStateFlow(Settings(autoReconnect = autoReconnect))

    override suspend fun setRecordTrips(enabled: Boolean) = Unit
    override suspend fun setUnit(quantity: Quantity, unit: UnitId) = Unit
    override suspend fun setUnits(units: UnitPreferences) = Unit

    @Deprecated("Use setUnit(Quantity.SPEED, …).", ReplaceWith("setUnit(Quantity.SPEED, unit)"))
    override suspend fun setSpeedUnit(unit: SpeedUnit) = Unit
    override suspend fun setKeepScreenOn(enabled: Boolean) = Unit
    override suspend fun setActiveVehicleId(id: String?) = Unit
    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override suspend fun setGaugeStyle(style: String) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
}
