package com.bruni.carscan.core.transport.ble

import com.bruni.carscan.core.transport.ObdTransport
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.indicate
import com.juul.kable.notify
import com.juul.kable.write
import com.juul.kable.writeWithoutResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import com.juul.kable.DiscoveredService as KableDiscoveredService

/**
 * The ELM327 serial pipe over Bluetooth LE, and the only Bluetooth that exists on iOS.
 *
 * Deliberately thin: a real BLE stack cannot be unit-tested, so everything here that
 * could hold a bug has been pushed out into pure functions that can be — [resolveProfile]
 * for the GATT zoo, [chunkForWrite] for the ATT payload limit, [asConnectionState] for
 * the state machine. What is left is plumbing, and is verified on real hardware.
 *
 * The peripheral is acquired lazily via [acquirePeripheral] rather than passed in, because
 * on iOS a `CBPeripheral` can only be obtained from a scan or from CoreBluetooth's own
 * cache — there is no "connect to this MAC address" in the common Kable API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleObdTransport(
    /**
     * A profile previously learned from this adapter and persisted. Supplied, it overrides
     * resolution — which matters for the adapters whose heuristic answer is wrong and had
     * to be corrected by hand.
     */
    private val savedProfile: GattProfile? = null,
    private val acquirePeripheral: suspend () -> Peripheral,
) : ObdTransport {

    private class Session(
        val peripheral: Peripheral,
        val profile: GattProfile,
        val maxChunkSize: Int,
        val writeType: WriteType,
    )

    private val peripheral = MutableStateFlow<Peripheral?>(null)
    private val session = MutableStateFlow<Session?>(null)
    private val learned = MutableStateFlow<GattProfile?>(null)
    private var closed = false

    /**
     * Where this adapter's pipe turned out to be. Non-null once [open] succeeds; persist it
     * against the adapter so the next connection does not have to guess again.
     */
    val profile: StateFlow<GattProfile?> = learned.asStateFlow()

    val connectionState: Flow<BleConnectionState> = peripheral.flatMapLatest { peripheral ->
        peripheral?.state?.map(::asConnectionState) ?: flowOf(BleConnectionState.Disconnected)
    }

    /**
     * Notifications, exactly as the radio delivered them.
     *
     * Not reassembled into lines — a notification carries whatever fragment of the ELM327's
     * output happened to fit, and framing on the `>` prompt is :core:obd's job. Emitting
     * "lines" here would mean guessing, and guessing wrong on somebody's adapter.
     */
    override val incoming: Flow<ByteArray> = session.filterNotNull().flatMapLatest { session ->
        session.peripheral.observe(characteristicOf(session.profile.service, session.profile.notify))
    }

    override suspend fun open() {
        check(!closed) { "This transport is closed. Create a new one to reconnect." }

        val peripheral = acquirePeripheral().also { this.peripheral.value = it }
        peripheral.connect()

        val services = checkNotNull(peripheral.services.value) {
            "Connected, but the peripheral reported no services."
        }
        val profile = savedProfile
            ?: resolveProfile(services.map(KableDiscoveredService::asDescription))
            ?: throw NoUsableGattProfileException(services.map { it.serviceUuid.toString() })

        val writeType =
            if (profile.writeWithResponse) WriteType.WithResponse else WriteType.WithoutResponse

        session.value = Session(
            peripheral = peripheral,
            profile = profile,
            // A negotiated MTU only ever widens this. Correctness rides on the ATT floor.
            maxChunkSize = peripheral.maximumWriteValueLengthForType(writeType)
                .coerceAtLeast(ATT_DEFAULT_PAYLOAD_SIZE),
            writeType = writeType,
        )
        learned.value = profile
    }

    override suspend fun write(bytes: ByteArray) {
        val session = checkNotNull(session.value) { "open() must complete before write()." }
        val characteristic = characteristicOf(session.profile.service, session.profile.write)

        chunkForWrite(bytes, session.maxChunkSize).forEach { chunk ->
            session.peripheral.write(characteristic, chunk, session.writeType)
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true

        val peripheral = peripheral.value ?: return
        try {
            peripheral.disconnect()
        } finally {
            peripheral.close()
        }
    }
}

/** The peripheral connected, but nothing on it can carry a serial protocol. */
class NoUsableGattProfileException(
    val discoveredServices: List<String>,
) : IllegalStateException(
    "No service on this peripheral exposes both a writable and a notifiable characteristic, " +
        "so there is nowhere to speak ELM327. Discovered services: $discoveredServices",
)

/** The edge where Kable's model becomes the plain data [resolveProfile] works on. */
@OptIn(ExperimentalUuidApi::class)
internal fun KableDiscoveredService.asDescription() = DiscoveredService(
    uuid = serviceUuid,
    characteristics = characteristics.map { characteristic ->
        DiscoveredCharacteristic(
            uuid = characteristic.characteristicUuid,
            properties = CharacteristicProperties(
                write = characteristic.properties.write,
                writeWithoutResponse = characteristic.properties.writeWithoutResponse,
                notify = characteristic.properties.notify,
                indicate = characteristic.properties.indicate,
            ),
        )
    },
)
