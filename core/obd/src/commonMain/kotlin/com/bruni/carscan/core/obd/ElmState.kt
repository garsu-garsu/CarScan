package com.bruni.carscan.core.obd

sealed interface ElmState {
    data object Disconnected : ElmState
    data object Initializing : ElmState
    data object Ready : ElmState
    data object Recovering : ElmState
    data class Failed(val reason: String) : ElmState
}
