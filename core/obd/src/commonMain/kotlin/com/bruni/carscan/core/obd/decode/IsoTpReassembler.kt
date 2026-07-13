package com.bruni.carscan.core.obd.decode

/** A complete ISO-TP message: the service response bytes, still with the echo on the front. */
class IsoTpMessage(val canId: String, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is IsoTpMessage && canId == other.canId && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * canId.hashCode() + data.contentHashCode()

    override fun toString(): String = "IsoTpMessage($canId, ${data.size} bytes)"
}

/**
 * Reassembles ISO-TP messages from the individual CAN frames the ELM327 prints.
 *
 * Buffers are keyed by CAN id because several ECUs can be answering at the same moment
 * — a broadcast request to 7DF is answered by every ECU that supports the PID, each on
 * its own id. A single buffer would interleave them into one message that is the right
 * length and completely wrong, which is the worst possible failure here.
 *
 * Not thread-safe; it is fed from the one coroutine that owns the ELM stream.
 */
class IsoTpReassembler {

    private class Buffer(val declaredLen: Int) {
        val data = ArrayList<Byte>(declaredLen)
        var expectedSeq = 1
    }

    private val buffers = HashMap<String, Buffer>()

    /**
     * Feeds one frame, returning any message it completed.
     *
     * Returns a list rather than a nullable because that is what the caller wants to
     * flatMap over; a single frame can never complete more than one message.
     */
    fun feed(frame: RawFrame): List<IsoTpMessage> {
        val b = frame.bytes
        if (b.isEmpty()) return emptyList()

        val pci = b[0].toInt() and 0xFF
        return when (pci shr 4) {
            0x0 -> single(frame, b, pci and 0x0F)
            0x1 -> first(frame, b)
            0x2 -> consecutive(frame, b, pci and 0x0F)
            // 0x3 is flow control, which ATCAF1 sends on our behalf. We never send one,
            // but adapters echo them back, so seeing one is normal and means nothing.
            else -> emptyList()
        }
    }

    /** Drops every open buffer. Call on reconnect or after a protocol desync. */
    fun reset() = buffers.clear()

    private fun single(frame: RawFrame, b: ByteArray, len: Int): List<IsoTpMessage> {
        // len 0 is the CAN-FD escape, which classic ISO-TP never emits.
        if (len == 0 || len > 7 || b.size < 1 + len) return emptyList()
        buffers.remove(frame.canId)
        return listOf(IsoTpMessage(frame.canId, b.copyOfRange(1, 1 + len)))
    }

    private fun first(frame: RawFrame, b: ByteArray): List<IsoTpMessage> {
        if (b.size < 2) return emptyList()
        val declared = ((b[0].toInt() and 0x0F) shl 8) or (b[1].toInt() and 0xFF)
        if (declared == 0) return emptyList()

        // A new first frame supersedes whatever was half-built on this id.
        val buf = Buffer(declared)
        buffers[frame.canId] = buf
        for (i in 2 until b.size) buf.data += b[i]
        return complete(frame.canId, buf)
    }

    private fun consecutive(frame: RawFrame, b: ByteArray, seq: Int): List<IsoTpMessage> {
        val buf = buffers[frame.canId] ?: return emptyList()   // tuned in mid-message

        if (seq != buf.expectedSeq) {
            // A gap means frames were dropped. Everything after the gap belongs at an
            // offset we cannot know, so the message is unrecoverable — drop it rather
            // than emit something that will decode without complaint.
            buffers.remove(frame.canId)
            return emptyList()
        }
        buf.expectedSeq = (seq + 1) and 0x0F

        for (i in 1 until b.size) buf.data += b[i]
        return complete(frame.canId, buf)
    }

    private fun complete(canId: String, buf: Buffer): List<IsoTpMessage> {
        if (buf.data.size < buf.declaredLen) return emptyList()
        buffers.remove(canId)
        // The final frame is padded out to 8 bytes; only the declared length is ours.
        val out = ByteArray(buf.declaredLen) { buf.data[it] }
        return listOf(IsoTpMessage(canId, out))
    }
}
