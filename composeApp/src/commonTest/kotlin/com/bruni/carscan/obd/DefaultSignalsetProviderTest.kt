package com.bruni.carscan.obd

import com.bruni.carscan.core.data.CachedSignalset
import com.bruni.carscan.core.data.SignalsetAvailability
import com.bruni.carscan.core.data.SignalsetCache
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private const val BUNDLED_JSON = """{"commands": [{"hdr": "7E0", "cmd": {"01": "0C"}}]}"""
private const val DOWNLOADED_JSON = """{"commands": [{"hdr": "744", "cmd": {"22": "E003"}}]}"""

/** Verbatim what `OBDb/Kia-Sorento`, `Kia-Niro` and `Kia-EV3` publish at `signalsets/v3/default.json`. */
private const val EMPTY_JSON = """{"commands": []}"""

/** Stands in for the make repo the empty ones defer to — `OBDb/Kia` defines 79 commands. */
private const val MAKE_JSON = """{"commands": [{"hdr": "7B3", "cmd": {"22": "C00B"}}]}"""

/**
 * [DefaultSignalsetProvider] against the frozen [com.bruni.carscan.core.data.SignalsetProvider]
 * port: bundled-or-cached-or-downloaded precedence, the online/offline branches `ensureAvailable`
 * has to get right at pick time, and the make fallback over all of them.
 */
class DefaultSignalsetProviderTest {

