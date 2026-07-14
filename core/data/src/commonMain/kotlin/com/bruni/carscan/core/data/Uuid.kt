package com.bruni.carscan.core.data

import kotlin.random.Random

private const val HEX = "0123456789abcdef"

/**
 * A random (version 4) UUID.
 *
 * Every id the user can own — trip, vehicle, dashboard layout — is one of these
 * rather than an autoincrement integer, because backups are restored onto *other
 * devices*. Autoincrement keys collide by construction: the phone being restored to
 * already has a trip 1. With a UUID, importing the same backup twice lands on the
 * same row twice, so it can be made idempotent; with an integer key it cannot be,
 * at any layer above.
 */
fun newUuid(random: Random = Random.Default): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte() // version 4
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // IETF variant

    val sb = StringBuilder(36)
    for (i in 0 until 16) {
        if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
        val v = bytes[i].toInt() and 0xFF
        sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}
