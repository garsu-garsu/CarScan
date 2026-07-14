package com.bruni.carscan.core.data

import com.bruni.carscan.db.Adapter
import com.bruni.carscan.db.CarScanDb

/**
 * Everything we had to learn the hard way about one physical adapter.
 *
 * None of it is in the BLE advertisement: which GATT characteristics carry the serial
 * stream, whether the clone echoes what you write to it, whether it is an STN chip
 * that understands expected-frame counts, how many bytes it accepts per write.
 * Discovering it costs a slow, failure-prone handshake — so it is written down, and
 * the second connection to the same adapter is fast because of this table.
 */
data class AdapterQuirks(
    val address: String,
    /** BLE / SPP / TCP. */
    val kind: String,
    val name: String?,
    val gattService: String?,
    val gattWrite: String?,
    val gattNotify: String?,
    val isStn: Boolean,
    val supportsExpectedFrames: Boolean,
    val echoSuppressionNeeded: Boolean,
    val maxWriteChunk: Long,
    /**
     * The protocol ATDPN reported, e.g. 6 for ISO 15765-4 11-bit.
     *
     * The one field here that saves *seconds* rather than round trips: seeded back into the
     * session before connect(), the initializer sends a single ATSP{n} instead of running a
     * protocol search on a cold bus.
     */
    val protocolNum: Long?,
    /** Exponentially weighted mean round-trip time; seeds the scheduler's governor. */
    val ewmaRttMs: Double?,
    val lastUsedMs: Long?,
)

interface AdapterRepository {
    suspend fun remember(quirks: AdapterQuirks)
    suspend fun recall(address: String): AdapterQuirks?
    suspend fun all(): List<AdapterQuirks>
    suspend fun updateRtt(address: String, ewmaRttMs: Double, atMs: Long)
    suspend fun forget(address: String)
}

class DefaultAdapterRepository(private val db: CarScanDb) : AdapterRepository {

    override suspend fun remember(quirks: AdapterQuirks) {
        db.adapterQueries.upsert(
            address = quirks.address,
            kind = quirks.kind,
            name = quirks.name,
            gatt_service = quirks.gattService,
            gatt_write = quirks.gattWrite,
            gatt_notify = quirks.gattNotify,
            is_stn = quirks.isStn.toLong(),
            supports_expected_frames = quirks.supportsExpectedFrames.toLong(),
            echo_suppression_needed = quirks.echoSuppressionNeeded.toLong(),
            max_write_chunk = quirks.maxWriteChunk,
            protocol_num = quirks.protocolNum,
            ewma_rtt_ms = quirks.ewmaRttMs,
            last_used_ms = quirks.lastUsedMs,
        )
    }

    override suspend fun recall(address: String): AdapterQuirks? =
        db.adapterQueries.selectByAddress(address).executeAsOneOrNull()?.toQuirks()

    override suspend fun all(): List<AdapterQuirks> =
        db.adapterQueries.selectAll().executeAsList().map(Adapter::toQuirks)

    /** Updates only the timing. The learned quirks are not re-derived on every poll. */
    override suspend fun updateRtt(address: String, ewmaRttMs: Double, atMs: Long) {
        db.adapterQueries.updateRtt(ewma_rtt_ms = ewmaRttMs, last_used_ms = atMs, address = address)
    }

    override suspend fun forget(address: String) {
        db.adapterQueries.deleteByAddress(address)
    }
}

private fun Boolean.toLong(): Long = if (this) 1L else 0L

private fun Adapter.toQuirks() = AdapterQuirks(
    address = address,
    kind = kind,
    name = name,
    gattService = gatt_service,
    gattWrite = gatt_write,
    gattNotify = gatt_notify,
    isStn = is_stn != 0L,
    supportsExpectedFrames = supports_expected_frames != 0L,
    echoSuppressionNeeded = echo_suppression_needed != 0L,
    maxWriteChunk = max_write_chunk,
    protocolNum = protocol_num,
    ewmaRttMs = ewma_rtt_ms,
    lastUsedMs = last_used_ms,
)
