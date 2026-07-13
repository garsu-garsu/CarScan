package com.bruni.carscan.core.transport.ble

import com.juul.kable.State

/**
 * The link as a person would describe it.
 *
 * Kable reports three separate rungs on the way up (radio link, service discovery,
 * subscription). They are useful to Kable and meaningless to a user staring at a
 * spinner, so they collapse into one.
 */
enum class BleConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
}

fun asConnectionState(state: State): BleConnectionState = when (state) {
    is State.Connecting -> BleConnectionState.Connecting
    is State.Connected -> BleConnectionState.Connected
    State.Disconnecting -> BleConnectionState.Disconnecting
    is State.Disconnected -> BleConnectionState.Disconnected
}
