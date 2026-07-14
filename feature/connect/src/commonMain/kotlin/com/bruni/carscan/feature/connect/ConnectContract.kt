package com.bruni.carscan.feature.connect

import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind

/** One transport's worth of picker. Absent entirely when the platform cannot do that transport. */
data class AdapterSection(
    val kind: TransportKind,
    val adapters: List<DiscoveredAdapter> = emptyList(),
)

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
     * One per transport this platform actually has, built from [com.bruni.carscan.core.data.ObdConnector.supported].
     *
     * On iOS there is no Bluetooth Classic — Apple's ExternalAccessory framework reaches only
     * MFi-certified hardware and no ELM327 clone is MFi — so the SPP section is *not here at
     * all*, rather than here and disabled. A greyed-out row invites the user to keep tapping
     * at something that can never work.
     */
    val sections: List<AdapterSection> = emptyList(),
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

    val hasAdapters: Boolean get() = sections.any { it.adapters.isNotEmpty() }
}

sealed interface ConnectIntent {
    data object Scan : ConnectIntent
    data class Select(val adapter: DiscoveredAdapter) : ConnectIntent

    /** After a failure: try the same adapter again, or rescan if there wasn't one. */
    data object Retry : ConnectIntent
    data object DismissFailure : ConnectIntent

    /** The permission deep-link. Only offered for [ConnectFailure.BLUETOOTH_PERMISSION]. */
    data object OpenSettings : ConnectIntent

    /** Leave the readout and go drive. */
    data object Proceed : ConnectIntent
}

sealed interface ConnectEffect {
    data object NavigateToDashboard : ConnectEffect
    data object OpenAppSettings : ConnectEffect
}
