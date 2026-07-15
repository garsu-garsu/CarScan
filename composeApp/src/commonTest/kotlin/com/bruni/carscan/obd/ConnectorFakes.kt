package com.bruni.carscan.obd

import com.bruni.carscan.core.data.AdapterQuirks
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.ObdTransport
import com.bruni.carscan.core.transport.TransportKind
import com.bruni.carscan.core.transport.ble.GattProfile
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Every ASCII command the session actually put on the wire.
 *
 * The protocol-seeding test cannot be written any other way. "The second connect is faster" is
 * a claim about *what was sent*, and the only honest way to check it is to read the wire —
 * asserting on `AdapterInfo.protocolNum` would pass just as happily for a session that searched
 * for the protocol all over again and arrived at the same answer.
 */
class RecordingTransport(private val delegate: ObdTransport) : ObdTransport {

    private val pending = StringBuilder()

    /** Uppercased, space-stripped, in the order they were written. */
    val commands: MutableList<String> = mutableListOf()

    override val incoming: Flow<ByteArray> get() = delegate.incoming

    override suspend fun open() = delegate.open()

    override suspend fun write(bytes: ByteArray) {
        for (byte in bytes) {
            when (val char = (byte.toInt() and 0xFF).toChar()) {
                '\r' -> {
                    commands += pending.toString().filterNot { it == ' ' }.uppercase()
                    pending.clear()
                }
                else -> pending.append(char)
            }
        }
        delegate.write(bytes)
    }

    override suspend fun close() = delegate.close()
}

/** A [Transports] whose adapters are emulators, and which remembers what it was asked for. */
class FakeTransports(
    override val supported: Set<TransportKind> = setOf(TransportKind.BLE),
    private val discovery: Flow<DiscoveredAdapter> = emptyFlow(),
    /** What [open] reports as the GATT layout it resolved. */
    private val resolvedProfile: GattProfile? = null,
    private val newTransport: () -> ObdTransport,
) : Transports {

    /** One per [open], in order. `opened[1]` is the second connection. */
    val opened: MutableList<RecordingTransport> = mutableListOf()

    /** The quirks handed to the most recent [open]. */
    var lastRemembered: AdapterQuirks? = null
        private set

    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> = discovery

    override fun open(target: DiscoveredAdapter, remembered: AdapterQuirks?): OpenTransport {
        lastRemembered = remembered
        val recording = RecordingTransport(newTransport())
        opened += recording
        return OpenTransport(recording) { resolvedProfile }
    }
}

/** A [Transports] whose every call throws — for the classification tests. */
class ThrowingTransports(
    private val thrown: Throwable,
    override val supported: Set<TransportKind> = setOf(TransportKind.BLE, TransportKind.SPP),
) : Transports {

    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> = throw thrown

    override fun open(target: DiscoveredAdapter, remembered: AdapterQuirks?): OpenTransport =
        throw thrown
}

fun signalsetOf(signalset: EffectiveSignalset?) = SignalsetSource { signalset }

val BLE_TARGET = DiscoveredAdapter(TransportKind.BLE, "AA:BB:CC:DD:EE:01", "OBDII", rssi = -55)
