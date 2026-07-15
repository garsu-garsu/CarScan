package com.bruni.carscan.core.data

import com.bruni.carscan.db.CarScanDb

/** A signalset JSON downloaded from OBDb once and kept on the device, ETag included. */
data class CachedSignalset(
    val repo: String,
    val variant: String,
    val etag: String?,
    val fetchedMs: Long,
    val json: String,
)

/**
 * The `signalset` table (`:core:database`), as a port: what lets a vehicle's OBDb signalset,
 * fetched once while the phone had internet, be read back with no network at all afterwards.
 */
interface SignalsetCache {
    suspend fun get(repo: String, variant: String = "default"): CachedSignalset?

    suspend fun put(repo: String, variant: String, etag: String?, json: String, fetchedMs: Long)
}

class DefaultSignalsetCache(private val db: CarScanDb) : SignalsetCache {

    override suspend fun get(repo: String, variant: String): CachedSignalset? =
        db.signalsetQueries.selectByRepoVariant(repo, variant).executeAsOneOrNull()?.toCached()

    override suspend fun put(repo: String, variant: String, etag: String?, json: String, fetchedMs: Long) {
        db.signalsetQueries.upsert(repo = repo, variant = variant, etag = etag, fetched_ms = fetchedMs, json = json)
    }
}

private fun com.bruni.carscan.db.Signalset.toCached() = CachedSignalset(
    repo = repo,
    variant = variant,
    etag = etag,
    fetchedMs = fetched_ms,
    json = json,
)
