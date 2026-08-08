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
 *
 * ## The make fallback
 *
 * Most OBDb vehicle repos publish `signalsets/v3/default.json` containing literally
 * `{"commands": []}` and keep their real definitions one level up, in the MAKE repo — 504 of this
 * catalog's 654 vehicles, 440 of which the make repo does define. OBDb's own harness resolves this
 * in `get_model_year_command_registry`: `if not signalset.commands:` re-fetch
 * `OBDb/{make}/signalsets/v3/default.json`. This port does the same, which is why both methods
 * take the vehicle's [make] alongside its repo.
 *
 * **It is a fallback, never a merge.** The make repo is a superset — every one of Ford-F-150's 116
 * commands also appears in `OBDb/Ford` — so unioning the two would make command resolution
 * ambiguous for every vehicle that does define its own.
 */
interface SignalsetProvider {

    /**
     * The signalset JSON for [repo] from a bundled asset or the local cache only — **never the
     * network**. This is what the connect path calls, and it may run with no internet. Null when
     * the device has neither a bundled copy nor a cached one.
     *
     * @param make the vehicle's make, e.g. `"Kia"` — used only when [repo]'s own signalset defines
     *   no commands, per the make fallback in the class KDoc. Null skips the fallback.
     */
    suspend fun cachedJson(repo: String, make: String?): String?

    /**
     * Make [repo]'s signalset available on the device, downloading and caching it if necessary.
     * Call this while online — i.e. when the user picks the vehicle. A bundled or already-cached
     * repo returns [SignalsetAvailability.AVAILABLE] with no network call; an ETag revalidation of
     * a cached copy that is still current returns the same.
     *
     * @param make the vehicle's make. When [repo]'s signalset turns out to define no commands, the
     *   make's copy is fetched and cached here **too** — the fallback that reads it runs on the
     *   connect path, which may be offline, so this is the only moment it can be obtained. The
     *   returned availability is still [repo]'s own; the make fetch is best-effort.
     */
    suspend fun ensureAvailable(repo: String, make: String?): SignalsetAvailability
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
