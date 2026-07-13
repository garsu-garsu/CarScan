package com.bruni.carscan.core.transport.spp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** The well-known Serial Port Profile UUID. Every ELM327, genuine or clone, speaks RFCOMM on it. */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** The reflection rung always asks for channel 1, which is where an ELM327 lives. */
private const val RFCOMM_CHANNEL_1 = 1

/**
 * The real Bluetooth stack — and the only code here that cannot be unit-tested, which is
 * why it holds no decisions. Every method is a one-liner over a framework call; the
 * behaviour that could be wrong lives above the seam, against fakes.
 *
 * The framework calls below throw [SecurityException] when `BLUETOOTH_CONNECT` is missing.
 * That is deliberate and load-bearing: [RfcommConnector] and [SppTransportFactory] turn it
 * into [SppPermissionDeniedException], so a missing permission reaches the user as a
 * sentence instead of a crash.
 */
internal class AndroidBluetoothHost(context: Context) : BluetoothHost {

    private val appContext = context.applicationContext

    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    override fun isEnabled(): Boolean = adapter?.isEnabled == true

    override fun bondedDevices(): List<DiscoveredAdapter> =
        adapter?.bondedDevices.orEmpty().map { device ->
            DiscoveredAdapter(kind = TransportKind.SPP, address = device.address, name = device.name)
        }

    override fun cancelDiscovery() {
        // Best effort. Cancelling needs BLUETOOTH_SCAN, but a scan we are not allowed to
        // start is also a scan that cannot be blocking us — so a SecurityException here is
        // not a reason to refuse to connect.
        runCatching { adapter?.cancelDiscovery() }
    }

    override fun socketFactory(address: String): RfcommSocketFactory {
        val device = adapter?.getRemoteDevice(address) ?: throw SppBluetoothOffException()
        return BluetoothDeviceSocketFactory(device)
    }
}

internal class BluetoothDeviceSocketFactory(private val device: BluetoothDevice) : RfcommSocketFactory {

    override fun secure(): RfcommSocket =
        BluetoothRfcommSocket(device.createRfcommSocketToServiceRecord(SPP_UUID))

    override fun insecure(): RfcommSocket =
        BluetoothRfcommSocket(device.createInsecureRfcommSocketToServiceRecord(SPP_UUID))

    /**
     * The hidden `createRfcommSocket(int)`. It skips SDP entirely and dials channel 1 blind,
     * which is the only thing that works on clones whose service record is broken or absent.
     * Not public API, greylisted, and still the difference between "connects" and "does not"
     * for a large share of the adapters people own.
     */
    override fun reflection(): RfcommSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
        return BluetoothRfcommSocket(method.invoke(device, RFCOMM_CHANNEL_1) as BluetoothSocket)
    }
}

private class BluetoothRfcommSocket(private val socket: BluetoothSocket) : RfcommSocket {
    override fun connect() = socket.connect()
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override fun close() = socket.close()
}
