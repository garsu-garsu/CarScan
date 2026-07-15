package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ConnectException
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.transport.TransportKind

/**
 * iOS has none of the exceptions the Android actual classifies: `SecurityException` and the `Spp*`
 * family are JVM types, and Bluetooth Classic does not exist here at all.
 *
 * CoreBluetooth does not throw on a refused authorization either — it reports it through
 * `CBManager.authorization`, which is a *state* rather than a failure, so it never reaches this
 * function. That is a real gap in the iOS connect screen and it is one this classifier cannot
 * close; it is left honest rather than filled with a guess.
 */
internal actual fun Throwable.asConnectFailure(kind: TransportKind): ConnectFailure = when (this) {
    is ConnectException -> reason
    else -> kind.unknownFailure()
}
