package com.bruni.carscan.core.transport.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The Bluetooth Base UUID. Every 16- and 32-bit "assigned number" is shorthand for a
 * full 128-bit UUID built on it, and GATT only ever speaks the full form. An adapter
 * datasheet says `FFE0`; the peripheral reports `0000ffe0-0000-1000-8000-00805f9b34fb`.
 */
private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/**
 * Parses `FFE0`, `0000FFE0` or a full 128-bit UUID into the one form everything else
 * compares against.
 *
 * @throws IllegalArgumentException if [value] is not a UUID in any accepted form.
 */
@OptIn(ExperimentalUuidApi::class)
fun bluetoothUuidOf(value: String): Uuid = when (value.length) {
    4 -> Uuid.parse("0000$value$BASE_UUID_SUFFIX")
    8 -> Uuid.parse("$value$BASE_UUID_SUFFIX")
    else -> Uuid.parse(value)
}
