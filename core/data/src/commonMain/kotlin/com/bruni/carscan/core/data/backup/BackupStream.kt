package com.bruni.carscan.core.data.backup

import kotlinx.serialization.SerializationException

/**
 * A file the user picked, as somewhere to put bytes. The platform opens and closes it —
 * Android's SAF hands out an `OutputStream` that only lives inside a `use` block — so there is
 * no `close()` here to get wrong.
 */
fun interface BackupSink {
    fun write(bytes: ByteArray)
}

/** The read side. Returns the number of bytes read, or -1 at end of file, exactly like a stream. */
fun interface BackupSource {
    fun read(destination: ByteArray, offset: Int, length: Int): Int
}

/** Aborts a read. [outcome] is what the user is told. */
class BackupFormatException(val outcome: BackupOutcome, message: String) : Exception(message)

/** 96 bits — the only nonce length AES-GCM is specified for without an extra hashing step. */
private const val NONCE_SIZE = 12
private const val SALT_SIZE = 16
private const val LENGTH_PREFIX = 4

/** A header line longer than this is not a header line; it is a file that is not ours. */
private const val MAX_HEADER_BYTES = 4096

/**
 * A frame bigger than this cannot be one of ours — the largest record is a 10-minute GPS chunk,
 * about 40 KB base64'd. The cap exists so a corrupt length prefix allocates nothing.
 */
private const val MAX_FRAME_BYTES = 1 shl 20

/**
 * Writes records into [sink] as encrypted frames.
 *
 * The header line goes out at construction, because [BackupHeader.salt] has to be on disk before
 * anything encrypted with the key derived from it.
 *
 * Nonces are the frame index, not random. That is safe here and nowhere else: the salt is fresh
 * per file, so the key is too, and a counter under a single-use key cannot repeat. (Random 12-byte
 * nonces would be the alternative, and would have to be stored per frame.)
 */
class BackupWriter(private val sink: BackupSink, password: String) {

    private val header = BackupHeader(salt = base64Encode(randomBytes(SALT_SIZE)))
    private val headerLine = (backupJson.encodeToString(header) + "\n").encodeToByteArray()
    private val key = deriveBackupKey(password, base64Decode(header.salt), header.iterations)
    private var frame = 0L

    init {
        sink.write(headerLine)
    }

    fun write(record: BackupRecord) {
        val plaintext = backupJson.encodeToString(BackupRecord.serializer(), record).encodeToByteArray()
        val sealed = aesGcmSeal(key, nonceFor(frame++), headerLine, plaintext)
        sink.write(bigEndian(sealed.size))
        sink.write(sealed)
    }
}

/**
 * Reads back what [BackupWriter] wrote.
 *
 * The constructor reads and validates the header, so a file that is not a backup, or one from a
 * newer app, is rejected before a single row is touched. The password is not checked here — it
 * cannot be, since a KDF has no notion of "wrong" — but the *first* [next] settles it, and that
 * still happens before anything is written.
 */
class BackupReader(private val source: BackupSource, password: String) {

    private val headerLine: ByteArray
    private val key: ByteArray
    private var frame = 0L

    init {
        headerLine = readHeaderLine()
        val header = try {
            backupJson.decodeFromString<BackupHeader>(headerLine.decodeToString().trimEnd('\n'))
        } catch (e: SerializationException) {
            throw BackupFormatException(BackupOutcome.FAILED, "not a CarScan backup: ${e.message}")
        }
        if (header.format != BACKUP_FORMAT) {
            throw BackupFormatException(BackupOutcome.FAILED, "not a CarScan backup: ${header.format}")
        }
        if (header.version > BACKUP_VERSION) {
            throw BackupFormatException(
                BackupOutcome.UNSUPPORTED_VERSION,
                "backup version ${header.version} is newer than $BACKUP_VERSION",
            )
        }
        key = deriveBackupKey(password, base64Decode(header.salt), header.iterations)
    }

    /**
     * The next record, or null at end of file.
     *
     * A frame that decrypts but does not decode is a record type this build does not know —
     * a newer app's addition — and is skipped rather than fatal. That skip is what lets a new
     * record type ship without bumping [BACKUP_VERSION]. It cannot hide corruption: GCM
     * authenticated the bytes before they got here.
     */
    fun next(): BackupRecord? {
        while (true) {
            val lengthBytes = ByteArray(LENGTH_PREFIX)
            if (!readFully(lengthBytes)) return null
            val length = readInt(lengthBytes)
            if (length <= 0 || length > MAX_FRAME_BYTES) {
                throw BackupFormatException(BackupOutcome.FAILED, "frame length $length is not plausible")
            }
            val sealed = ByteArray(length)
            if (!readFully(sealed)) {
                throw BackupFormatException(BackupOutcome.FAILED, "file ends inside a frame")
            }
            val plaintext = aesGcmOpen(key, nonceFor(frame++), headerLine, sealed)
                ?: throw BackupFormatException(
                    // Frame 0 failing is overwhelmingly a wrong password. A later frame failing
                    // means an edited file, and the same "we cannot read this" answer serves.
                    BackupOutcome.WRONG_PASSWORD,
                    "frame ${frame - 1} did not authenticate",
                )
            try {
                return backupJson.decodeFromString(BackupRecord.serializer(), plaintext.decodeToString())
            } catch (_: SerializationException) {
                continue
            }
        }
    }

    private fun readHeaderLine(): ByteArray {
        val out = ArrayList<Byte>(256)
        val one = ByteArray(1)
        while (out.size < MAX_HEADER_BYTES) {
            if (source.read(one, 0, 1) != 1) break
            out.add(one[0])
            if (one[0] == '\n'.code.toByte()) return out.toByteArray()
        }
        throw BackupFormatException(BackupOutcome.FAILED, "no backup header")
    }

    /** True when [destination] was filled, false at a clean end of file, throws on a partial read. */
    private fun readFully(destination: ByteArray): Boolean {
        var offset = 0
        while (offset < destination.size) {
            val read = source.read(destination, offset, destination.size - offset)
            if (read <= 0) {
                if (offset == 0) return false
                throw BackupFormatException(BackupOutcome.FAILED, "file ends mid-record")
            }
            offset += read
        }
        return true
    }
}

/** 4 zero bytes then the frame index, big-endian — see [BackupWriter]. */
private fun nonceFor(frame: Long): ByteArray = ByteArray(NONCE_SIZE).also {
    for (i in 0 until 8) it[NONCE_SIZE - 1 - i] = (frame ushr (8 * i)).toByte()
}

private fun bigEndian(value: Int) = ByteArray(4) { (value ushr (8 * (3 - it))).toByte() }

private fun readInt(bytes: ByteArray): Int {
    var value = 0
    for (b in bytes) value = (value shl 8) or (b.toInt() and 0xFF)
    return value
}
