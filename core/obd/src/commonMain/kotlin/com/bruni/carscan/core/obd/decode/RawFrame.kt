package com.bruni.carscan.core.obd.decode

/**
 * One CAN frame as the ELM327 printed it with headers on (`ATH1`).
 *
 * [canId] is kept as the hex text the adapter emitted (`"7EC"`, `"18DAF110"`) rather
 * than an Int, because it is what addresses a buffer, what a `rax` filter is compared
 * against, and what gets reported as the answering ECU. Widening it to an Int would
 * throw away whether it was an 11-bit or a 29-bit id.
 */
class RawFrame(val canId: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is RawFrame && canId == other.canId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * canId.hashCode() + bytes.contentHashCode()

    override fun toString(): String =
        "RawFrame($canId, ${bytes.joinToString(" ") { it.toHex() }})"
}

private const val HEX = "0123456789ABCDEF"

private fun Byte.toHex(): String {
    val v = toInt() and 0xFF
    return "${HEX[v shr 4]}${HEX[v and 0x0F]}"
}

/**
 * Parses one line of `ATH1` output into a frame, or returns null if the line is not
 * a frame at all.
 *
 * Null is the whole point. The same stream carries `SEARCHING...`, `NO DATA`,
 * `CAN ERROR`, the `>` prompt and an adapter's version banner, and every one of those
 * would decode into a confident, wrong number if it were fed to a hex parser. Anything
 * holding a non-hex character is rejected outright.
 *
 * The id width is decided by length parity, which is total: an 11-bit line is
 * `3 + 2n` characters (always odd) and a 29-bit line is `8 + 2n` (always even). Spaces
 * are stripped first, because not every adapter honours `ATS0`.
 */
fun parseElmLine(line: String): RawFrame? {
    val hex = StringBuilder(line.length)
    for (c in line) {
        if (c.isWhitespace()) continue
        val u = c.uppercaseChar()
        if (u !in HEX) return null
        hex.append(u)
    }

    val idLen = when {
        hex.length >= 5 && hex.length % 2 == 1 -> 3
        hex.length >= 10 && hex.length % 2 == 0 -> 8
        else -> return null
    }

    val payload = ByteArray((hex.length - idLen) / 2) { i ->
        val hi = HEX.indexOf(hex[idLen + 2 * i])
        val lo = HEX.indexOf(hex[idLen + 2 * i + 1])
        ((hi shl 4) or lo).toByte()
    }
    return RawFrame(hex.substring(0, idLen), payload)
}
