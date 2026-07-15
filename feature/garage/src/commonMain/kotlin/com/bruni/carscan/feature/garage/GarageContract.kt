package com.bruni.carscan.feature.garage

import com.bruni.carscan.core.data.CatalogEntry

data class GarageState(
    val entries: List<CatalogEntry> = emptyList(),
    /** The obdbRepo of the vehicle currently being downloaded, or null when none is in flight. */
    val downloading: String? = null,
    /** The outcome of the last download attempt the user still needs to see, if any. */
    val message: DownloadMessage? = null,
) {
    /** The picker reads as a list of makes, each with the models it covers. */
    val byMake: Map<String, List<CatalogEntry>> get() = entries.groupBy { it.make }
}

/** A signalset download outcome the user needs to learn about, because it did not just succeed. */
sealed interface DownloadMessage {
    /** No internet right now — standard PIDs will work offline; the download can be retried later. */
    data object Offline : DownloadMessage

    /** OBDb has no such repo, or the fetch failed for some other reason. */
    data object Failed : DownloadMessage
}

sealed interface GarageIntent {
    /** The user tapped a vehicle. Records it, makes it active, and downloads its signalset. */
    data class Select(val entry: CatalogEntry) : GarageIntent
}

sealed interface GarageEffect {
    /** A vehicle was picked and is now active. Features never navigate themselves — see App.kt. */
    data object Selected : GarageEffect
}
