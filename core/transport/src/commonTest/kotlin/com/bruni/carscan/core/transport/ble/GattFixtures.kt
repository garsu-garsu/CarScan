package com.bruni.carscan.core.transport.ble

import kotlin.uuid.ExperimentalUuidApi

/** Terse builders so the resolution tests read like the GATT dumps they came from. */
@OptIn(ExperimentalUuidApi::class)
internal fun svc(uuid: String, vararg characteristics: DiscoveredCharacteristic) =
    DiscoveredService(bluetoothUuidOf(uuid), characteristics.toList())

@OptIn(ExperimentalUuidApi::class)
internal fun chr(
    uuid: String,
    write: Boolean = false,
    writeWithoutResponse: Boolean = false,
    notify: Boolean = false,
    indicate: Boolean = false,
) = DiscoveredCharacteristic(
    uuid = bluetoothUuidOf(uuid),
    properties = CharacteristicProperties(
        write = write,
        writeWithoutResponse = writeWithoutResponse,
        notify = notify,
        indicate = indicate,
    ),
)

/** Every adapter exposes these; none of them is ever the OBD pipe. */
internal fun genericAccess() = svc("1800", chr("2A00"), chr("2A01"))

internal fun deviceInformation() = svc("180A", chr("2A29"), chr("2A24"))
