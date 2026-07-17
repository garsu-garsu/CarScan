package com.bruni.carscan.obd

import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.DefaultAcquisitionBaseline
import com.bruni.carscan.core.data.STANDARD_CORE_SIGNALS
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * `AcquisitionController` is what keeps the poller running once the user leaves the dashboard —
 * see the class KDoc for the full "trip recording stops the moment you tab away" problem this
 * fixes. A data screen (dashboard/live/HUD) in the foreground always owns [VisibleSignals] through
 * its own `setVisible` calls; this controller only ever speaks up the rest of the time.
 */
class AcquisitionControllerTest {

    private val speedKey = MetricKey.Metric(SuggestedMetric.SPEED)

    @Test
    fun `a non-data foreground with the dashboard source polls the dashboard's own tiles`() = runTest {
        val settings = FakeAcquisitionSettings(AcquisitionSource.DASHBOARD)
        val baseline = DefaultAcquisitionBaseline().apply { setDashboardSignals(setOf(speedKey)) }
        val visibility = FakeVisibleSignals()
        val controller = AcquisitionController(settings, baseline, visibility)

        controller.setForeground(null)
        controller.start(backgroundScope)
        runCurrent()

        visibility.calls.last() shouldBe setOf(speedKey)
    }

    @Test
    fun `the dashboard source falls back to the standard core signals when the dashboard is empty`() =
        runTest {
            val settings = FakeAcquisitionSettings(AcquisitionSource.DASHBOARD)
            val baseline = DefaultAcquisitionBaseline()   // never told about any tiles
            val visibility = FakeVisibleSignals()
            val controller = AcquisitionController(settings, baseline, visibility)

            controller.setForeground(null)
            controller.start(backgroundScope)
            runCurrent()

            visibility.calls.last() shouldBe STANDARD_CORE_SIGNALS
        }

    @Test
    fun `the monitoring source polls the standard core signals`() = runTest {
        val settings = FakeAcquisitionSettings(AcquisitionSource.MONITORING)
        val baseline = DefaultAcquisitionBaseline().apply { setDashboardSignals(setOf(speedKey)) }
        val visibility = FakeVisibleSignals()
        val controller = AcquisitionController(settings, baseline, visibility)

        controller.setForeground(null)
        controller.start(backgroundScope)
        runCurrent()

        visibility.calls.last() shouldBe STANDARD_CORE_SIGNALS
    }

    /**
     * The test the brief asks to fail if the guard is removed: with a data screen in the
     * foreground, that screen owns `setVisible` through its own announcement, and the controller
     * must not fight it for the one thing half-duplex polling allows — one visible set.
     */
    @Test
    fun `a data screen in the foreground is never overridden by the controller`() = runTest {
        val settings = FakeAcquisitionSettings(AcquisitionSource.DASHBOARD)
        val baseline = DefaultAcquisitionBaseline().apply { setDashboardSignals(setOf(speedKey)) }
        val visibility = FakeVisibleSignals()
        val controller = AcquisitionController(settings, baseline, visibility)

        controller.setForeground(AcquisitionScreen.DASHBOARD)
        controller.start(backgroundScope)
        runCurrent()

        visibility.calls shouldBe emptyList()
    }

    @Test
    fun `changing the acquisition source re-drives setVisible`() = runTest {
        val settings = FakeAcquisitionSettings(AcquisitionSource.DASHBOARD)
        val baseline = DefaultAcquisitionBaseline().apply { setDashboardSignals(setOf(speedKey)) }
        val visibility = FakeVisibleSignals()
        val controller = AcquisitionController(settings, baseline, visibility)

        controller.setForeground(null)
        controller.start(backgroundScope)
        runCurrent()
        visibility.calls.last() shouldBe setOf(speedKey)

        settings.setSource(AcquisitionSource.MONITORING)
        runCurrent()

        visibility.calls.last() shouldBe STANDARD_CORE_SIGNALS
    }
}

private class FakeVisibleSignals : VisibleSignals {
    val calls: MutableList<Set<MetricKey>> = mutableListOf()
    override fun setVisible(keys: Set<MetricKey>) {
        calls += keys
    }
}

private class FakeAcquisitionSettings(source: AcquisitionSource) : SettingsRepository {
    private val state = MutableStateFlow(Settings(acquisitionSource = source))
    override val settings: Flow<Settings> = state

    fun setSource(source: AcquisitionSource) {
        state.value = state.value.copy(acquisitionSource = source)
    }

    override suspend fun setRecordTrips(enabled: Boolean) = Unit
    override suspend fun setUnit(quantity: Quantity, unit: UnitId) = Unit
    override suspend fun setUnits(units: UnitPreferences) = Unit

    @Deprecated("Use setUnit(Quantity.SPEED, …).", ReplaceWith("setUnit(Quantity.SPEED, unit)"))
    override suspend fun setSpeedUnit(unit: SpeedUnit) = Unit
    override suspend fun setKeepScreenOn(enabled: Boolean) = Unit
    override suspend fun setActiveVehicleId(id: String?) = Unit
    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override suspend fun setGaugeStyle(style: String) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
    override suspend fun setAcquisitionSource(source: AcquisitionSource) = setSource(source)
}
