package com.bruni.carscan.core.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The backup file format. See `docs/BACKUP_FORMAT.md` for the byte layout.
 *
 * These records are a **wire format, not domain types**. They deliberately duplicate the
 * shape of `TripSummary`, `Vehicle` and friends rather than serialising those directly:
 * a rename in the domain must not silently change what a file written last year decodes
 * to. Anything that changes here changes [BACKUP_VERSION] with it.
 */
const val BACKUP_FORMAT: String = "carscan-backup"

/**
 * Bumped whenever a *reader* of an older app could no longer make sense of a file.
 *
 * A reader accepts anything at or below its own version and refuses anything above it with
 * [BackupOutcome.UNSUPPORTED_VERSION] — a file from a newer build is not something to guess at
 * when the payload is the user's entire driving history. Adding a new record type does **not**
 * need a bump: unknown records are skipped (see [BackupReader]), which is what makes the format
 * additive.
 */
const val BACKUP_VERSION: Int = 1

/** OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Stored in the file, so it can be raised later. */
const val BACKUP_KDF_ITERATIONS: Int = 210_000

const val BACKUP_KDF: String = "PBKDF2WithHmacSHA256"
const val BACKUP_CIPHER: String = "AES-256-GCM"

/**
 * The one line of plaintext in the file, and the only thing readable without the password.
 *
 * It has to be plaintext — [salt] and [iterations] are the inputs needed to *derive* the key
 * that decrypts everything else. It is not unauthenticated, though: the whole header line is
 * fed to every frame as AES-GCM associated data, so editing the iteration count or swapping the
 * salt makes the first frame fail to authenticate rather than silently weakening the file.
 */
@Serializable
data class BackupHeader(
    val format: String = BACKUP_FORMAT,
    val version: Int = BACKUP_VERSION,
    val kdf: String = BACKUP_KDF,
    val iterations: Int = BACKUP_KDF_ITERATIONS,
    /** Base64, 16 bytes. Fresh per file, which is what lets frame nonces be a plain counter. */
    val salt: String,
    val cipher: String = BACKUP_CIPHER,
)

/** One encrypted frame's payload. */
@Serializable
sealed interface BackupRecord

@Serializable
@SerialName("vehicle")
data class VehicleRecord(
    val id: String,
    val vin: String? = null,
    val make: String? = null,
    val model: String? = null,
    val modelYear: Long? = null,
    val obdbRepo: String? = null,
    val displayName: String? = null,
    val protocolNum: Long? = null,
    val lastConnectedMs: Long? = null,
    val createdMs: Long,
) : BackupRecord

@Serializable
@SerialName("trip")
data class TripRecord(
    val id: String,
    val vehicleId: String? = null,
    val startedMs: Long,
    val endedMs: Long? = null,
    val distanceM: Double,
    val fuelMl: Double,
    val energyWh: Double,
    val maxSpeedKmh: Double,
    val idleMs: Long,
    val sampleCount: Long,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val startAddress: String? = null,
    val endAddress: String? = null,
    val source: String = "OBD",
) : BackupRecord

/**
 * One `trip_series` row, verbatim.
 *
 * [series] is the BLOB as it sits on disk — little-endian IEEE-754, one float per second, NaN
 * for a second the car answered nothing — base64'd. It is *not* re-derived on import, and
 * neither are [minV]/[maxV]/[avgV]: a restored trip whose statistics differ from the backed-up
 * one's would be a silent lie about a drive that already happened.
 */
@Serializable
@SerialName("series")
data class SeriesChunkRecord(
    val tripId: String,
    val signalId: String,
    val chunkIndex: Long,
    val t0S: Long,
    val n: Long,
    val series: String,
    val minV: Double? = null,
    val maxV: Double? = null,
    val avgV: Double? = null,
) : BackupRecord

/** One `trip_gps` row, verbatim. Same base64-of-the-stored-BLOB reasoning as [SeriesChunkRecord]. */
@Serializable
@SerialName("gps")
data class GpsChunkRecord(
    val tripId: String,
    val chunkIndex: Long,
    val t0S: Long,
    val n: Long,
    val lat: String,
    val lon: String,
    val alt: String,
    val speed: String,
    val bearing: String,
) : BackupRecord

@Serializable
@SerialName("event")
data class TripEventRecord(
    val id: String,
    val tripId: String,
    val tsMs: Long,
    val type: String,
    val severity: Double,
    val lat: Double,
    val lon: Double,
) : BackupRecord

@Serializable
@SerialName("dtc")
data class DtcRecord(
    val id: String,
    val vehicleId: String,
    val code: String,
    val status: String,
    val ecu: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val clearedMs: Long? = null,
    val freezeFrame: String? = null,
) : BackupRecord

@Serializable
@SerialName("layout")
data class DashboardLayoutRecord(
    val id: String,
    val vehicleId: String? = null,
    val name: String,
    val isActive: Boolean,
    val layoutJson: String,
) : BackupRecord

/** The user's preferences. One per file; a later one wins, and there is only ever one. */
@Serializable
@SerialName("settings")
data class SettingsRecord(
    val recordTrips: Boolean,
    /** `UnitPreferences.encode()` — the same string DataStore holds. */
    val units: String,
    val keepScreenOn: Boolean,
    val activeVehicleId: String? = null,
    val themeMode: String,
    val gaugeStyle: String,
    val autoReconnect: Boolean,
    val acquisitionSource: String,
    val autoDriveDetectSpeedKmh: Int,
    val backgroundTracking: Boolean,
) : BackupRecord

/** Starred signals, as the `M:`/`S:` tokens `BookmarkRepository` already persists. */
@Serializable
@SerialName("bookmarks")
data class BookmarksRecord(val keys: List<String>) : BackupRecord

/**
 * `encodeDefaults` because every field of [BackupHeader] but the salt is a default and the
 * reader needs all of them. `ignoreUnknownKeys` so a field added to a record in a later
 * version — the additive change that does not bump [BACKUP_VERSION] — does not make an older
 * app throw away a file it could otherwise read.
 */
internal val backupJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    // Not the default "type": TripEventRecord has a `type` column of its own (HARSH_BRAKE and
    // friends), and kotlinx refuses to serialise a class whose property collides with the
    // discriminator. Renaming the discriminator keeps the records mirroring their table columns.
    classDiscriminator = "record"
}

/** How an export or import ended. The UI turns exactly these four into a sentence. */
enum class BackupOutcome {
    OK,

    /** The password did not decrypt the first frame. Nothing was written. */
    WRONG_PASSWORD,

    /** Written by a newer app than this one. Nothing was written. */
    UNSUPPORTED_VERSION,

    /** Not a CarScan backup, truncated, or the storage failed mid-way. */
    FAILED,
}
