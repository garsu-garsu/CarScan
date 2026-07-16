package com.bruni.carscan.core.monetization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

private val CACHED_PURCHASES = stringPreferencesKey("cached_purchases")

/**
 * [EntitlementCache] over the same `DataStore<Preferences>` singleton `:core:data`'s
 * SettingsRepository reads — one preferences file, not a second store to keep in sync.
 *
 * Tolerant by construction, same reasoning as `UnitPreferences.decode`: a record with an
 * unrecognised [PurchaseKind] or [PurchaseState] — from a newer build, or corruption — is
 * dropped rather than crashing the read. Losing one cached purchase is recoverable the next
 * time [BillingPort] answers; throwing on it would brick the app on downgrade.
 */
class DataStoreEntitlementCache(private val store: DataStore<Preferences>) : EntitlementCache {

    override suspend fun load(): List<Purchase> = decode(store.data.first()[CACHED_PURCHASES])

    override suspend fun save(purchases: List<Purchase>) {
        store.edit { it[CACHED_PURCHASES] = encode(purchases) }
    }

    private fun encode(purchases: List<Purchase>): String =
        purchases.joinToString(";") { "${it.kind.name}:${it.state.name}:${it.expiryEpochMs ?: ""}" }

    private fun decode(raw: String?): List<Purchase> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 3) return@mapNotNull null
            val kind = PurchaseKind.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val state = PurchaseState.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            Purchase(kind, state, parts[2].toLongOrNull())
        }
    }
}
