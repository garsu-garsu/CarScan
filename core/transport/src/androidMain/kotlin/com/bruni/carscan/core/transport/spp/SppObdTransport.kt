package com.bruni.carscan.core.transport.spp

import com.bruni.carscan.core.transport.ObdTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads come back as whatever the radio delivered; the buffer only has to be big enough not to split it further. */
private const val READ_BUFFER_BYTES = 1024

/**
 * Bluetooth Classic (RFCOMM / SPP) transport — the cheap ELM327 v1.5 clones.
 *
 * Android only, permanently: Apple's ExternalAccessory framework reaches only MFi
 * hardware, and no clone is MFi.
 *
 * Obtain one from [SppTransportFactory]; it needs a [BluetoothHost] to reach the radio.
 */
class SppObdTransport internal constructor(
    private val address: String,
    private val host: BluetoothHost,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ObdTransport {

    private val connector = RfcommConnector(address, host)
    private val socket = AtomicReference<RfcommSocket?>(null)
    private val closed = AtomicBoolean(false)
    private val collecting = AtomicBoolean(false)

    override suspend fun open() {
        if (closed.get()) throw SppNotOpenException()
        if (socket.get() != null) return
        if (!host.isEnabled()) throw SppBluetoothOffException()

        // connect() blocks for seconds — on a clone that has to fail twice first, longer.
        socket.set(withContext(io) { connector.connect() })
    }

    /**
     * Chunks exactly as the radio delivered them. Not lines: an ELM327 response can arrive
     * split across reads, and two responses can arrive in one. Framing on the `>` prompt is
     * :core:obd's job, and doing it here would break the moment a chunk boundary landed
     * mid-line — which is to say, on someone else's adapter, not the developer's.
     *
     * Single-consumer, enforced: a second collector would start a second pump on the same
     * InputStream, and two pumps split the byte stream between them — each gets part of every
     * response and the half-duplex prompt stream desyncs permanently, under load only.
     */
    override val incoming: Flow<ByteArray> = channelFlow {
        val socket = requireOpen()
        check(collecting.compareAndSet(false, true)) {
            "SppObdTransport($address).incoming already has a collector, and a second pump on " +
                "one RFCOMM socket would steal half of every response from the first."
        }
        launch(io) {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val read = try {
                    socket.input.read(buffer)
                } catch (e: IOException) {
                    // Closing the socket is how a blocking read is ended, so our own close()
                    // arrives here as an IOException. Anything else really is a lost link —
                    // and throwing it fails the flow, which is what the caller needs to see.
                    if (closed.get()) break else throw SppLinkLostException(address, e)
                }
                if (read < 0) break // adapter hung up
                if (read > 0) send(buffer.copyOf(read))
            }
            this@channelFlow.close()
        }

        // A blocking RFCOMM read ignores thread interruption: closing the socket is the only
        // thing that ends it. So cancelling collection has to close the socket, or the read
        // thread is stranded for the life of the process.
        awaitClose { closeSocket() }
    }

    override suspend fun write(bytes: ByteArray) {
        val socket = requireOpen()
        withContext(io) {
            try {
                socket.output.write(bytes)
                socket.output.flush()
            } catch (e: IOException) {
                throw SppLinkLostException(address, e)
            }
        }
    }

    override suspend fun close() {
        withContext(io) { closeSocket() }
    }

    private fun requireOpen(): RfcommSocket = socket.get() ?: throw SppNotOpenException()

    /** Idempotent: the socket is closed by whoever gets there first — close(), or a cancelled collector. */
    private fun closeSocket() {
        closed.set(true)
        socket.getAndSet(null)?.closeQuietly()
    }
}
