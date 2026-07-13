package com.bruni.carscan.core.transport.spp

/**
 * The connect fallback ladder.
 *
 * A well-behaved SPP device connects on the first rung. Cheap ELM327 clones — which is
 * to say, most of them — do not: they publish a broken SDP record, or none, and the
 * secure path fails with the infamously unhelpful "read failed, socket might closed".
 * So we walk down: secure, insecure, and finally the hidden `createRfcommSocket(1)`
 * reached by reflection. Every serious OBD app ships all three rungs.
 *
 * Blocking. Callers run it off the main thread.
 */
internal class RfcommConnector(
    private val address: String,
    private val host: BluetoothHost,
) {
    fun connect(): RfcommSocket {
        // Discovery starves the radio: with a scan running, connect() reliably times out.
        // This is the number-one "Bluetooth doesn't work" bug in Android code.
        host.cancelDiscovery()

        val factory = host.socketFactory(address)
        val attempts = mutableListOf<SppConnectAttempt>()

        for (rung in SppConnectRung.entries) {
            val socket = try {
                factory.open(rung)
            } catch (e: SecurityException) {
                // Without BLUETOOTH_CONNECT every rung throws this. Walking the ladder would
                // only bury the real cause under two more failures.
                throw SppPermissionDeniedException(e)
            } catch (e: Exception) {
                attempts += SppConnectAttempt(rung, e)
                continue
            }

            try {
                socket.connect()
                return socket
            } catch (e: SecurityException) {
                socket.closeQuietly()
                throw SppPermissionDeniedException(e)
            } catch (e: Exception) {
                // A half-open socket keeps the RFCOMM channel busy, which makes the next
                // rung fail too — for a reason that has nothing to do with the next rung.
                socket.closeQuietly()
                attempts += SppConnectAttempt(rung, e)
            }
        }

        throw SppConnectFailedException(address, attempts)
    }
}

private fun RfcommSocketFactory.open(rung: SppConnectRung): RfcommSocket = when (rung) {
    SppConnectRung.SECURE -> secure()
    SppConnectRung.INSECURE -> insecure()
    SppConnectRung.REFLECTION -> reflection()
}

internal fun RfcommSocket.closeQuietly() {
    runCatching { close() }
}
