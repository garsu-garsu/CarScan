package com.bruni.carscan.core.data

/**
 * Where a vehicle's OBDb signalset JSON comes from — a bundled asset, the local cache, or a
 * download — without the callers having to know which. Only a curated few vehicles ship in the
 * APK; the rest are fetched from OBDb on demand and cached in the `signalset` table.
 *
 * The split between the two methods is deliberate and load-bearing: **a download must happen while
 * the phone still has internet, which is when the user picks the vehicle — not when they connect.**
 * A Wi-Fi ELM327 puts the phone on the adapter's own access point, with no route to the internet,
 * so the connect path can only ever read what is already on the device.
 */
interface SignalsetProvider {

    /**
     * The signalset JSON for [repo] from a bundled asset or the local cache only — **never the
     * network**. This is what the connect path calls, and it may run with no internet. Null when
     * the device has neither a bundled copy nor a cached one.
     */
    suspend fun cachedJson(repo: String): String?

    /**
     * Make [repo]'s signalset available on the device, downloading and caching it if necessary.
     * Call this while online — i.e. when the user picks the vehicle. A bundled or already-cached
     * repo returns [SignalsetAvailability.AVAILABLE] with no network call; an ETag revalidation of
     * a cached copy that is still current returns the same.
     */
    suspend fun ensureAvailable(repo: String): SignalsetAvailability
}

enum class SignalsetAvailability {
    /** Already on the device (bundled, or cached and still current) — nothing was downloaded. */
    AVAILABLE,

    /** Fetched from OBDb just now and cached. */
    DOWNLOADED,

    /** Not on the device and could not be fetched because there is no internet right now. */
    NO_NETWORK,

    /** OBDb has no such repository (a 404) — the catalog is out of step with upstream. */
    NOT_FOUND,

    /** On the network, but the fetch failed for some other reason. */
    FAILED,
}
