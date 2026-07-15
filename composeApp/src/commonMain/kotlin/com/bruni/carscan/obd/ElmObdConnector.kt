package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ActiveVehicle
import com.bruni.carscan.core.data.AdapterQuirks
import com.bruni.carscan.core.data.AdapterSummary
import com.bruni.carscan.core.data.ConnectException
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectOutcome
import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.SampleSource
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.obd.ElmState
import com.bruni.carscan.core.obd.poll.PidScheduler
import com.bruni.carscan.core.obd.poll.PollClock
import com.bruni.carscan.core.obd.poll.Priority
import com.bruni.carscan.core.obd.poll.pollEntry
import com.bruni.carscan.core.obd.session.ElmInitFailure
import com.bruni.carscan.core.obd.session.ElmSession
import com.bruni.carscan.core.obd.session.ElmSessionConfig
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The live OBD session, and the only thing in the app that knows an ELM327 exists.
 *
 * It satisfies four ports at once because they are four views of one object — the connection.
 * [ObdConnector] opens it, [SampleSource] is what comes out of it, [ActiveVehicle] is what is on
 * the other end of it, and [VisibleSignals] is what the user is looking at. Splitting them into
 * four classes would only mean four classes sharing one mutable session, which is the same thing
 * with more places for it to go wrong.
 *
 * The flows are **stable across connections**: they are this object's, not the current session's.
 * A `SampleSource` re-created on every connect would mean every ViewModel in the app re-collecting
 * a new flow whenever the adapter reconnected, and any that missed the swap would sit on a dead
 * one forever.
 */
