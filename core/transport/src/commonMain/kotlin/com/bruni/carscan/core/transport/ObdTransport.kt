package com.bruni.carscan.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * A raw, bidirectional byte pipe to an ELM327 adapter.
 *
 * Deliberately dumb. It knows nothing about AT commands, prompts, or OBD — it moves
 * bytes. Everything above it (framing on the `>` prompt, half-duplex serialization,
 * ISO-TP reassembly, decoding) is transport-agnostic and lives in :core:obd, which
 * is why one protocol stack runs unchanged over Bluetooth LE, Bluetooth Classic and
 * Wi-Fi, and why it can be tested end to end against a fake with no hardware at all.
 *
 * [incoming] is chunked arbitrarily by the underlying stack. A BLE notification may
 * carry half a line; a TCP read may carry three lines and a fragment. **One chunk is
 * not one line, and one chunk is not one response.** Code that assumes otherwise
 * works on the developer's adapter and fails on the user's.
 */
interface ObdTransport {
    suspend fun open()

    /**
     * Bytes from the adapter.
     *
     * **Cold, and single-consumer. No implementation may drop bytes because nobody was
     * listening yet, and no implementation may fan out to two collectors.**
     *
     * This is a hard part of the contract, not a hint. A `SharedFlow`-backed implementation
     * would silently discard anything that arrives before the collector subscribes — so the
     * first response after [open] would go missing *sometimes*, on *one* transport, and the
     * failure would surface far away as a decode error or a phantom timeout. Half-duplex
     * makes it worse: one lost response desynchronizes the prompt stream for the rest of
     * the session.
     *
     * Collect this before you [write]. Buffer, don't broadcast.
     */
    val incoming: Flow<ByteArray>

    suspend fun write(bytes: ByteArray)

    suspend fun close()
}

/**
 * How we reach the adapter.
 *
 * [SPP] is Bluetooth Classic RFCOMM — the cheap ELM327 v1.5 clones. It is
 * **permanently unavailable on iOS**: Apple's ExternalAccessory framework reaches
 * only MFi-certified hardware, and no clone is MFi. This is not a gap to close
 * later; it is a property of the platform, and the product is designed around it.
 */
enum class TransportKind { BLE, SPP, WIFI }

/** An adapter that has been found but not yet connected to. */
data class DiscoveredAdapter(
    val kind: TransportKind,
    /** MAC address, BLE peripheral identifier, or "host:port". Stable across sessions. */
    val address: String,
    val name: String? = null,
    /** BLE only; null elsewhere. */
    val rssi: Int? = null,
)

/**
 * Creates transports, and tells the UI what this platform can actually do.
 *
 * The UI branches on [supported], never on the platform, so that the iOS adapter
 * picker simply has no Bluetooth Classic entry — rather than offering one that
 * cannot work. Android supports all three kinds; iOS supports BLE and Wi-Fi.
 */
interface TransportFactory {
    val supported: Set<TransportKind>

    fun discover(kind: TransportKind): Flow<DiscoveredAdapter>

    fun create(target: DiscoveredAdapter): ObdTransport
}
