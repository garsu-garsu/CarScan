package com.bruni.carscan.core.data.backup

/** Collects everything written, so a test can hand the same bytes straight back to a reader. */
class ByteBufferSink : BackupSink {
    private val chunks = mutableListOf<ByteArray>()

    override fun write(bytes: ByteArray) {
        chunks.add(bytes.copyOf())
    }

    fun bytes(): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }
}

/**
 * Reads [bytes] back in small bites on purpose — 7 at a time, never aligned to a frame — so a
 * reader that assumes one `read` returns everything it asked for fails here rather than on a
 * real SAF stream.
 */
fun sourceOf(bytes: ByteArray, bite: Int = 7): BackupSource {
    var position = 0
    return BackupSource { destination, offset, length ->
        if (position >= bytes.size) {
            -1
        } else {
            val count = minOf(length, bite, bytes.size - position)
            bytes.copyInto(destination, offset, position, position + count)
            position += count
            count
        }
    }
}
