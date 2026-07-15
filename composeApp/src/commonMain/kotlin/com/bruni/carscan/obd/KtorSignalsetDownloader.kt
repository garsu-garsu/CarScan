package com.bruni.carscan.obd

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Fetches an OBDb signalset over plain HTTPS from `raw.githubusercontent.com`.
 *
 * Deliberately thin: what an HTTP outcome *means* — revalidate, cache, give up — is
 * [DefaultSignalsetProvider]'s decision, tested there against a fake of this interface. This class
 * only maps a response to a [DownloadResult].
 */
class KtorSignalsetDownloader(private val client: HttpClient) : SignalsetDownloader {

    override suspend fun fetch(repo: String, variant: String, etag: String?): DownloadResult = try {
        val response = client.get(
            "https://raw.githubusercontent.com/OBDb/$repo/main/signalsets/v3/$variant.json",
        ) {
            if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
        }

        when (response.status) {
            HttpStatusCode.OK -> DownloadResult.Fetched(
                json = response.bodyAsText(),
                etag = response.headers[HttpHeaders.ETag],
            )
            HttpStatusCode.NotModified -> DownloadResult.NotModified
            HttpStatusCode.NotFound -> DownloadResult.NotFound
            else -> DownloadResult.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        DownloadResult.NoNetwork
    } catch (e: Exception) {
        DownloadResult.Failed
    }
}
