package com.bruni.carscan.core.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.UnitId
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
     * Recording on is the default: `TripRecorder` only ever writes while CONNECTED, so a
     * first-run user gets their drives recorded the moment a scanner is plugged in, without
     * ever having to find the settings screen.
     */
    @Test
    fun `trip recording is on by default`() = runTest {
        assertTrue(repo.settings.first().recordTrips)
    }

    @Test
    fun `the defaults are what a first-run user gets`() = runTest {
        val settings = repo.settings.first()
        assertEquals(UnitId.KMH, settings.units[Quantity.SPEED])
        assertTrue(settings.keepScreenOn)
        assertNull(settings.activeVehicleId)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals("MODERN_ARC", settings.gaugeStyle)
        assertTrue(settings.autoReconnect)
        assertEquals(AcquisitionSource.DASHBOARD, settings.acquisitionSource)
        assertEquals(20, settings.autoDriveDetectSpeedKmh)
        assertFalse(settings.backgroundTracking)
    }

    @Test
    fun `a changed setting is visible to the next read`() = runTest {
        repo.setRecordTrips(true)
        repo.setUnit(Quantity.SPEED, UnitId.MPH)
        repo.setActiveVehicleId("veh-1")
        repo.setKeepScreenOn(false)
        repo.setThemeMode(ThemeMode.DARK)
        repo.setGaugeStyle("CLASSIC_ANALOG")
        repo.setAutoReconnect(false)
        repo.setAcquisitionSource(AcquisitionSource.MONITORING)
        repo.setAutoDriveDetectSpeedKmh(30)
        repo.setBackgroundTracking(true)

        val settings = repo.settings.first()
        assertTrue(settings.recordTrips)
        assertEquals(UnitId.MPH, settings.units[Quantity.SPEED])
        assertEquals("veh-1", settings.activeVehicleId)
        assertFalse(settings.keepScreenOn)
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals("CLASSIC_ANALOG", settings.gaugeStyle)
        assertFalse(settings.autoReconnect)
        assertEquals(AcquisitionSource.MONITORING, settings.acquisitionSource)
        assertEquals(30, settings.autoDriveDetectSpeedKmh)
        assertTrue(settings.backgroundTracking)
    }

    /** A preferences file from a newer build must not brick the app — see the theme mode test. */
    @Test
    fun `an unrecognised stored acquisition source falls back to the default instead of throwing`() =
        runTest {
            store.edit { it[stringPreferencesKey("acquisition_source")] = "COCKPIT" }
            assertEquals(AcquisitionSource.DASHBOARD, repo.settings.first().acquisitionSource)
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
        repo.setUnit(Quantity.SPEED, UnitId.MPH)
        assertEquals(
            UnitId.MPH,
            DefaultSettingsRepository(store).settings.first().units[Quantity.SPEED],
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
        store.edit { it[stringPreferencesKey("unit_prefs")] = "SPEED=FURLONGS_PER_FORTNIGHT" }
        assertEquals(UnitId.KMH, repo.settings.first().units[Quantity.SPEED])
    }
}
