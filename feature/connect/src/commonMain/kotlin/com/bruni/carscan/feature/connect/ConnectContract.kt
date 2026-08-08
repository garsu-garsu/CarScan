package com.bruni.carscan.feature.connect

import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind

/** What the adapter turned out to be, once it had been asked rather than believed. */
data class ReadyReadout(
    val adapter: DiscoveredAdapter,
    /** The protocol that was actually negotiated. Null when `ATDPN` would not say. */
    val protocol: String?,
    /** A genuine STN answered `STI`. Everything else is a clone, and clones are slow. */
    val isStn: Boolean,
    /** True when this connect reused a profile from the quirks table instead of re-probing. */
    val reusedProfile: Boolean,
)

data class ConnectState(
    /**
     * The methods this platform actually has, built from
     * [com.bruni.carscan.core.data.ObdConnector.supported] — never from the platform.
     *
     * On iOS there is no Bluetooth Classic — Apple's ExternalAccessory framework reaches only
     * MFi-certified hardware and no ELM327 clone is MFi — so SPP is *not offered at all*, rather
     * than offered and disabled. A greyed-out row invites the user to keep tapping at something
     * that can never work.
     */
    val availableKinds: List<TransportKind> = emptyList(),
    /** Null shows the method picker. Set, it shows the scan/list (or Wi-Fi entry) for that kind. */
    val selectedKind: TransportKind? = null,
    /** Results for [selectedKind] only — cleared on every [ConnectIntent.SelectMethod] or [ConnectIntent.BackToMethods]. */
    val adapters: List<DiscoveredAdapter> = emptyList(),
    val isScanning: Boolean = false,
    val connectingTo: DiscoveredAdapter? = null,
    val ready: ReadyReadout? = null,
    val failure: ConnectFailure? = null,
    val health: SessionHealth = SessionHealth(),
) {
    /**
     * The honest number. Null until the first round trip has been measured — see
     * [adviseThroughput], which will not put a figure on an adapter it has not timed.
     */
    val throughput: ThroughputAdvice? get() = adviseThroughput(health.capacityHz)

    val hasAdapters: Boolean get() = adapters.isNotEmpty()
}

sealed interface ConnectIntent {
    /** The user's choice of connection method. Sets [ConnectState.selectedKind] and starts scanning it. */
    data class SelectMethod(val kind: TransportKind) : ConnectIntent

    /** Back to the method picker. Cancels any scan in flight and clears the results. */
    data object BackToMethods : ConnectIntent

    /** Rescan the currently selected method. */
    data object Scan : ConnectIntent
    data class Select(val adapter: DiscoveredAdapter) : ConnectIntent

    /** Wi-Fi has no scan — the adapter is its own fixed access point — so the host and port are typed in by hand. */
    data class ConnectWifi(val host: String, val port: Int) : ConnectIntent

    /** After a failure: try the same adapter again, or rescan if there wasn't one. */
    data object Retry : ConnectIntent

    /** The permission deep-link. Only offered for [ConnectFailure.BLUETOOTH_PERMISSION]. */
    data object OpenSettings : ConnectIntent

    /** Leave the readout and go drive. */
    data object Proceed : ConnectIntent

    /** The user's explicit choice to end the drive and disconnect from the adapter. */
    data object Disconnect : ConnectIntent
}

sealed interface ConnectEffect {
    data object NavigateToDashboard : ConnectEffect
    data object OpenAppSettings : ConnectEffect
}
