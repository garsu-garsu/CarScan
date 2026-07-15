package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ConnectException
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.transport.TransportKind
import com.bruni.carscan.core.transport.spp.SppBluetoothOffException
import com.bruni.carscan.core.transport.spp.SppConnectFailedException
import com.bruni.carscan.core.transport.spp.SppPermissionDeniedException

/**
 * Four branches, and every one of them is a failure with a remedy attached.
 *
 * Nothing here maps a failure to `ADAPTER_UNREACHABLE`, and that is deliberate: a branch whose
 * answer equals [TransportKind.unknownFailure]'s answer is a branch no test can distinguish from
 * its absence, and one that will quietly rot. `SppLinkLostException` and `SppNotOpenException`
 * belong to that class — the fallback already says what they mean.
 */
internal actual fun Throwable.asConnectFailure(kind: TransportKind): ConnectFailure = when (this) {
    // Already classified, further down. Re-deriving it here would throw the answer away.
    is ConnectException -> reason

    is SppPermissionDeniedException -> ConnectFailure.BLUETOOTH_PERMISSION

    // What Kable actually throws when BLUETOOTH_SCAN is refused: a bare SecurityException, not
    // anything of ours. Without this branch the single most common first-run failure in the app
    // falls through to "the adapter did not answer — check that it is plugged in", and we send a
    // user out to their car over a permission dialog they tapped Deny on.
    is SecurityException -> ConnectFailure.BLUETOOTH_PERMISSION

    is SppBluetoothOffException -> ConnectFailure.BLUETOOTH_OFF

    // The secure → insecure → reflection ladder was exhausted. A clone almost always wants to be
    // paired in system Bluetooth settings first (PIN 1234 or 0000).
    is SppConnectFailedException -> ConnectFailure.SPP_PAIRING_REQUIRED

    else -> kind.unknownFailure()
}
