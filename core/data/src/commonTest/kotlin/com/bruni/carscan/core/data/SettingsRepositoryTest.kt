package com.bruni.carscan.core.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bruni.carscan.core.units.SpeedUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepositoryTest {

    private val store = InMemoryPreferencesDataStore()
    private val repo = DefaultSettingsRepository(store)

    /**
     * Recording off is the default, and that is a storage decision rather than a UI
     * one: with it on, every minute the app spends open in someone's car writes to
     * disk. The gauges read the live sample stream and need none of it.
     */
    @Test
    fun `trip recording is off by default`() = runTest {
        assertFalse(repo.settings.first().recordTrips)
    }

    @Test
    fun `the defaults are what a first-run user gets`() = runTest {
        val settings = repo.settings.first()
        assertEquals(SpeedUnit.KM_PER_HOUR, settings.speedUnit)
        assertTrue(settings.keepScreenOn)
        assertNull(settings.activeVehicleId)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals("MODERN_ARC", settings.gaugeStyle)
    }

    @Test
    fun `a changed setting is visible to the next read`() = runTest {
        repo.setRecordTrips(true)
        repo.setSpeedUnit(SpeedUnit.MILES_PER_HOUR)
        repo.setActiveVehicleId("veh-1")
        repo.setKeepScreenOn(false)
        repo.setThemeMode(ThemeMode.DARK)
        repo.setGaugeStyle("CLASSIC_ANALOG")

        val settings = repo.settings.first()
        assertTrue(settings.recordTrips)
        assertEquals(SpeedUnit.MILES_PER_HOUR, settings.speedUnit)
        assertEquals("veh-1", settings.activeVehicleId)
        assertFalse(settings.keepScreenOn)
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals("CLASSIC_ANALOG", settings.gaugeStyle)
    }

    /** A preferences file from a newer build must not brick the app — see the unit test below. */
    @Test
    fun `an unrecognised stored theme mode falls back to the default instead of throwing`() = runTest {
        store.edit { it[stringPreferencesKey("theme_mode")] = "SUPER_DARK" }
        assertEquals(ThemeMode.SYSTEM, repo.settings.first().themeMode)
    }

    /** The repository holds no state of its own; the store is the only truth. */
    @Test
    fun `a new repository over the same store reads what the old one wrote`() = runTest {
        repo.setSpeedUnit(SpeedUnit.MILES_PER_HOUR)
        assertEquals(
            SpeedUnit.MILES_PER_HOUR,
            DefaultSettingsRepository(store).settings.first().speedUnit,
        )
    }

    @Test
    fun `the active vehicle can be cleared`() = runTest {
        repo.setActiveVehicleId("veh-1")
        repo.setActiveVehicleId(null)
        assertNull(repo.settings.first().activeVehicleId)
    }

    /**
     * A preferences file written by a newer build — or a renamed enum — must not brick
     * the app on downgrade. An unreadable value falls back to the default.
     */
    @Test
    fun `an unrecognised stored unit falls back to the default instead of throwing`() = runTest {
        store.edit { it[stringPreferencesKey("speed_unit")] = "FURLONGS_PER_FORTNIGHT" }
        assertEquals(SpeedUnit.KM_PER_HOUR, repo.settings.first().speedUnit)
    }
}
