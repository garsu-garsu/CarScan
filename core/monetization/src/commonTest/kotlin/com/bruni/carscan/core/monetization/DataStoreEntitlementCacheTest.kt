package com.bruni.carscan.core.monetization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataStoreEntitlementCacheTest {

    @Test
    fun `load returns an empty list when nothing has been saved yet`() = runTest {
        val cache = DataStoreEntitlementCache(InMemoryPreferencesDataStore())
        assertTrue(cache.load().isEmpty())
    }

    @Test
    fun `a saved purchase list round-trips through load unchanged`() = runTest {
        val cache = DataStoreEntitlementCache(InMemoryPreferencesDataStore())
        val purchases = listOf(
            Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE),
            Purchase(PurchaseKind.SUB_YEARLY, PurchaseState.IN_GRACE_PERIOD, expiryEpochMs = 1_700_000_000_000),
        )

        cache.save(purchases)

        assertEquals(purchases, cache.load())
    }

    @Test
    fun `saving replaces whatever was cached before`() = runTest {
        val cache = DataStoreEntitlementCache(InMemoryPreferencesDataStore())
        cache.save(listOf(Purchase(PurchaseKind.SUB_MONTHLY, PurchaseState.ACTIVE)))

        cache.save(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)))

        assertEquals(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)), cache.load())
    }
}

/**
 * DataStore, in memory. Mirrors `:core:data`'s `InMemoryPreferencesDataStore` test double: the
 * code under test is the [Purchase]-list-to-string encoding, not androidx's file engine.
 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = transform(state.value).also { state.value = it }
}
