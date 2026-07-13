package com.bruni.carscan.core.transport.ble

/**
 * ATT's guaranteed payload: the 23-byte default MTU minus 3 bytes of header.
 *
 * A larger MTU may be negotiated, but it may also not be — and a write longer than the
 * peer's actual limit is silently truncated on some stacks rather than rejected. So this
 * is the floor everything is safe at, and the only thing correctness is allowed to
 * depend on. A bigger MTU buys throughput, never correctness.
 */
const val ATT_DEFAULT_PAYLOAD_SIZE = 20

/**
 * Splits a command into writes the peer will accept whole.
 *
 * `>`-prompt framing happens in :core:obd over the reassembled byte stream, so splitting
 * a command across several writes is invisible to everything above the transport.
 *
 * [maxChunkSize] is whatever the platform reports as the current maximum write length;
 * anything nonsensical falls back to [ATT_DEFAULT_PAYLOAD_SIZE].
 */
fun chunkForWrite(
    bytes: ByteArray,
    maxChunkSize: Int = ATT_DEFAULT_PAYLOAD_SIZE,
): List<ByteArray> {
    val limit = if (maxChunkSize > 0) maxChunkSize else ATT_DEFAULT_PAYLOAD_SIZE
    return (bytes.indices step limit).map { start ->
        bytes.copyOfRange(start, minOf(start + limit, bytes.size))
    }
}
