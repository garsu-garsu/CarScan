package com.bruni.carscan.core.transport.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What a peripheral said it has, stripped of everything the resolver does not need.
 *
 * This exists so that [resolveProfile] — the only part of the BLE stack with real logic
 * in it — is a pure function over plain data, and can be tested exhaustively against the
 * GATT layouts of adapters nobody on this team owns. The Kable types are mapped into
 * these at the edge, in [BleObdTransport], and go no further.
 */
@OptIn(ExperimentalUuidApi::class)
data class DiscoveredService(
    val uuid: Uuid,
    val characteristics: List<DiscoveredCharacteristic>,
)

@OptIn(ExperimentalUuidApi::class)
data class DiscoveredCharacteristic(
    val uuid: Uuid,
    val properties: CharacteristicProperties,
)

data class CharacteristicProperties(
    /** Acknowledged write. */
    val write: Boolean = false,
    /** Unacknowledged ("command") write — what most ELM327 clones want. */
    val writeWithoutResponse: Boolean = false,
    val notify: Boolean = false,
    val indicate: Boolean = false,
) {
    /** Notify and indicate differ only in whether the peripheral wants an ack back. */
    val notifiable: Boolean get() = notify || indicate

    val writable: Boolean get() = write || writeWithoutResponse
}

/**
 * Where on a specific adapter the ELM327 serial pipe actually lives.
 *
 * Resolving this costs a connect and a full service discovery, so it is persisted per
 * adapter (the `adapter` table's `gatt_service` / `gatt_write` / `gatt_notify` columns)
 * and handed back to [BleObdTransport] on reconnect to skip the whole dance. That makes
 * this a value that leaves the process and comes back: keep it plain, and reconstruct it
 * with [bluetoothUuidOf] over the stored strings.
 *
 * [write] and [notify] are frequently the **same** characteristic — that is not a bug,
 * it is what the FFE0/FFE1 clones do.
 */
@OptIn(ExperimentalUuidApi::class)
data class GattProfile(
    val service: Uuid,
    val write: Uuid,
    val notify: Uuid,
    /**
     * `true` selects an acknowledged write. Most adapters want `false`; OBDLink's 18F0
     * profile wants `true`. Getting this backwards fails every write on the adapters that
     * only implement one of the two.
     */
    val writeWithResponse: Boolean,
)
