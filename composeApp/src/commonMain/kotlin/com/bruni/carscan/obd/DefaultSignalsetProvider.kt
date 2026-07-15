package com.bruni.carscan.obd

import carscan.composeapp.generated.resources.Res
import com.bruni.carscan.core.data.SignalsetAvailability
import com.bruni.carscan.core.data.SignalsetCache
import com.bruni.carscan.core.data.SignalsetProvider
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.time.Clock

/**
 * The frozen [SignalsetProvider] port, satisfied: a bundled asset if there is one, else the local
 * cache, else — at [ensureAvailable] pick time only — a download from OBDb.
 */
class DefaultSignalsetProvider(
    private val cache: SignalsetCache,
    private val downloader: SignalsetDownloader,
    /** Injected so a test can control what a fresh download is timestamped with. */
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Reads a bundled asset by its `composeResources`-relative path. Injected for the same reason
     * [BundledSignalsetSource] injects one: a test can supply fake JSON with no compose resources
     * on the test classpath. */
    private val readAsset: suspend (path: String) -> String = ::readComposeAsset,
) : SignalsetProvider {

    override suspend fun cachedJson(repo: String): String? =
        bundledJson(repo) ?: cache.get(repo)?.json

    override suspend fun ensureAvailable(repo: String): SignalsetAvailability {
        if (bundledJson(repo) != null) return SignalsetAvailability.AVAILABLE

        val cached = cache.get(repo)
        return when (val result = downloader.fetch(repo, VARIANT, cached?.etag)) {
            is DownloadResult.NotModified -> SignalsetAvailability.AVAILABLE
            is DownloadResult.Fetched -> {
                cache.put(repo, VARIANT, result.etag, result.json, nowMs())
                SignalsetAvailability.DOWNLOADED
            }
            is DownloadResult.NotFound -> SignalsetAvailability.NOT_FOUND
            is DownloadResult.NoNetwork ->
                if (cached != null) SignalsetAvailability.AVAILABLE else SignalsetAvailability.NO_NETWORK
            is DownloadResult.Failed -> SignalsetAvailability.FAILED
        }
    }

    private suspend fun bundledJson(repo: String): String? =
        runCatching { readAsset("files/obdb/$repo.json") }.getOrNull()

    private companion object {
        const val VARIANT = "default"
    }
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun readComposeAsset(path: String): String = Res.readBytes(path).decodeToString()
