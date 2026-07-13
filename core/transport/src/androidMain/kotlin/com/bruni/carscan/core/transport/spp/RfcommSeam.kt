package com.bruni.carscan.core.transport.spp

import com.bruni.carscan.core.transport.DiscoveredAdapter
import java.io.InputStream
import java.io.OutputStream

/**
 * The seam.
 *
 * `BluetoothSocket` is final, has no public constructor and can only be produced by a
 * `BluetoothDevice`, which in turn can only be produced by a real Bluetooth stack —
 * it cannot be unit-tested, and on a JVM host test every call to it throws
 * "not mocked". So the Android edge is squeezed into these three interfaces and the
 * two tiny classes that implement them ([AndroidBluetoothHost],
 * [BluetoothDeviceSocketFactory]). Everything with a decision in it — the connect
 * fallback ladder, the stream pump, discovery, error mapping — sits above the seam and
 * is tested against fakes.
 */
internal interface RfcommSocket {
    /** Blocks until connected, or throws. */
    fun connect()

    val input: InputStream
    val output: OutputStream

    fun close()
}

/**
 * The three rungs of the connect ladder, as three ways of asking for a socket.
 *
 * Cheap ELM327 clones fail the secure path constantly (they advertise no SDP record,
 * or advertise one they cannot honour), so every serious OBD app ships all three.
 */
internal interface RfcommSocketFactory {
    fun secure(): RfcommSocket

    fun insecure(): RfcommSocket

    /** `createRfcommSocket(1)` — hidden API, reached by reflection. The last resort. */
    fun reflection(): RfcommSocket
}

/** The Bluetooth adapter itself: everything we need from it, and nothing more. */
internal interface BluetoothHost {
    fun isEnabled(): Boolean

    /**
     * Paired devices. This is how users actually add an ELM327 — in system settings,
     * PIN 1234 or 0000 — so it is the primary discovery source, and it works with no
     * scan running.
     *
     * Throws [SecurityException] when `BLUETOOTH_CONNECT` is missing.
     */
    fun bondedDevices(): List<DiscoveredAdapter>

    fun cancelDiscovery()

    fun socketFactory(address: String): RfcommSocketFactory
}
