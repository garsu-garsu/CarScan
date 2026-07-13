package com.bruni.carscan.core.transport.spp

import java.io.IOException

/**
 * Everything the SPP transport can fail with.
 *
 * These are `IOException`s so a caller that only knows [com.bruni.carscan.core.transport.ObdTransport]
 * can still catch them generically — but each one carries a message a user could act on,
 * because "java.io.IOException: read failed, socket might closed" (sic, the actual
 * Android string) tells nobody anything.
 */
sealed class SppException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Which rung of the ladder an attempt was. */
enum class SppConnectRung { SECURE, INSECURE, REFLECTION }

/** One failed rung, kept so the failure message can name what was tried. */
data class SppConnectAttempt(val rung: SppConnectRung, val cause: Throwable)

class SppPermissionDeniedException(cause: Throwable? = null) : SppException(
    "Bluetooth permission is missing. Android 12 and up need BLUETOOTH_CONNECT granted at " +
        "runtime (and BLUETOOTH_SCAN to cancel a running scan); Android 11 and below need " +
        "BLUETOOTH, BLUETOOTH_ADMIN and ACCESS_FINE_LOCATION. Request the permission, then " +
        "connect again.",
    cause,
)

class SppBluetoothOffException : SppException(
    "Bluetooth is turned off, or this device has no Bluetooth adapter. Turn Bluetooth on and try again.",
)

/** All three rungs failed. Lists what was tried, so a bug report is worth something. */
class SppConnectFailedException(
    val address: String,
    val attempts: List<SppConnectAttempt>,
) : SppException(message(address, attempts), attempts.lastOrNull()?.cause) {

    private companion object {
        fun message(address: String, attempts: List<SppConnectAttempt>): String = buildString {
            append("Could not open a Bluetooth Classic (RFCOMM) channel to $address. ")
            append("Tried ")
            append(
                attempts.joinToString(", ") { attempt ->
                    val rung = attempt.rung.name.lowercase()
                    "$rung (${attempt.cause::class.simpleName}: ${attempt.cause.message})"
                },
            )
            append(
                ". The adapter may be out of range or unpowered, already connected to another " +
                    "app or phone, or paired but not actually an OBD adapter. Unplug and replug it, " +
                    "re-pair it in Bluetooth settings (PIN 1234 or 0000), and close any other OBD app.",
            )
        }
    }
}

/** [com.bruni.carscan.core.transport.ObdTransport.open] has not been called, or the transport is closed. */
class SppNotOpenException : SppException(
    "The Bluetooth Classic transport is not open. Call open() first; a transport that has been " +
        "closed cannot be reopened — create a new one.",
)

/** The socket died under us: adapter unplugged, out of range, car switched off. */
class SppLinkLostException(address: String, cause: Throwable) : SppException(
    "The Bluetooth Classic link to $address dropped. The adapter may have lost power (some " +
        "clones cut out when the ignition goes off) or gone out of range. Reconnect to continue.",
    cause,
)
