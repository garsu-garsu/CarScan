package com.bruni.carscan.core.obd.decode

import com.bruni.carscan.core.model.obdb.CommandSpec

/**
 * True when a message from [canId] is a reply to a command whose receive filter is [rax].
 *
 * This is the check the echo cannot make. When two ECUs answer the same PID — Elantra's
 * `22 E001` is answered by both `7E8` and `7EB` — both replies open with an identical
 * `62 E0 01` echo, so [stripServiceEcho] accepts both and the second silently overwrites
 * the first. The reading that comes out is well-formed, plausible, and from the wrong
 * module.
 *
 * A null [rax] means the command cleared the filter and takes whoever answers.
 *
 * On the adapter `ATCRA` does this in hardware, but the decoder must not assume the
 * adapter was configured — the filter is cheap and the failure it prevents is silent.
 */
fun acceptsReplyFrom(canId: String, rax: String?): Boolean =
    rax == null || canId.equals(rax, ignoreCase = true)

/**
 * Strips the echoed service and PID from a positive response, returning the payload
 * that `fmt.bix` is measured against — or null if this message is not the answer to
 * [spec].
 *
 * Null is not an edge case here, it is the point. A negative response (`7F …`), a reply
 * from an ECU we did not ask, and a late reply landing against the next request all look
 * like perfectly well-formed messages. If any of them were stripped and handed on, every
 * `bix` in the command would index into the wrong bytes and produce numbers that look
 * entirely reasonable. So the echo is *verified*, not assumed: the service byte must be
 * the request's mode + 0x40, and the PID bytes must be the PID we asked for.
 */
fun stripServiceEcho(msg: IsoTpMessage, spec: CommandSpec): ByteArray? {
    val data = msg.data
    val echoLen = 1 + spec.pidBytes
    if (data.size < echoLen) return null

    if ((data[0].toInt() and 0xFF) != spec.responseSid) return null

    for (i in 0 until spec.pidBytes) {
        val expected = spec.pid.substring(i * 2, i * 2 + 2).toInt(16)
        if ((data[1 + i].toInt() and 0xFF) != expected) return null
    }

    return data.copyOfRange(echoLen, data.size)
}
