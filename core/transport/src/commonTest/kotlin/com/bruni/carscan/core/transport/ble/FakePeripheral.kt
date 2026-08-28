package com.bruni.carscan.core.transport.ble

import com.juul.kable.Characteristic
import com.juul.kable.Descriptor
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.ExperimentalKableApi
import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlin.uuid.ExperimentalUuidApi

/** GATT property bits, as the spec numbers them. */
@OptIn(ExperimentalKableApi::class)
internal fun props(
    write: Boolean = false,
    writeWithoutResponse: Boolean = false,
    notify: Boolean = false,
    indicate: Boolean = false,
): Characteristic.Properties = Characteristic.Properties(
    (if (writeWithoutResponse) 0x04 else 0) or
        (if (write) 0x08 else 0) or
        (if (notify) 0x10 else 0) or
        (if (indicate) 0x20 else 0),
)

@OptIn(ExperimentalUuidApi::class)
internal class FakeKableCharacteristic(
    override val serviceUuid: kotlin.uuid.Uuid,
    override val characteristicUuid: kotlin.uuid.Uuid,
    override val properties: Characteristic.Properties,
) : DiscoveredCharacteristic {
    override val descriptors: List<DiscoveredDescriptor> = emptyList()
}

@OptIn(ExperimentalUuidApi::class)
internal class FakeKableService(
    override val serviceUuid: kotlin.uuid.Uuid,
    override val characteristics: List<DiscoveredCharacteristic>,
) : DiscoveredService

/** The FFE0 clone: one characteristic, both directions. */
@OptIn(ExperimentalUuidApi::class)
internal fun ffe0Services(): List<DiscoveredService> {
    val service = bluetoothUuidOf("FFE0")
    return listOf(
        FakeKableService(
            serviceUuid = service,
            characteristics = listOf(
                FakeKableCharacteristic(
                    serviceUuid = service,
                    characteristicUuid = bluetoothUuidOf("FFE1"),
                    properties = props(writeWithoutResponse = true, notify = true),
                ),
            ),
        ),
    )
}

/** A peripheral with nothing that could carry a serial protocol. */
@OptIn(ExperimentalUuidApi::class)
internal fun muteServices(): List<DiscoveredService> = listOf(
    FakeKableService(
        serviceUuid = bluetoothUuidOf("180A"),
        characteristics = listOf(
            FakeKableCharacteristic(
                serviceUuid = bluetoothUuidOf("180A"),
                characteristicUuid = bluetoothUuidOf("2A29"),
                properties = props(),
            ),
        ),
    ),
)

/**
 * A peripheral with no radio behind it, modelling the one thing about BLE that bites:
 * **a peripheral does not send notifications until someone subscribes.** Kable writes the
 * CCCD descriptor when a collector attaches to `observe()`, so [notify] before an
 * observation exists goes precisely nowhere — exactly as the real radio behaves.
 */
@OptIn(ExperimentalKableApi::class)
internal class FakePeripheral(
    private val discovered: List<DiscoveredService>,
    private val maxWriteLength: Int = 20,
) : Peripheral {

    val writes = mutableListOf<Pair<ByteArray, WriteType>>()
    var observationsStarted = 0
        private set
    var disconnects = 0
        private set
    var closes = 0
        private set

    /** Delivered only while an observation is live — see the class doc. */
    private val radio = Channel<ByteArray>(Channel.UNLIMITED)
    private var observing = false

    /** The adapter pushes a notification up. Dropped if nobody has subscribed yet. */
    suspend fun notify(bytes: ByteArray) {
        if (observing) radio.send(bytes)
    }

    /**
     * Unconfined on purpose. `BleObdTransport.open()` launches its observation in the scope
     * `connect()` hands back, so anything else here would run [observe]'s body on a background
     * thread while the test reads [observationsStarted] and [observing] from its own — a data
     * race whose two symptoms are exactly the two flaky failures this fake used to produce: a
     * notification dropped because `observing` was still stale-false, and `observationsStarted`
     * read as 0 after `open()` returned. Unconfined runs the whole observation inline on the
     * caller's thread, so the fake is single-threaded and the tests are deterministic.
     */
    override val scope: CoroutineScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    override val state = MutableStateFlow<State>(State.Disconnected())
    override val services = MutableStateFlow<List<DiscoveredService>?>(null)

    override val identifier: Identifier get() = error("not used by BleObdTransport")
    override val name: String? = "FakeOBDII"

    override suspend fun connect(): CoroutineScope {
        services.value = discovered
        state.value = State.Connected(scope)
        return scope
    }

    override suspend fun disconnect() {
        disconnects++
        state.value = State.Disconnected()
    }

    override fun close() {
        closes++
    }

    override suspend fun maximumWriteValueLengthForType(writeType: WriteType): Int = maxWriteLength

    override suspend fun write(characteristic: Characteristic, data: ByteArray, writeType: WriteType) {
        writes += data to writeType
    }

    override fun observe(
        characteristic: Characteristic,
        onSubscription: suspend () -> Unit,
    ): Flow<ByteArray> = flow {
        observationsStarted++
        observing = true
        onSubscription()
        emitAll(radio.consumeAsFlow())
    }

    override suspend fun rssi(): Int = -55
    override suspend fun read(characteristic: Characteristic): ByteArray = error("unused")
    override suspend fun read(descriptor: Descriptor): ByteArray = error("unused")
    override suspend fun write(descriptor: Descriptor, data: ByteArray) = error("unused")
}
