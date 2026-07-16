package com.bruni.carscan.core.data

import com.bruni.carscan.core.database.createDatabase
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This table is what makes the *second* connection to an adapter fast. None of it is
 * discoverable from a BLE advertisement: which characteristics carry the serial
 * stream, whether the clone echoes what you write to it, whether it is an STN chip
 * that understands expected-frame counts, how many bytes it will take in one write.
 * Learning it costs a slow, failure-prone handshake. Forgetting it means paying that
 * cost on every single connection.
 */
class AdapterRepositoryTest {

    private val driver = createTestDriver()
    private val db: CarScanDb = createDatabase(driver)
    private val repo = DefaultAdapterRepository(db)

    private val vgate = AdapterQuirks(
        address = "AA:BB:CC:DD:EE:FF",
        kind = "BLE",
        name = "Vgate iCar Pro",
        gattService = "0000fff0-0000-1000-8000-00805f9b34fb",
        gattWrite = "0000fff2-0000-1000-8000-00805f9b34fb",
        gattNotify = "0000fff1-0000-1000-8000-00805f9b34fb",
        isStn = true,
        supportsExpectedFrames = true,
        echoSuppressionNeeded = false,
        maxWriteChunk = 20,
        protocolNum = 6,
        ewmaRttMs = 42.5,
        lastUsedMs = 1_000,
    )

    @Test
    fun `an unknown adapter is not remembered`() = runTest {
        assertNull(repo.recall("00:00:00:00:00:00"))
    }

    @Test
    fun `every learned quirk survives a round trip`() = runTest {
        repo.remember(vgate)
        assertEquals(vgate, repo.recall(vgate.address))
    }

    /**
     * The same adapter reconnecting must update what we know about it, not append a
     * second row — the address is the identity.
     */
    @Test
    fun `re-pairing the same adapter updates it rather than duplicating it`() = runTest {
        repo.remember(vgate)
        repo.remember(vgate.copy(name = "Vgate iCar Pro BLE", maxWriteChunk = 128, lastUsedMs = 9_000))

        assertEquals(1, repo.all().size)
        val back = repo.recall(vgate.address)!!
        assertEquals("Vgate iCar Pro BLE", back.name)
        assertEquals(128, back.maxWriteChunk)
        assertEquals(9_000, back.lastUsedMs)
    }

    /**
     * The measured round-trip time seeds the scheduler's throughput governor, so the
     * very first poll cycle after a reconnect is already paced for this adapter
     * rather than discovering its limits by overrunning them.
     */
    @Test
    fun `the measured round trip time is updated in place`() = runTest {
        repo.remember(vgate)
        repo.updateRtt(vgate.address, ewmaRttMs = 71.25, atMs = 5_000)

        val back = repo.recall(vgate.address)!!
        assertEquals(71.25, back.ewmaRttMs!!, 1e-9)
        assertEquals(5_000, back.lastUsedMs)
        assertTrue(back.isStn, "updating the rtt must not clobber the learned quirks")
        assertEquals("0000fff2-0000-1000-8000-00805f9b34fb", back.gattWrite)
    }

    @Test
    fun `adapters are listed most recently used first`() = runTest {
        repo.remember(vgate)
        repo.remember(vgate.copy(address = "11:22:33:44:55:66", name = "OBDLink MX+", lastUsedMs = 9_000))

        assertEquals(listOf("OBDLink MX+", "Vgate iCar Pro"), repo.all().map { it.name })
    }

    @Test
    fun `a Wi-Fi adapter has no GATT characteristics and that is not an error`() = runTest {
        val tcp = AdapterQuirks(
            address = "192.168.0.10:35000",
            kind = "TCP",
            name = "ELM327 WiFi",
            gattService = null, gattWrite = null, gattNotify = null,
            isStn = false, supportsExpectedFrames = false, echoSuppressionNeeded = true,
            maxWriteChunk = 512, protocolNum = null, ewmaRttMs = null, lastUsedMs = null,
        )
        repo.remember(tcp)
        assertEquals(tcp, repo.recall(tcp.address))
    }

    @Test
    fun `the negotiated protocol survives a round trip`() = runTest {
        // The column that earns this table. Without it, a reconnect re-runs the ATSP0 protocol
        // search — seconds on a cold bus — and the "second connect is faster" promise is empty.
        // Every other quirk here saves a round trip; this one saves the wait.
        repo.remember(vgate)

        assertEquals(6L, repo.recall(vgate.address)?.protocolNum)
    }

    /** Auto-reconnect at launch needs exactly one adapter: the one last connected. */
    @Test
    fun `lastUsed returns the most recently used adapter`() = runTest {
        repo.remember(vgate)
        repo.remember(vgate.copy(address = "11:22:33:44:55:66", name = "OBDLink MX+", lastUsedMs = 9_000))

        assertEquals("OBDLink MX+", repo.lastUsed()?.name)
    }

    @Test
    fun `lastUsed is null when nothing has ever been remembered`() = runTest {
        assertNull(repo.lastUsed())
    }

    /** An adapter only ever remembered, never actually connected, must not look "most recent". */
    @Test
    fun `lastUsed treats a null last_used_ms as oldest`() = runTest {
        repo.remember(vgate.copy(lastUsedMs = null))
        repo.remember(vgate.copy(address = "11:22:33:44:55:66", name = "OBDLink MX+", lastUsedMs = 9_000))

        assertEquals("OBDLink MX+", repo.lastUsed()?.name)
    }
}
