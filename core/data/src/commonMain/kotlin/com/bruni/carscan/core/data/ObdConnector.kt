package com.bruni.carscan.core.data

import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import kotlinx.coroutines.flow.Flow

/**
 * Finding an adapter, connecting to it, and finding out what it actually is.
 *
 * Declared here and satisfied in :composeApp by an implementation over :core:obd — the same
 * inversion as [SampleSource], and for the same reason. The connect screen is the one place a
 * feature module would be most tempted to reach into the ELM327 machinery, so it is the one
 * place where the boundary is worth defending: nothing above :core:data can construct a
 * session, and therefore nothing above :core:data can break half-duplex.
 */
interface ObdConnector {
    /**
     * What this platform can actually do.
     *
     * The UI branches on this, never on the platform. iOS is {BLE, WIFI}: Apple's
     * ExternalAccessory framework reaches only MFi-certified hardware and no ELM327 clone is
     * MFi, so Bluetooth Classic is not a gap to be closed later — it does not exist there. The
     * adapter picker simply has no SPP section, rather than offering one that cannot work.
     */
    val supported: Set<TransportKind>

    fun discover(kind: TransportKind): Flow<DiscoveredAdapter>

    /**
     * [remembered] is what we learned about this adapter last time. Passing it back skips the
     * probing that produced it — and on a clone managing fifteen queries a second, the round
     * trips saved are the difference between a second connection that feels instant and one
     * that feels like the first.
     */
    suspend fun connect(target: DiscoveredAdapter, remembered: AdapterQuirks? = null): ConnectOutcome

    suspend fun disconnect()
}

sealed interface ConnectOutcome {
    data class Ready(val adapter: AdapterSummary) : ConnectOutcome
    data class Failed(val reason: ConnectFailure) : ConnectOutcome
}

/**
 * A scan failed for a reason the user can do something about. Thrown into [ObdConnector.discover]'s
 * flow, so the discovery path has the same typed failure channel [ConnectOutcome.Failed] gives the
 * connect path.
 *
 * Without this, the most common first-run failure in the app is reported as the wrong thing. A user
 * taps Deny on the Bluetooth permission; Kable throws a bare `SecurityException`, which common code
 * cannot name (it is a JVM type, as are :core:transport's SPP exceptions); the ViewModel can only
 * guess from the transport kind, guesses `ADAPTER_UNREACHABLE`, and the screen says *"the adapter
 * did not answer — check that it is plugged in."*
 *
 * So we send them to fiddle with their hardware, when the actual fix is the settings deep-link we
 * already have — and `BLUETOOTH_PERMISSION` is the one failure with a working remedy attached.
 * The classification has to happen where the platform exceptions are visible, and what crosses into
 * common code is this.
 */
class ConnectException(val reason: ConnectFailure) : Exception(reason.name)

/**
 * Why a connection failed, as data.
 *
 * The concrete exceptions this is derived from ([SppPermissionDenied] and friends) live in
 * :core:transport's androidMain and extend `java.io.IOException` — a commonMain ViewModel
 * cannot catch them or even name them. So the classification happens at the edge, and what
 * crosses into common code is this enum.
 *
 * Every entry exists because a user can do something about it. An ELM error code on screen
 * helps nobody; "turn the ignition on" is a fix.
 */
enum class ConnectFailure {
    /** `UNABLE_TO_CONNECT` during init. Nearly always this, and nearly always the real cause. */
    IGNITION_OFF,

    /** BLUETOOTH_CONNECT / BLUETOOTH_SCAN denied. Offer the settings deep-link. */
    BLUETOOTH_PERMISSION,

    /** The radio is off. */
    BLUETOOTH_OFF,

    /** The secure → insecure → reflection ladder was exhausted. Clones usually want pairing first (PIN 1234 / 0000). */
    SPP_PAIRING_REQUIRED,

    /** The Wi-Fi adapter is its own access point and the phone is not on it. Joining it also kills cellular data — say so. */
    WIFI_NOT_JOINED,

    /** Nothing answered. Unplugged, out of range, or dead. */
    ADAPTER_UNREACHABLE,

    /** The adapter answered but never became usable, for a reason we cannot make actionable. */
    INIT_FAILED,
}

/**
 * What the adapter turned out to be, once we asked it instead of believing it.
 *
 * The common-code mirror of :core:obd's `AdapterInfo`. Counterfeit adapters answer `ELM327 v2.1`
 * to `ATI` and then reject half of what a real v2.1 does, so every capability here was
 * feature-detected — we sent the command and watched what came back.
 */
data class AdapterSummary(
    /** Whatever `ATI` said. A fingerprint for support tickets, never an input to a decision. */
    val identity: String,
    /** A genuine STN (OBDLink), not a clone. */
    val isStn: Boolean,
    /** The protocol that was actually negotiated, e.g. 6 for ISO 15765-4 11-bit. */
    val protocolNum: Int?,
    /** Measured round trip of a real `0100`. This is what the poll budget is built on. */
    val rttMs: Long,
    /**
     * The learned quirks, ready to be handed to [AdapterRepository.remember] so the next
     * connection to this adapter skips the probing that produced them.
     */
    val quirks: AdapterQuirks,
)
