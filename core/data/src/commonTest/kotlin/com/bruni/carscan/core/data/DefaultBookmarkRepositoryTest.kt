package com.bruni.carscan.core.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The signals the user has starred for the monitoring screen — see
 * `AcquisitionController`'s MONITORING branch, which polls exactly this set (falling back to
 * `STANDARD_CORE_SIGNALS` when it's empty).
 */
class DefaultBookmarkRepositoryTest {

    private val store = InMemoryPreferencesDataStore()
    private val repo = DefaultBookmarkRepository(store)

    private val speedKey = MetricKey.Metric(SuggestedMetric.SPEED)
    private val rpmKey = MetricKey.Signal("RPM")

    @Test
    fun `no bookmarks by default`() = runTest {
        assertEquals(emptySet(), repo.bookmarks.first())
    }

    @Test
    fun `toggling a key adds then removes it`() = runTest {
        repo.toggle(speedKey)
        assertEquals(setOf(speedKey), repo.bookmarks.first())
        assertTrue(repo.isBookmarked(speedKey))

        repo.toggle(speedKey)
        assertEquals(emptySet(), repo.bookmarks.first())
        assertFalse(repo.isBookmarked(speedKey))
    }

    /** The repository holds no state of its own; the store is the only truth. */
    @Test
    fun `both MetricKey variants round-trip through a new repository over the same store`() = runTest {
        repo.toggle(speedKey)
        repo.toggle(rpmKey)

        val reopened = DefaultBookmarkRepository(store).bookmarks.first()
        assertEquals(setOf(speedKey, rpmKey), reopened)
    }

    /** A newer build's key must not crash an older one — same defensive pattern as Settings. */
    @Test
    fun `an unrecognised stored token is ignored instead of throwing`() = runTest {
        store.edit { it[stringSetPreferencesKey("bookmarked_signals")] = setOf("X:garbage", "M:NOT_A_REAL_METRIC") }
        assertEquals(emptySet(), repo.bookmarks.first())
    }
}
