package com.bruni.carscan.obd

/** The outcome of asking OBDb for a signalset. */
sealed interface DownloadResult {
    /** HTTP 200. [etag] is the response's ETag header, if any, to revalidate against next time. */
    data class Fetched(val json: String, val etag: String?) : DownloadResult

    /** HTTP 304 — the cached copy, revalidated against its ETag, is still current. */
    data object NotModified : DownloadResult

    /** HTTP 404 — the catalog names a repository OBDb does not have. */
    data object NotFound : DownloadResult

    /** A connect/IO failure — the phone is offline right now. */
    data object NoNetwork : DownloadResult

    /** Anything else: an unexpected status, a malformed response. */
    data object Failed : DownloadResult
}

/**
 * Fetches one OBDb vehicle signalset. Called only at pick time, while the phone is expected to
 * have internet — never from the connect path, which may be offline on a Wi-Fi ELM327's own
 * access point.
 */
interface SignalsetDownloader {
    suspend fun fetch(repo: String, variant: String, etag: String?): DownloadResult
}
