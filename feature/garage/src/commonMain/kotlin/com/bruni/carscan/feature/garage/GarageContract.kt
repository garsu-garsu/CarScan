package com.bruni.carscan.feature.garage

import com.bruni.carscan.core.data.CatalogEntry

data class GarageState(
    val entries: List<CatalogEntry> = emptyList(),
    /** True while [VehicleCatalog.all] is being read — the catalog is a 654-entry asset read. */
    val loading: Boolean = false,
    /** What the user has typed into the search field. Blank means "no filter". */
    val query: String = "",
    /** The obdbRepo of the vehicle currently being downloaded, or null when none is in flight. */
    val downloading: String? = null,
    /** The outcome of the last download attempt the user still needs to see, if any. */
    val message: DownloadMessage? = null,
) {
    /**
     * The picker reads as a list of makes, each with the models it covers — filtered to [query],
     * matched against [CatalogEntry.displayName] case-insensitively so either make or model
     * narrows the list.
     */
    val byMake: Map<String, List<CatalogEntry>>
        get() = matchingEntries.groupBy { it.make }

    private val matchingEntries: List<CatalogEntry>
        get() = if (query.isBlank()) entries else entries.filter { it.displayName.contains(query, ignoreCase = true) }
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

    /** The user typed into the search field. Narrows [GarageState.byMake] to matching vehicles. */
    data class Search(val query: String) : GarageIntent
}

sealed interface GarageEffect {
    /** A vehicle was picked and is now active. Features never navigate themselves — see App.kt. */
    data object Selected : GarageEffect
}
