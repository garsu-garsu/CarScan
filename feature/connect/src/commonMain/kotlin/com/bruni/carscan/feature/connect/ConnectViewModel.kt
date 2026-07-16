package com.bruni.carscan.feature.connect

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.AdapterRepository
import com.bruni.carscan.core.data.ConnectException
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectOutcome
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.monetization.Entitlements
import com.bruni.carscan.core.monetization.FullScreenAdGate
import com.bruni.carscan.core.monetization.InterstitialAdPort
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
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
    private val interstitial: InterstitialAdPort,
    private val gate: FullScreenAdGate,
    private val entitlements: Entitlements,
) : MviViewModel<ConnectState, ConnectIntent, ConnectEffect>(
    ConnectState(
        // Capability, not platform. TransportKind's declaration order is the display order.
        availableKinds = TransportKind.entries.filter { it in connector.supported },
    ),
) {

    private var scanJob: Job? = null

    /** The adapter a [ConnectIntent.Retry] should go back to. */
    private var lastTarget: DiscoveredAdapter? = null

    init {
        session.health.collectIntoState { health -> setState { copy(health = health) } }
        // Ready well before the first disconnect — a preload started only on that intent would
        // make the very first drive's disconnect always show nothing.
        interstitial.preload()
    }

    override fun onIntent(intent: ConnectIntent) = when (intent) {
        is ConnectIntent.SelectMethod -> selectMethod(intent.kind)
        ConnectIntent.BackToMethods -> backToMethods()
        ConnectIntent.Scan -> scan()
        is ConnectIntent.Select -> connect(intent.adapter)
        is ConnectIntent.ConnectWifi ->
            connect(DiscoveredAdapter(TransportKind.WIFI, "${intent.host}:${intent.port}"))
        ConnectIntent.Retry -> lastTarget?.let(::connect) ?: scan()
        ConnectIntent.DismissFailure -> setState { copy(failure = null) }
        ConnectIntent.OpenSettings -> emitEffect(ConnectEffect.OpenAppSettings)
        ConnectIntent.Proceed -> emitEffect(ConnectEffect.NavigateToDashboard)
        ConnectIntent.Disconnect -> disconnect()
    }

    /**
     * Ending the drive. The full-screen ad is purely additive here: it must never block or
     * delay the disconnect itself, so it is evaluated and shown in its own launch rather than
     * awaited before or after [ObdConnector.disconnect].
     */
    private fun disconnect() {
        scope.launch { connector.disconnect() }
        maybeShowInterstitial()
    }

    private fun maybeShowInterstitial() {
        if (entitlements.isPremium.value || !gate.shouldShow()) return
        gate.record()
        scope.launch { interstitial.show() }
    }

    /** Choosing a method starts a scan of that method alone — never the other two. */
    private fun selectMethod(kind: TransportKind) {
        setState { copy(selectedKind = kind, adapters = emptyList(), failure = null) }
        scan()
    }

    /** Back to the picker. Whatever scan was running for the old method stops right here. */
    private fun backToMethods() {
        scanJob?.cancel()
        setState { copy(selectedKind = null, adapters = emptyList(), isScanning = false, failure = null) }
    }

    private fun scan() {
        val kind = state.value.selectedKind ?: return
        scanJob?.cancel()
        setState { copy(isScanning = true, failure = null, adapters = emptyList()) }

        scanJob = scope.launch {
            connector.discover(kind)
                // A radio that is off, or a permission that was refused, ends the scan with a
                // reason the user can act on.
                .catch { thrown -> setState { copy(failure = thrown.asConnectFailure()) } }
                // No advertised name means a random nearby device — a phone, earbuds — not an
                // ELM327, which always advertises one. Keeping them would bury the real adapter in
                // noise the user cannot tell apart.
                .filter { !it.name.isNullOrBlank() }
                .collect { found -> setState { copy(adapters = adapters.plus(found)) } }

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

/**
 * Adds a discovered adapter to the list, or refreshes one already there **in place**.
 *
 * In place is the whole point. A BLE peripheral re-advertises several times a second, so the same
 * address arrives again and again during a scan. Removing the old sighting and appending the new
 * one would send that row to the bottom of the list on every advertisement — the list would churn
 * under the user's finger and a tap would never land. Keeping its position makes the list settle so
 * it can actually be used.
 */
private fun List<DiscoveredAdapter>.plus(found: DiscoveredAdapter): List<DiscoveredAdapter> {
    val at = indexOfFirst { it.address == found.address }
    return if (at >= 0) toMutableList().also { it[at] = found } else this + found
}

/**
 * Why a *scan* — as opposed to a connect — failed.
 *
 * [ConnectException] is the whole point. The exceptions worth telling a user about
 * (`SppPermissionDeniedException`, and the bare `SecurityException` Kable throws on a refused
 * `BLUETOOTH_SCAN`) are platform types that common code cannot name, so `ObdConnector` classifies
 * them at the edge and the *reason* is what crosses over. Read it, and do not second-guess it.
 *
 * **The `else` deliberately does not guess from the transport.** Answering
 * [ConnectFailure.ADAPTER_UNREACHABLE] for an unclassified BLE failure would look reasonable and
 * would be a trap: it puts *"the adapter did not answer, check that it is plugged in"* in front of
 * a user who simply tapped Deny, sending them out to their car instead of to the settings button
 * already on the screen. A fallback like that survives forever, because nothing can tell it apart
 * from a live path — so the day the connector meets an exception it has never seen, the bug comes
 * back silently.
 *
 * [ConnectFailure.INIT_FAILED] exists for exactly this. Saying "it failed and we cannot explain
 * why" is unhelpful and harmless; guessing wrong is confident and costs us the user. `discover()`
 * wraps everything, so reaching this branch at all is a bug in the connector, not a case to paper
 * over here.
 */
private fun Throwable.asConnectFailure(): ConnectFailure = when (this) {
    is ConnectException -> reason
    else -> ConnectFailure.INIT_FAILED
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
