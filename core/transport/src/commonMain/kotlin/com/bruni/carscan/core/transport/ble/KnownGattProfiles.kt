package com.bruni.carscan.core.transport.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A GATT layout we have seen in the wild, and how to drive it.
 *
 * [preferWriteWithResponse] is a *preference*, not a fact — see [resolveWriteWithResponse].
 */
@OptIn(ExperimentalUuidApi::class)
private data class KnownGattProfile(
    val service: Uuid,
    val write: Uuid,
    val notify: Uuid,
    val preferWriteWithResponse: Boolean,
)

@OptIn(ExperimentalUuidApi::class)
private fun knownProfile(
    service: String,
    write: String,
    notify: String,
    preferWriteWithResponse: Boolean = false,
) = KnownGattProfile(
    service = bluetoothUuidOf(service),
    write = bluetoothUuidOf(write),
    notify = bluetoothUuidOf(notify),
    preferWriteWithResponse = preferWriteWithResponse,
)

/**
 * There is no standard GATT layout for an ELM327 over BLE. Vendors picked whatever
 * serial-over-BLE profile their radio module shipped with, so the only way to find the
 * pipe is to recognise the module.
 *
 * Order matters: the first entry that matches wins, and any match beats the heuristic.
 */
private val KnownGattProfiles = listOf(
    // Cheap clones on TI CC254x modules. One characteristic is *both* the write sink and
    // the notify source. Overwhelmingly the most common thing you will meet.
    knownProfile(service = "FFE0", write = "FFE1", notify = "FFE1"),

    // vLinker, LELink and relatives: write and notify are separate, and FFF1/FFF2 are the
    // opposite way round from what the numbering suggests.
    knownProfile(service = "FFF0", write = "FFF2", notify = "FFF1"),

    // Nordic UART Service — anything built on an nRF5x.
    knownProfile(
        service = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
        write = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E",
        notify = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
    ),

    // OBDLink CX / MX+. The one adapter that wants acknowledged writes.
    knownProfile(service = "18F0", write = "2AF1", notify = "2AF0", preferWriteWithResponse = true),
)

/**
 * Finds the serial pipe on a peripheral, or returns `null` if it has none.
 *
 * Tries the known layouts first, then falls back to the only rule that generalises: the
 * first service that can both accept our commands and push us responses. The fallback is
 * what carries the no-name clones that advertise nothing recognisable — and hiding a
 * working adapter behind a table we failed to update is a worse failure than trying a
 * service that turns out to be silent.
 *
 * Pure. Given a description of a peripheral's services, it does the same thing every
 * time, and can be tested against every adapter we have ever heard of without a radio.
 */
fun resolveProfile(services: List<DiscoveredService>): GattProfile? =
    KnownGattProfiles.firstNotNullOfOrNull { known -> services.matching(known) }
        ?: services.firstNotNullOfOrNull { it.asHeuristicProfile() }

@OptIn(ExperimentalUuidApi::class)
private fun List<DiscoveredService>.matching(known: KnownGattProfile): GattProfile? {
    val service = firstOrNull { it.uuid == known.service } ?: return null
    val notify = service.characteristics
        .firstOrNull { it.uuid == known.notify && it.properties.notifiable } ?: return null
    val write = service.characteristics
        .firstOrNull { it.uuid == known.write && it.properties.writable } ?: return null

    return GattProfile(
        service = service.uuid,
        write = write.uuid,
        notify = notify.uuid,
        writeWithResponse = resolveWriteWithResponse(write.properties, known.preferWriteWithResponse)
            ?: return null,
    )
}

/** The heuristic: any service that can talk back to us is a candidate for the pipe. */
private fun DiscoveredService.asHeuristicProfile(): GattProfile? {
    val notify = characteristics.firstOrNull { it.properties.notifiable } ?: return null
    val write = characteristics.firstOrNull { it.properties.writable } ?: return null

    return GattProfile(
        service = uuid,
        write = write.uuid,
        notify = notify.uuid,
        writeWithResponse = resolveWriteWithResponse(write.properties, preferWithResponse = false)
            ?: return null,
    )
}

/**
 * Reconciles what the table wants with what the characteristic can actually do.
 *
 * An adapter whose write characteristic only advertises `writeWithoutResponse` will
 * reject every acknowledged write, and vice versa — so the table's preference is honoured
 * only when the characteristic supports it, and otherwise silently yields to reality.
 */
private fun resolveWriteWithResponse(
    properties: CharacteristicProperties,
    preferWithResponse: Boolean,
): Boolean? = when {
    preferWithResponse && properties.write -> true
    !preferWithResponse && properties.writeWithoutResponse -> false
    properties.write -> true
    properties.writeWithoutResponse -> false
    else -> null
}
