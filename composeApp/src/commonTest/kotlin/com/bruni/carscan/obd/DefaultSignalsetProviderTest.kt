package com.bruni.carscan.obd

import com.bruni.carscan.core.data.CachedSignalset
import com.bruni.carscan.core.data.SignalsetAvailability
import com.bruni.carscan.core.data.SignalsetCache
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private const val BUNDLED_JSON = """{"commands": [{"hdr": "7E0", "cmd": {"01": "0C"}}]}"""
private const val DOWNLOADED_JSON = """{"commands": [{"hdr": "744", "cmd": {"22": "E003"}}]}"""

/**
 * [DefaultSignalsetProvider] against the frozen [com.bruni.carscan.core.data.SignalsetProvider]
 * port: bundled-or-cached-or-downloaded precedence, and the online/offline branches
 * `ensureAvailable` has to get right at pick time.
 */
class DefaultSignalsetProviderTest {

    @Test
    fun `cachedJson prefers the bundled asset over the cache`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(mapOf("Kia-EV6" to cached(json = "stale-cache"))),
            downloader = FakeSignalsetDownloader(DownloadResult.Failed),
            readAsset = assetsOf("files/obdb/Kia-EV6.json" to BUNDLED_JSON),
        )

        provider.cachedJson("Kia-EV6") shouldBe BUNDLED_JSON
    }

    @Test
    fun `cachedJson falls back to the cache when there is no bundled asset`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(mapOf("Toyota-RAV4" to cached(json = DOWNLOADED_JSON))),
            downloader = FakeSignalsetDownloader(DownloadResult.Failed),
            readAsset = assetsOf(),
        )

        provider.cachedJson("Toyota-RAV4") shouldBe DOWNLOADED_JSON
    }

    @Test
    fun `cachedJson is null with neither a bundled asset nor a cached copy, and never touches the network`() =
        runTest {
            val downloader = FakeSignalsetDownloader(DownloadResult.Fetched(DOWNLOADED_JSON, etag = null))
            val provider = DefaultSignalsetProvider(
                cache = FakeSignalsetCache(),
                downloader = downloader,
                readAsset = assetsOf(),
            )

            provider.cachedJson("Toyota-RAV4") shouldBe null
            downloader.calls shouldBe 0
        }

    @Test
    fun `a bundled asset short-circuits ensureAvailable to AVAILABLE without calling the downloader`() = runTest {
        val downloader = FakeSignalsetDownloader(DownloadResult.Failed)
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(),
            downloader = downloader,
            readAsset = assetsOf("files/obdb/Kia-EV6.json" to BUNDLED_JSON),
        )

        provider.ensureAvailable("Kia-EV6") shouldBe SignalsetAvailability.AVAILABLE
        downloader.calls shouldBe 0
    }

    @Test
    fun `a 304 against a cached copy is AVAILABLE`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(mapOf("Toyota-RAV4" to cached(etag = "abc", json = DOWNLOADED_JSON))),
            downloader = FakeSignalsetDownloader(DownloadResult.NotModified),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Toyota-RAV4") shouldBe SignalsetAvailability.AVAILABLE
    }

    @Test
    fun `a fresh download is cached and reported as DOWNLOADED`() = runTest {
        val cache = FakeSignalsetCache()
        val provider = DefaultSignalsetProvider(
            cache = cache,
            downloader = FakeSignalsetDownloader(DownloadResult.Fetched(DOWNLOADED_JSON, etag = "new-etag")),
            readAsset = assetsOf(),
            nowMs = { 42_000 },
        )

        provider.ensureAvailable("Toyota-RAV4") shouldBe SignalsetAvailability.DOWNLOADED

        val stored = cache.get("Toyota-RAV4")!!
        stored.json shouldBe DOWNLOADED_JSON
        stored.etag shouldBe "new-etag"
        stored.fetchedMs shouldBe 42_000
    }

    @Test
    fun `offline with a cached copy still reports AVAILABLE`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(mapOf("Toyota-RAV4" to cached(json = DOWNLOADED_JSON))),
            downloader = FakeSignalsetDownloader(DownloadResult.NoNetwork),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Toyota-RAV4") shouldBe SignalsetAvailability.AVAILABLE
    }

    @Test
    fun `offline with no cached copy reports NO_NETWORK`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(),
            downloader = FakeSignalsetDownloader(DownloadResult.NoNetwork),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Toyota-RAV4") shouldBe SignalsetAvailability.NO_NETWORK
    }

    @Test
    fun `OBDb 404 reports NOT_FOUND`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(),
            downloader = FakeSignalsetDownloader(DownloadResult.NotFound),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Some-Renamed-Repo") shouldBe SignalsetAvailability.NOT_FOUND
    }

    @Test
    fun `any other failure reports FAILED`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(),
            downloader = FakeSignalsetDownloader(DownloadResult.Failed),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Toyota-RAV4") shouldBe SignalsetAvailability.FAILED
    }

    /** The cached ETag, not null, must be what a revalidation is sent with. */
    @Test
    fun `ensureAvailable revalidates with the cached ETag`() = runTest {
        val downloader = FakeSignalsetDownloader(DownloadResult.NotModified)
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(mapOf("Toyota-RAV4" to cached(etag = "cached-etag", json = "{}"))),
            downloader = downloader,
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Toyota-RAV4")

        downloader.lastEtag shouldBe "cached-etag"
    }
}

private fun cached(etag: String? = null, json: String) =
    CachedSignalset(repo = "unused", variant = "default", etag = etag, fetchedMs = 0, json = json)

private fun assetsOf(vararg pairs: Pair<String, String>): suspend (String) -> String {
    val assets = pairs.toMap()
    return { path -> assets[path] ?: error("No fake asset at $path") }
}

private class FakeSignalsetCache(seed: Map<String, CachedSignalset> = emptyMap()) : SignalsetCache {
    private val store = seed.toMutableMap()

    override suspend fun get(repo: String, variant: String): CachedSignalset? = store[repo]

    override suspend fun put(repo: String, variant: String, etag: String?, json: String, fetchedMs: Long) {
        store[repo] = CachedSignalset(repo, variant, etag, fetchedMs, json)
    }
}

private class FakeSignalsetDownloader(private val result: DownloadResult) : SignalsetDownloader {
    var calls: Int = 0
        private set
    var lastEtag: String? = null
        private set

    override suspend fun fetch(repo: String, variant: String, etag: String?): DownloadResult {
        calls++
        lastEtag = etag
        return result
    }
}
