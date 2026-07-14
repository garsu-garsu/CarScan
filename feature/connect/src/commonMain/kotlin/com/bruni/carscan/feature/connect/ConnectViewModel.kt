package com.bruni.carscan.feature.connect

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.AdapterRepository
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectOutcome
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The screen where the user meets counterfeit hardware.
 *
 * Two things here are load-bearing, and neither of them is the state machine.
 *
 * The first is that the picker is built from [ObdConnector.supported] and **never from the
 * platform**. On iOS there is no Bluetooth Classic and there never will be — Apple's
 * ExternalAccessory framework reaches only MFi-certified hardware, and no ELM327 clone is
 * MFi — so the SPP section must not exist there, rather than exist and fail. An `if (isIos)`
 * anywhere in this file is the bug.
 *
 * The second is that a connection which *succeeds* still owes the user the truth about what
 * they bought. See [ConnectState.throughput].
 */
class ConnectViewModel(
    private val connector: ObdConnector,
    private val adapters: AdapterRepository,
    session: VehicleSessionRepository,
    private val nowMs: () -> Long,
) : MviViewModel<ConnectState, ConnectIntent, ConnectEffect>(
    ConnectState(
        // Capability, not platform. TransportKind's declaration order is the display order.
        sections = TransportKind.entries
            .filter { it in connector.supported }
            .map(::AdapterSection),
    ),
) {

    private var scanJob: Job? = null

    /** The adapter a [ConnectIntent.Retry] should go back to. */
    private var lastTarget: DiscoveredAdapter? = null

    init {
        session.health.collectIntoState { health -> setState { copy(health = health) } }
    }

    override fun onIntent(intent: ConnectIntent) = when (intent) {
        ConnectIntent.Scan -> scan()
        is ConnectIntent.Select -> connect(intent.adapter)
        ConnectIntent.Retry -> lastTarget?.let(::connect) ?: scan()
        ConnectIntent.DismissFailure -> setState { copy(failure = null) }
        ConnectIntent.OpenSettings -> emitEffect(ConnectEffect.OpenAppSettings)
        ConnectIntent.Proceed -> emitEffect(ConnectEffect.NavigateToDashboard)
    }

    private fun scan() {
        scanJob?.cancel()
        setState {
            copy(
                isScanning = true,
                failure = null,
                sections = sections.map { it.copy(adapters = emptyList()) },
            )
        }

        scanJob = scope.launch {
            state.value.sections
                .map { section ->
                    connector.discover(section.kind)
                        // A radio that is off, or a permission that was refused, kills one
                        // transport — not the scan. The other two still have something to find.
                        .catch { setState { copy(failure = section.kind.scanFailure()) } }
                }
                .merge()
                .collect { found -> setState { copy(sections = sections.plus(found)) } }

            setState { copy(isScanning = false) }
        }
    }

    private fun connect(target: DiscoveredAdapter) {
        scanJob?.cancel()
        lastTarget = target
        setState { copy(isScanning = false, connectingTo = target, ready = null, failure = null) }

        scope.launch {
            // The learned-quirks row, if we have ever met this adapter. Handing it back is the
            // entire reason the table exists: without it the second connection pays again for
            // everything the first one found out — above all the protocol search.
            val remembered = adapters.recall(target.address)

            when (val outcome = connector.connect(target, remembered)) {
                is ConnectOutcome.Ready -> {
                    // The connector hands back quirks ready to store. Only *when* is ours to
                    // stamp — and the picker sorts on it.
                    adapters.remember(outcome.adapter.quirks.copy(lastUsedMs = nowMs()))

                    setState {
                        copy(
                            connectingTo = null,
                            ready = ReadyReadout(
                                adapter = target,
                                protocol = protocolName(outcome.adapter.protocolNum),
                                isStn = outcome.adapter.isStn,
                                reusedProfile = remembered != null,
                            ),
                        )
                    }
                }

                is ConnectOutcome.Failed ->
                    setState { copy(connectingTo = null, failure = outcome.reason) }
            }
        }
    }
}

/** Adds a discovered adapter to its section, replacing an earlier sighting of the same address. */
private fun List<AdapterSection>.plus(found: DiscoveredAdapter): List<AdapterSection> = map { section ->
    if (section.kind != found.kind) {
        section
    } else {
        section.copy(adapters = section.adapters.filterNot { it.address == found.address } + found)
    }
}

/**
 * What it means when a *scan* — as opposed to a connect — fails on this transport.
 *
 * Coarse on purpose, and not good enough. [ObdConnector.discover] returns a bare
 * `Flow<DiscoveredAdapter>`, so a failure arrives as an untyped `Throwable` that common code
 * cannot inspect — `SppPermissionDeniedException` is a `java.io.IOException` declared in
 * :core:transport's androidMain and cannot be named here. The *connect* path is classified
 * properly, through [ConnectOutcome.Failed]; the *discover* path has no such channel, so the
 * best this can do is answer from the transport alone.
 *
 * The cost is real: a refused `BLUETOOTH_SCAN` — the most common first-run failure there is —
 * reads as [ConnectFailure.ADAPTER_UNREACHABLE] and sends the user to check their hardware
 * instead of granting a permission. Closing this needs a typed failure on the port.
 */
private fun TransportKind.scanFailure(): ConnectFailure = when (this) {
    // The adapter is its own access point, so "cannot reach it" nearly always means the phone
    // is still on some other network.
    TransportKind.WIFI -> ConnectFailure.WIFI_NOT_JOINED
    TransportKind.SPP -> ConnectFailure.SPP_PAIRING_REQUIRED
    TransportKind.BLE -> ConnectFailure.ADAPTER_UNREACHABLE
}

/**
 * The protocol the adapter actually negotiated, as `ATDPN` numbers it.
 *
 * A fixed table from the ELM327 datasheet, not UI copy — these are spec designations and they
 * are not translated. Null when the adapter would not say, in which case the screen shows no
 * protocol line rather than the word "unknown".
 */
internal fun protocolName(atdpn: Int?): String? = when (atdpn) {
    1 -> "SAE J1850 PWM"
    2 -> "SAE J1850 VPW"
    3 -> "ISO 9141-2"
    4 -> "ISO 14230-4 KWP (5 baud init)"
    5 -> "ISO 14230-4 KWP (fast init)"
    6 -> "ISO 15765-4 CAN (11 bit, 500 kbaud)"
    7 -> "ISO 15765-4 CAN (29 bit, 500 kbaud)"
    8 -> "ISO 15765-4 CAN (11 bit, 250 kbaud)"
    9 -> "ISO 15765-4 CAN (29 bit, 250 kbaud)"
    10 -> "SAE J1939 CAN"
    else -> null
}
