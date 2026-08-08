package com.bruni.carscan.obd

import carscan.composeapp.generated.resources.Res
import com.bruni.carscan.core.data.SignalsetAvailability
import com.bruni.carscan.core.data.SignalsetCache
import com.bruni.carscan.core.data.SignalsetProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.time.Clock

/**
 * The frozen [SignalsetProvider] port, satisfied: a bundled asset if there is one, else the local
 * cache, else — at [ensureAvailable] pick time only — a download from OBDb; and over all three,
 * the make fallback the port documents.
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

    override suspend fun cachedJson(repo: String, make: String?): String? {
        val own = onDeviceJson(repo)
        if (!own.definesNoCommands()) return own
        // Fallback, never a merge: the make's copy replaces an empty one outright.
        return makeRepoOf(make, repo)?.let { onDeviceJson(it) } ?: own
    }

    override suspend fun ensureAvailable(repo: String, make: String?): SignalsetAvailability {
        val availability = ensureCached(repo)

        // The vehicle repo is on the device now but defines nothing, so the connect path will read
        // the make's copy instead — and the connect path may be on a Wi-Fi ELM327's access point
        // with no internet. This is the only moment that copy can be obtained. Best-effort: the
        // vehicle's own availability is what the caller asked about and what it gets back.
        val makeRepo = makeRepoOf(make, repo)
        if (makeRepo != null && onDeviceJson(repo).definesNoCommands()) ensureCached(makeRepo)

        return availability
    }

    private suspend fun ensureCached(repo: String): SignalsetAvailability {
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

    /** Bundled-or-cached, no network — the half of [cachedJson] that is per-repo. */
    private suspend fun onDeviceJson(repo: String): String? = bundledJson(repo) ?: cache.get(repo)?.json

    private suspend fun bundledJson(repo: String): String? =
        runCatching { readAsset("files/obdb/$repo.json") }.getOrNull()

    private companion object {
        const val VARIANT = "default"

        /**
         * The OBDb repository holding [make]'s own signalset, e.g. `"Kia"` → `OBDb/Kia`. Makes
         * whose name is two words are hyphenated upstream: `"Land Rover"` → `OBDb/Land-Rover`,
         * `"Alfa Romeo"` → `OBDb/Alfa-Romeo`.
         *
         * Null when there is no make to fall back to, or when it *is* the vehicle repo — deriving
         * the make from [vehicleRepo] instead is what this avoids: `"Kia-EV3"` splits to `"Kia"`
         * but `"Land-Rover-Defender"` splits to `"Land"`, and the catalog carries the real name.
         */
        fun makeRepoOf(make: String?, vehicleRepo: String): String? =
            make?.trim()?.takeIf { it.isNotEmpty() }?.replace(' ', '-')?.takeIf { it != vehicleRepo }

        /**
         * The trigger for the make fallback: nothing on the device, or a `commands` array that is
         * present and empty — OBDb's `if not signalset.commands:`, and nothing more.
         *
         * It reads the array directly instead of going through
         * [com.bruni.carscan.core.vehicle.SignalsetParser] on purpose. That
         * parser is deliberately strict about enums, so one `unit` OBDb has added since would make
         * a vehicle with hundreds of commands look empty and get silently replaced by its make's.
         * A document we cannot read is not a document with no commands, and is left alone.
         */
        fun String?.definesNoCommands(): Boolean = when (this) {
            null -> true
            else -> runCatching {
                Json.parseToJsonElement(this).jsonObject["commands"]?.jsonArray?.isEmpty()
            }.getOrNull() == true
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun readComposeAsset(path: String): String = Res.readBytes(path).decodeToString()
