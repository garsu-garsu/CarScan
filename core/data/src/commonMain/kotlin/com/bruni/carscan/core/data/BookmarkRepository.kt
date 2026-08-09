package com.bruni.carscan.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The signals the user has starred for the monitoring screen — see `AcquisitionController`'s
 * MONITORING branch, which polls exactly this set (falling back to `STANDARD_CORE_SIGNALS` when
 * it's empty).
 */
interface BookmarkRepository {
    val bookmarks: Flow<Set<MetricKey>>
    suspend fun toggle(key: MetricKey)
    suspend fun isBookmarked(key: MetricKey): Boolean
}

private val BOOKMARKED_SIGNALS = stringSetPreferencesKey("bookmarked_signals")

class DefaultBookmarkRepository(
    private val store: DataStore<Preferences>,
) : BookmarkRepository {

    override val bookmarks: Flow<Set<MetricKey>> = store.data.map { prefs ->
        // An unparseable/unknown entry — a newer build's key, or corruption — is skipped rather
        // than thrown: the same defensive pattern as Settings' enum fallbacks.
        (prefs[BOOKMARKED_SIGNALS] ?: emptySet()).mapNotNull(::decodeMetricKey).toSet()
    }

    override suspend fun toggle(key: MetricKey) {
        // Read-modify-write inside the edit, so two toggles can't clobber each other — same
        // reasoning as DefaultSettingsRepository.setUnit.
        store.edit { prefs ->
            val current = (prefs[BOOKMARKED_SIGNALS] ?: emptySet())
                .mapNotNull(::decodeMetricKey).toMutableSet()
            if (!current.remove(key)) current.add(key)
            prefs[BOOKMARKED_SIGNALS] = current.map(::encodeMetricKey).toSet()
        }
    }

    override suspend fun isBookmarked(key: MetricKey): Boolean = key in bookmarks.first()
}

/**
 * The persisted token for a starred signal. `internal` rather than private because a backup
 * carries bookmarks, and the backup file must speak the same tokens DataStore holds — a second
 * spelling of the same format is a divergence waiting to happen. See `BackupService`.
 */
internal fun encodeMetricKey(key: MetricKey): String = when (key) {
    is MetricKey.Metric -> "M:${key.metric.name}"
    is MetricKey.Signal -> "S:${key.signalId}"
}

internal fun decodeMetricKey(token: String): MetricKey? = when {
    token.startsWith("M:") ->
        SuggestedMetric.entries.firstOrNull { it.name == token.removePrefix("M:") }?.let(MetricKey::Metric)
    token.startsWith("S:") -> MetricKey.Signal(token.removePrefix("S:"))
    else -> null
}
