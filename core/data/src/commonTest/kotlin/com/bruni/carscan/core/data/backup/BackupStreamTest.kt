package com.bruni.carscan.core.data.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "정확한암호1234"

private fun write(vararg records: BackupRecord, password: String = PASSWORD): ByteArray {
    val sink = ByteBufferSink()
    val writer = BackupWriter(sink, password)
    for (record in records) writer.write(record)
    return sink.bytes()
}

private fun readAll(bytes: ByteArray, password: String = PASSWORD): List<BackupRecord> {
    val reader = BackupReader(sourceOf(bytes), password)
    return generateSequence { reader.next() }.toList()
}

private fun newline(bytes: ByteArray) = bytes.indexOf('\n'.code.toByte())

/**
 * Rewrites the plaintext header line and leaves every ciphertext byte alone. Round-tripping the
 * whole file through a String would mangle the frames into replacement characters and the test
 * would pass for the wrong reason.
 */
private fun editHeader(bytes: ByteArray, from: String, to: String): ByteArray {
    val end = newline(bytes)
    val header = bytes.copyOfRange(0, end).decodeToString().replaceFirst(from, to).encodeToByteArray()
    return header + byteArrayOf('\n'.code.toByte()) + bytes.copyOfRange(end + 1, bytes.size)
}

private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

class BackupStreamTest {

    private val bookmarks = BookmarksRecord(listOf("M:SPEED", "S:ENGINE_RPM"))
    private val vehicle = VehicleRecord(id = "veh-1", vin = "VIN1", make = "Kia", createdMs = 7)

    @Test
    fun `records come back in the order they were written`() {
        assertEquals(listOf<BackupRecord>(bookmarks, vehicle), readAll(write(bookmarks, vehicle)))
    }

    @Test
    fun `the header is plaintext and names the format and version`() {
        val header = write(vehicle).let { it.copyOfRange(0, newline(it)) }.decodeToString()
        assertTrue(header.contains("\"format\":\"$BACKUP_FORMAT\""), header)
        assertTrue(header.contains("\"version\":$BACKUP_VERSION"), header)
        assertTrue(header.contains("\"iterations\":$BACKUP_KDF_ITERATIONS"), header)
        assertTrue(header.contains("\"cipher\":\"$BACKUP_CIPHER\""), header)
    }

    /** The payload must not be sitting in the file in the clear. */
    @Test
    fun `a recognisable value from a record does not appear in the bytes`() {
        val bytes = write(VehicleRecord(id = "veh-1", displayName = "내 아반떼", createdMs = 0))
        assertFalse(bytes.containsBytes("아반떼".encodeToByteArray()))
        assertFalse(bytes.containsBytes("veh-1".encodeToByteArray()))
    }

    @Test
    fun `the wrong password fails instead of producing garbage`() {
        val failure = assertFailsWith<BackupFormatException> {
            readAll(write(vehicle), password = "틀린암호1234")
        }
        assertEquals(BackupOutcome.WRONG_PASSWORD, failure.outcome)
    }

    /**
     * The header carries the salt and the iteration count in the clear, so it has to be
     * *authenticated* even though it is not encrypted — otherwise anyone could rewrite the
     * iteration count down to 1 and hand the file back to be re-encrypted at that strength.
     */
    @Test
    fun `editing the plaintext header breaks authentication`() {
        val tampered = editHeader(
            write(vehicle),
            "\"iterations\":$BACKUP_KDF_ITERATIONS",
            "\"iterations\":$BACKUP_KDF_ITERATIONS ",
        )
        val failure = assertFailsWith<BackupFormatException> { readAll(tampered) }
        assertEquals(BackupOutcome.WRONG_PASSWORD, failure.outcome)
    }

    @Test
    fun `flipping one byte of ciphertext is caught`() {
        val bytes = write(vehicle)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()

        val failure = assertFailsWith<BackupFormatException> { readAll(bytes) }
        assertEquals(BackupOutcome.WRONG_PASSWORD, failure.outcome)
    }

    @Test
    fun `a file from a newer app is refused, not guessed at`() {
        val tampered = editHeader(
            write(vehicle),
            "\"version\":$BACKUP_VERSION",
            "\"version\":${BACKUP_VERSION + 1}",
        )
        val failure = assertFailsWith<BackupFormatException> { readAll(tampered) }
        assertEquals(BackupOutcome.UNSUPPORTED_VERSION, failure.outcome)
    }

    @Test
    fun `something that is not a backup at all is refused`() {
        val failure = assertFailsWith<BackupFormatException> {
            readAll("이건 그냥 텍스트 파일입니다\n그리고 두 번째 줄\n".encodeToByteArray())
        }
        assertEquals(BackupOutcome.FAILED, failure.outcome)
    }

    @Test
    fun `a truncated file is refused rather than half-read`() {
        val bytes = write(vehicle, vehicle)
        val failure = assertFailsWith<BackupFormatException> { readAll(bytes.copyOf(bytes.size - 4)) }
        assertEquals(BackupOutcome.FAILED, failure.outcome)
    }

    /**
     * Frame nonces are a counter, which is only safe because the key is single-use. If two
     * identical records ever encrypted to identical bytes the counter would have stalled, and a
     * two-time pad would be sitting in the user's backup.
     */
    @Test
    fun `two identical records do not encrypt to the same bytes`() {
        val bytes = write(vehicle, vehicle)
        val first = newline(bytes) + 1
        val length = bytes.copyOfRange(first, first + 4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

        assertEquals(2, readAll(bytes).size)
        assertFalse(
            bytes.copyOfRange(first + 4, first + 4 + length)
                .contentEquals(bytes.copyOfRange(first + 8 + length, first + 8 + 2 * length)),
        )
    }

    /** Two exports of the same data must not share a key — the per-file salt is what guarantees it. */
    @Test
    fun `each file gets its own salt`() {
        fun header(bytes: ByteArray) = bytes.copyOfRange(0, newline(bytes)).decodeToString()
        assertNotEquals(header(write(vehicle)), header(write(vehicle)))
    }

    @Test
    fun `an empty backup reads back as no records`() {
        assertEquals(emptyList(), readAll(write()))
    }

    /** GCM verifies before it yields — a bad key produces nothing, never plausible bytes. */
    @Test
    fun `opening with the wrong key returns null instead of bytes`() {
        val key = deriveBackupKey(PASSWORD, ByteArray(16), 1_000)
        val other = deriveBackupKey("다른암호", ByteArray(16), 1_000)
        val nonce = ByteArray(12)
        val sealed = aesGcmSeal(key, nonce, ByteArray(0), "속도 88".encodeToByteArray())

        assertEquals("속도 88", aesGcmOpen(key, nonce, ByteArray(0), sealed)!!.decodeToString())
        assertNull(aesGcmOpen(other, nonce, ByteArray(0), sealed))
    }

    @Test
    fun `the derived key is 256 bits and depends on the salt`() {
        val a = deriveBackupKey(PASSWORD, ByteArray(16) { 1 }, 1_000)
        val b = deriveBackupKey(PASSWORD, ByteArray(16) { 2 }, 1_000)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
    }
}
