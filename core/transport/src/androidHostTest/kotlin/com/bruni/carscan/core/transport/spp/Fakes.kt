package com.bruni.carscan.core.transport.spp

import com.bruni.carscan.core.transport.DiscoveredAdapter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingDeque

/**
 * An [InputStream] that behaves like a real RFCOMM one: `read` **blocks** until bytes
 * arrive, returns whatever chunk the radio happened to deliver (never a tidy line), and
 * only ever unblocks because someone closed the socket. A stream that returned data
 * immediately would let a broken pump pass.
 */
internal class ChunkedInputStream : InputStream() {

    private sealed interface Item {
        class Data(val bytes: ByteArray) : Item
        data object Eof : Item
        data object Closed : Item
        class Failed(val cause: IOException) : Item
    }

    private val items = LinkedBlockingDeque<Item>()

    /** Deliver one chunk, exactly as the radio would. */
    fun feed(bytes: ByteArray) = items.put(Item.Data(bytes))

    /** The peer hung up cleanly: `read` returns -1. */
    fun feedEof() = items.put(Item.Eof)

    /** The link dropped: `read` throws. */
    fun feedFailure(cause: IOException) = items.put(Item.Failed(cause))

    override fun read(): Int = throw UnsupportedOperationException("the pump reads into a buffer")

    override fun read(b: ByteArray, off: Int, len: Int): Int = when (val item = items.take()) {
        is Item.Data -> {
            val n = minOf(len, item.bytes.size)
            item.bytes.copyInto(b, off, 0, n)
            if (n < item.bytes.size) items.putFirst(Item.Data(item.bytes.copyOfRange(n, item.bytes.size)))
            n
        }
        Item.Eof -> -1
        Item.Closed -> throw IOException("socket closed")
        is Item.Failed -> throw item.cause
    }

    override fun close() = items.put(Item.Closed)
}

internal class FakeRfcommSocket(
    private val rung: SppConnectRung,
    private val log: MutableList<String>,
    private val onConnect: (SppConnectRung) -> Unit,
) : RfcommSocket {

    val stream = ChunkedInputStream()
    val written = ByteArrayOutputStream()
    var closeCount = 0
        private set
    var connected = false
        private set

    override fun connect() {
        log += "connect:${rung.tag}"
        onConnect(rung)
        connected = true
    }

    override val input: InputStream get() = stream
    override val output: OutputStream get() = written

    override fun close() {
        closeCount++
        log += "close:${rung.tag}"
        stream.close()
    }
}

/**
 * @param onCreate throw to simulate `createRfcommSocket…` itself failing (a `SecurityException`
 *   when the permission is missing, a `NoSuchMethodException` when a ROM has removed the
 *   hidden reflection entry point).
 * @param onConnect throw to simulate the far more common case: the socket is handed over
 *   fine and then `connect()` fails.
 */
internal class FakeRfcommSocketFactory(
    private val log: MutableList<String>,
    private val onCreate: (SppConnectRung) -> Unit = {},
    private val onConnect: (SppConnectRung) -> Unit = {},
) : RfcommSocketFactory {

    val created = mutableListOf<FakeRfcommSocket>()

    override fun secure(): RfcommSocket = open(SppConnectRung.SECURE)
    override fun insecure(): RfcommSocket = open(SppConnectRung.INSECURE)
    override fun reflection(): RfcommSocket = open(SppConnectRung.REFLECTION)

    private fun open(rung: SppConnectRung): RfcommSocket {
        log += "create:${rung.tag}"
        onCreate(rung)
        return FakeRfcommSocket(rung, log, onConnect).also { created += it }
    }
}

internal class FakeBluetoothHost(
    val log: MutableList<String> = mutableListOf(),
    val factory: FakeRfcommSocketFactory = FakeRfcommSocketFactory(log),
    private val enabled: Boolean = true,
    private val bonded: List<DiscoveredAdapter> = emptyList(),
    private val bondedFailure: Throwable? = null,
) : BluetoothHost {

    override fun isEnabled(): Boolean = enabled

    override fun bondedDevices(): List<DiscoveredAdapter> {
        bondedFailure?.let { throw it }
        return bonded
    }

    override fun cancelDiscovery() {
        log += "cancelDiscovery"
    }

    override fun socketFactory(address: String): RfcommSocketFactory {
        log += "socketFactory:$address"
        return factory
    }
}

internal val SppConnectRung.tag: String get() = name.lowercase()