class ElmObdConnector(
    private val transports: Transports,
    private val signalsets: SignalsetSource,
    private val scope: CoroutineScope,
    private val sessionConfig: ElmSessionConfig = ElmSessionConfig(),
    private val pollClock: PollClock = PollClock.system(),
) : ObdConnector, SampleSource, ActiveVehicle, VisibleSignals {

    /** One live connection: the session, its poller, and the coroutines feeding this object. */
    private class Live(
        val session: ElmSession,
        val scheduler: PidScheduler,
        val pumps: Job,
    )

    /** `connect` and `disconnect` must not interleave: two sessions on one adapter is two sockets. */
    private val gate = Mutex()

    private var live: Live? = null

    /** What the user is looking at, kept across reconnects — the dashboard did not go away. */
    private var visible: Set<MetricKey> = emptySet()

    /** Command ids already in the poll plan. `PidScheduler.submit` appends; it does not upsert. */
    private val submitted = mutableSetOf<String>()

    override val supported: Set<TransportKind> get() = transports.supported

    private val _samples = MutableSharedFlow<SensorSample>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val samples: SharedFlow<SensorSample> = _samples.asSharedFlow()

    private val _health = MutableStateFlow(SessionHealth())
    override val health: StateFlow<SessionHealth> = _health.asStateFlow()

    private val _signalset = MutableStateFlow<EffectiveSignalset?>(null)
    override val signalset: StateFlow<EffectiveSignalset?> = _signalset.asStateFlow()

    private val _unsupported = MutableStateFlow<Set<String>>(emptySet())
    override val unsupported: StateFlow<Set<String>> = _unsupported.asStateFlow()

    /**
     * Discovery, with its failures classified.
     *
     * The `flow { emitAll(…) }` wrapper is not ceremony: `SppTransportFactory.discover` throws
     * `SppBluetoothOffException` *synchronously*, before it has returned a flow at all, so a bare
     * `.catch` on the result would never see it. Inside a flow builder, both kinds of failure
     * arrive in the same place.
     */
    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> =
        flow { emitAll(transports.discover(kind)) }
            .catch { thrown -> throw ConnectException(thrown.asConnectFailure(kind)) }

    override suspend fun connect(
        target: DiscoveredAdapter,
        remembered: AdapterQuirks?,
    ): ConnectOutcome = gate.withLock {
        teardown()
        _health.value = SessionHealth(connection = ConnectionState.CONNECTING)

        val opened = try {
            transports.open(target, remembered)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Throwable) {
            return@withLock failed(thrown.asConnectFailure(target.kind))
        }

        val session = ElmSession(opened.transport, scope, sessionConfig)

        // ---------------------------------------------------------------------------------
        // THE reconnect speed-up, and the entire reason `protocol_num` is a column.
        //
        // Seeded, ElmInitializer.protocol() sends one ATSP6 and stops. Not seeded, it sends
        // ATSP0 — which detects nothing on its own — and then a real 0100 that waits out the
        // search timeout while the adapter walks every protocol in turn. On a cold bus that is
        // *seconds*, not round trips. Drop this line and the quirks table buys nothing.
        // ---------------------------------------------------------------------------------
        session.atCache.protocol = remembered?.protocolNum?.toInt()

        val info = try {
            session.connect()
        } catch (cancelled: CancellationException) {
            session.close()
            throw cancelled
        } catch (failure: ElmInitFailure) {
            session.close()
            return@withLock failed(failure.kind.asConnectFailure())
        } catch (thrown: Throwable) {
            session.close()
            return@withLock failed(thrown.asConnectFailure(target.kind))
        }

        val scheduler = PidScheduler(session, session.atCache, pollClock)
        live = Live(session, scheduler, startPumps(session, scheduler))

        _signalset.value = signalsets.load()
        // Whatever the user was already looking at. On a reconnect the dashboard is still on
        // screen, and it is not going to tell us again.
        applyVisible(visible)

        ConnectOutcome.Ready(
            AdapterSummary(
                identity = info.identity,
                isStn = info.isStn,
                protocolNum = info.protocolNum,
                rttMs = info.rttMs,
                quirks = AdapterQuirks(
                    address = target.address,
                    kind = target.kind.name,
                    name = target.name,
                    // BLE only, and only once the transport is open — which it now is. There is
                    // no standard GATT layout for an ELM327, so this is the second thing (after
                    // the protocol) that makes the next connection to this adapter cheap.
                    gattService = opened.learnedProfile()?.service?.toString(),
                    gattWrite = opened.learnedProfile()?.write?.toString(),
                    gattNotify = opened.learnedProfile()?.notify?.toString(),
                    isStn = info.isStn,
                    supportsExpectedFrames = info.supportsExpectedFrames,
                    echoSuppressionNeeded = info.echoSuppressionNeeded,
                    maxWriteChunk = info.maxWriteChunk.toLong(),
                    protocolNum = info.protocolNum?.toLong(),
                    ewmaRttMs = info.rttMs.toDouble(),
                    // Stamped by the caller. The connector has no clock and no business owning one.
                    lastUsedMs = null,
                ),
            ),
        )
    }

    override suspend fun disconnect() = gate.withLock { teardown() }

    /**
     * The visible tiles get the adapter's budget — and the commands behind them get into the plan
     * in the first place.
     *
     * Only what is on screen is ever polled. Submitting the vehicle's whole signalset instead
     * would be a hundred-odd commands competing for the fifteen exchanges a second a clone
     * manages, and it would make `SessionHealth.loadHz` — *"what the visible tiles are asking
     * for"* — a number about PIDs nobody has ever looked at. The over-subscription warning would
     * then be on for every user, always, and a warning that always fires is one nobody reads when
     * it finally matters.
     */
    override fun setVisible(keys: Set<MetricKey>) {
        visible = keys
        applyVisible(keys)
    }

    private fun applyVisible(keys: Set<MetricKey>) {
        val scheduler = live?.scheduler ?: return
        val signalset = _signalset.value

        if (signalset != null) {
            // `submit` appends — it does not upsert — so a command already in the plan must not be
            // handed over twice, or it is polled twice and counted twice.
            val fresh = keys
                .flatMap(signalset::get)
                .map { it.command }
                .distinctBy { it.id }
                .filter { submitted.add(it.id) }
                .map { it.command.pollEntry(Priority.NORMAL) }

            if (fresh.isNotEmpty()) scheduler.submit(fresh)
        }

        // Promotes what is on screen to CRITICAL — the one priority the governor will not slow
        // down — and demotes what scrolled off it.
        scheduler.setVisible(keys)
    }

    /** The coroutines that make this object's flows the session's flows. */
    private fun startPumps(session: ElmSession, scheduler: PidScheduler): Job = scope.launch {
        launch { scheduler.run() }
        launch { scheduler.samples.collect(_samples::emit) }
        launch { scheduler.unsupported.collect { _unsupported.value = it } }
        launch {
            combine(session.state, scheduler.health) { state, poller ->
                poller.asSessionHealth(state.asConnectionState())
            }.collect { _health.value = it }
        }
    }

    private fun failed(reason: ConnectFailure): ConnectOutcome {
        _health.value = SessionHealth()
        return ConnectOutcome.Failed(reason)
    }

    private suspend fun teardown() {
        val current = live ?: return
        live = null
        current.pumps.cancel()
        current.session.close()

        submitted.clear()
        _signalset.value = null
        _unsupported.value = emptySet()
        _health.value = SessionHealth()
    }
}

/**
 * The session's own view of whether it is up.
 *
 * `Recovering` is CONNECTED, not CONNECTING: the adapter browned out and the session is putting
 * its AT registers back, which takes under a second and does not need the connect screen to
 * reappear over the top of a moving dashboard.
 */
private fun ElmState.asConnectionState(): ConnectionState = when (this) {
    ElmState.Ready, ElmState.Recovering -> ConnectionState.CONNECTED
    ElmState.Initializing -> ConnectionState.CONNECTING
    ElmState.Disconnected, is ElmState.Failed -> ConnectionState.DISCONNECTED
}