    @Test
    fun `cachedJson prefers the bundled asset over the cache`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(mapOf("Kia-EV6" to cached(json = "stale-cache"))),
            downloader = FakeSignalsetDownloader(DownloadResult.Failed),
            readAsset = assetsOf("files/obdb/Kia-EV6.json" to BUNDLED_JSON),
        )

        provider.cachedJson("Kia-EV6", make = null) shouldBe BUNDLED_JSON
    }

    @Test
    fun `cachedJson falls back to the cache when there is no bundled asset`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(mapOf("Toyota-RAV4" to cached(json = DOWNLOADED_JSON))),
            downloader = FakeSignalsetDownloader(DownloadResult.Failed),
            readAsset = assetsOf(),
        )

        provider.cachedJson("Toyota-RAV4", make = null) shouldBe DOWNLOADED_JSON
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

            provider.cachedJson("Toyota-RAV4", make = null) shouldBe null
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

        provider.ensureAvailable("Kia-EV6", make = null) shouldBe SignalsetAvailability.AVAILABLE
        downloader.calls shouldBe 0
    }

    @Test
    fun `a 304 against a cached copy is AVAILABLE`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(mapOf("Toyota-RAV4" to cached(etag = "abc", json = DOWNLOADED_JSON))),
            downloader = FakeSignalsetDownloader(DownloadResult.NotModified),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Toyota-RAV4", make = null) shouldBe SignalsetAvailability.AVAILABLE
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

        provider.ensureAvailable("Toyota-RAV4", make = null) shouldBe SignalsetAvailability.DOWNLOADED

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

        provider.ensureAvailable("Toyota-RAV4", make = null) shouldBe SignalsetAvailability.AVAILABLE
    }

    @Test
    fun `offline with no cached copy reports NO_NETWORK`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(),
            downloader = FakeSignalsetDownloader(DownloadResult.NoNetwork),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Toyota-RAV4", make = null) shouldBe SignalsetAvailability.NO_NETWORK
    }

    @Test
    fun `OBDb 404 reports NOT_FOUND`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(),
            downloader = FakeSignalsetDownloader(DownloadResult.NotFound),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Some-Renamed-Repo", make = null) shouldBe SignalsetAvailability.NOT_FOUND
    }

    @Test
    fun `any other failure reports FAILED`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(),
            downloader = FakeSignalsetDownloader(DownloadResult.Failed),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Toyota-RAV4", make = null) shouldBe SignalsetAvailability.FAILED
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

        provider.ensureAvailable("Toyota-RAV4", make = null)

        downloader.lastEtag shouldBe "cached-etag"
    }

    // ---- the make fallback ----------------------------------------------------------------
    //
    // 504 of the catalog's 654 vehicles publish `{"commands": []}` and keep their real
    // definitions in the MAKE repo; 440 of those the make repo does define. Without the
    // fallback a Kia Sorento offers the standard mode-01 PIDs and nothing else, on a real car.

    @Test
    fun `cachedJson falls back to the make when the vehicle's signalset defines no commands`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(
                mapOf(
                    "Kia-Sorento" to cached(json = EMPTY_JSON),
                    "Kia" to cached(json = MAKE_JSON),
                ),
            ),
            // The connect path may be on a Wi-Fi ELM327's own access point. Nothing here may
            // reach the network, whatever the vehicle's signalset turns out to contain.
            downloader = FakeSignalsetDownloader(DownloadResult.NoNetwork),
            readAsset = assetsOf(),
        )

        provider.cachedJson("Kia-Sorento", make = "Kia") shouldBe MAKE_JSON
    }

    /**
     * The one that keeps this a fallback instead of a merge. The make repo is a superset — every
     * one of Ford-F-150's 116 commands also appears in `OBDb/Ford` — so a union would make command
     * resolution ambiguous for every vehicle that does define its own.
     *
     * [DOWNLOADED_JSON] is also schema-minimal enough that `SignalsetParser` rejects it, which
     * pins the second half of the rule: a document the strict parser cannot decode is still a
     * document with commands, and must not be replaced by the make's.
     */
    @Test
    fun `cachedJson never consults the make when the vehicle defines its own commands`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(
                mapOf(
                    "Kia-EV6" to cached(json = DOWNLOADED_JSON),
                    "Kia" to cached(json = MAKE_JSON),
                ),
            ),
            downloader = FakeSignalsetDownloader(DownloadResult.NoNetwork),
            readAsset = assetsOf(),
        )

        provider.cachedJson("Kia-EV6", make = "Kia") shouldBe DOWNLOADED_JSON
    }

    /** Makes whose name is two words are hyphenated upstream: `OBDb/Land-Rover`, not `OBDb/Land`. */
    @Test
    fun `a two-word make resolves to its hyphenated OBDb repo`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(
                mapOf(
                    "Land-Rover-Defender" to cached(json = EMPTY_JSON),
                    "Land-Rover" to cached(json = MAKE_JSON),
                ),
            ),
            downloader = FakeSignalsetDownloader(DownloadResult.NoNetwork),
            readAsset = assetsOf(),
        )

        provider.cachedJson("Land-Rover-Defender", make = "Land Rover") shouldBe MAKE_JSON
    }

    /**
     * The whole point of doing this at pick time. Downloads happen while the user picks the
     * vehicle, online; the fallback runs at connect, possibly offline. If the make's copy is not
     * cached here it is empty exactly when it is needed.
     */
    @Test
    fun `ensureAvailable caches the make too, so the fallback survives going offline`() = runTest {
        val cache = FakeSignalsetCache()
        val online = FakeSignalsetDownloader(
            result = DownloadResult.Fetched(EMPTY_JSON, etag = null),
            byRepo = mapOf("Kia" to DownloadResult.Fetched(MAKE_JSON, etag = null)),
        )
        val pick = DefaultSignalsetProvider(cache = cache, downloader = online, readAsset = assetsOf())

        // Pick time, online. The vehicle's own repo is what the user is told about.
        pick.ensureAvailable("Kia-Sorento", make = "Kia") shouldBe SignalsetAvailability.DOWNLOADED
        online.repos shouldBe listOf("Kia-Sorento", "Kia")

        // Connect time, offline, same device cache and a downloader that would fail.
        val connect = DefaultSignalsetProvider(
            cache = cache,
            downloader = FakeSignalsetDownloader(DownloadResult.NoNetwork),
            readAsset = assetsOf(),
        )
        connect.cachedJson("Kia-Sorento", make = "Kia") shouldBe MAKE_JSON
    }

    @Test
    fun `ensureAvailable never fetches the make when the vehicle defines its own commands`() = runTest {
        val downloader = FakeSignalsetDownloader(DownloadResult.Fetched(DOWNLOADED_JSON, etag = null))
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(),
            downloader = downloader,
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Kia-EV6", make = "Kia") shouldBe SignalsetAvailability.DOWNLOADED

        downloader.repos shouldBe listOf("Kia-EV6")
    }

    /** A make with nothing to offer must not turn a working pick into a failure. */
    @Test
    fun `a make repo that 404s leaves the vehicle's own availability alone`() = runTest {
        val provider = DefaultSignalsetProvider(
            cache = FakeSignalsetCache(),
            downloader = FakeSignalsetDownloader(
                result = DownloadResult.Fetched(EMPTY_JSON, etag = null),
                byRepo = mapOf("Aston-Martin" to DownloadResult.NotFound),
            ),
            readAsset = assetsOf(),
        )

        provider.ensureAvailable("Aston-Martin-DB11", make = "Aston Martin") shouldBe
            SignalsetAvailability.DOWNLOADED
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

private class FakeSignalsetDownloader(
    private val result: DownloadResult,
    private val byRepo: Map<String, DownloadResult> = emptyMap(),
) : SignalsetDownloader {
    var calls: Int = 0
        private set
    var lastEtag: String? = null
        private set

    /** Every repo fetched, in call order — what proves the make repo was, or was not, consulted. */
    val repos = mutableListOf<String>()

    override suspend fun fetch(repo: String, variant: String, etag: String?): DownloadResult {
        calls++
        lastEtag = etag
        repos += repo
        return byRepo[repo] ?: result
    }
}
