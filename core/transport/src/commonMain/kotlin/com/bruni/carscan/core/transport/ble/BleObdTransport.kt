package com.bruni.carscan.core.transport.ble

import com.bruni.carscan.core.transport.ObdTransport
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.indicate
import com.juul.kable.notify
import com.juul.kable.write
import com.juul.kable.writeWithoutResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
     * Everything the radio has delivered since [open], buffered until someone reads it.
     *
     * Unbounded on purpose: a half-duplex ELM327 answers in tens of bytes, and dropping
     * one of them to save memory is the failure this whole contract exists to prevent.
     */
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * Notifications, exactly as the radio delivered them.
     *
     * Not reassembled into lines — a notification carries whatever fragment of the ELM327's
     * output happened to fit, and framing on the `>` prompt is :core:obd's job. Emitting
     * "lines" here would mean guessing, and guessing wrong on somebody's adapter.
     *
     * Buffered, not broadcast: [open] subscribes to the peripheral and parks bytes in [inbox],
     * so a reply that lands before the consumer arrives is waiting for it rather than gone.
     * `consumeAsFlow` also makes the single-consumer half of the contract loud — a second
     * collector fails instead of quietly splitting the byte stream with the first.
     */
    override val incoming: Flow<ByteArray> = inbox.consumeAsFlow()

    override suspend fun open() {
        check(!closed) { "This transport is closed. Create a new one to reconnect." }

        val peripheral = acquirePeripheral().also { this.peripheral.value = it }
        val connection = peripheral.connect()

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

        // A peripheral sends nothing until its CCCD is written, and Kable writes it when a
        // collector attaches to observe(). Leaving that to the consumer means the adapter is
        // still mute during the first write and the reply is never transmitted at all — so the
        // observation starts here, and open() does not return until it is actually subscribed.
        val subscribed = CompletableDeferred<Unit>()
        connection.launch {
            try {
                peripheral
                    .observe(characteristicOf(profile.service, profile.notify)) {
                        subscribed.complete(Unit)
                    }
                    .collect(inbox::send)
                inbox.close()
            } catch (cancellation: CancellationException) {
                inbox.close()
                throw cancellation
            } catch (failure: Throwable) {
                // A link we did not drop on purpose is a fact the session needs; swallowing it
                // here is how a reconnect never happens.
                inbox.close(failure)
            }
        }.invokeOnCompletion { cause ->
            // An observation that dies before subscribing must fail open(), never hang it.
            subscribed.completeExceptionally(
                cause ?: IllegalStateException("Notifications ended before they were subscribed."),
            )
        }
        subscribed.await()
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
