package com.bruni.carscan.core.data

import com.bruni.carscan.core.database.createDatabase
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `signalset` table wrapped as a port: what lets a non-bundled vehicle's OBDb signalset,
 * fetched once while the phone had internet, be read back with no network at all.
 */
class SignalsetCacheTest {

    private val driver = createTestDriver()
    private val db: CarScanDb = createDatabase(driver)
    private val cache = DefaultSignalsetCache(db)

    @Test
    fun `a repo nobody ever cached is not found`() = runTest {
        assertNull(cache.get("Kia-EV6"))
    }

    @Test
    fun `a cached signalset survives a round trip`() = runTest {
        cache.put("Kia-EV6", "default", etag = "abc123", json = """{"commands":[]}""", fetchedMs = 1_000)

        val cached = cache.get("Kia-EV6")!!
        assertEquals("Kia-EV6", cached.repo)
        assertEquals("default", cached.variant)
        assertEquals("abc123", cached.etag)
        assertEquals("""{"commands":[]}""", cached.json)
        assertEquals(1_000, cached.fetchedMs)
    }

    @Test
    fun `a null etag is a valid cached state`() = runTest {
        cache.put("Kia-EV6", "default", etag = null, json = "{}", fetchedMs = 1_000)
        assertNull(cache.get("Kia-EV6")!!.etag)
    }

    /** A re-download of the same repo+variant replaces what is cached, never duplicates it. */
    @Test
    fun `re-caching the same repo and variant replaces it rather than duplicating it`() = runTest {
        cache.put("Kia-EV6", "default", etag = "abc123", json = "{}", fetchedMs = 1_000)
        cache.put("Kia-EV6", "default", etag = "def456", json = """{"commands":[]}""", fetchedMs = 2_000)

        val cached = cache.get("Kia-EV6")!!
        assertEquals("def456", cached.etag)
        assertEquals("""{"commands":[]}""", cached.json)
        assertEquals(2_000, cached.fetchedMs)
    }

    @Test
    fun `different variants of the same repo are cached independently`() = runTest {
        cache.put("Kia-EV6", "default", etag = "d", json = "default-json", fetchedMs = 1_000)
        cache.put("Kia-EV6", "extended", etag = "e", json = "extended-json", fetchedMs = 2_000)

        assertEquals("default-json", cache.get("Kia-EV6", "default")!!.json)
        assertEquals("extended-json", cache.get("Kia-EV6", "extended")!!.json)
    }
}
