package com.bruni.carscan.feature.settings

import app.cash.turbine.test
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.UnitId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class SettingsViewModelTest {

    // viewModelScope is Dispatchers.Main.immediate, which does not exist in a JVM unit test.
    // Sharing one scheduler with runTest is what lets the ViewModel's own launches advance
    // under virtual time instead of hanging.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state mirrors what is already in the repository`() = runTest(dispatcher) {
        val repo = FakeSettingsRepository(Settings(keepScreenOn = false, recordTrips = true))
        val vm = SettingsViewModel(repo)
        runCurrent()

        vm.state.value.keepScreenOn shouldBe false
        vm.state.value.recordTrips shouldBe true
    }

    @Test
    fun `SetUnit reaches the repository, and the new unit comes back into state`() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)

        vm.onIntent(SettingsIntent.SetUnit(Quantity.SPEED, UnitId.MPH))
        runCurrent()

        vm.state.value.units[Quantity.SPEED] shouldBe UnitId.MPH
    }

    @Test
    fun `SetGaugeStyle reaches the repository`() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)

        vm.onIntent(SettingsIntent.SetGaugeStyle("CLASSIC_ANALOG"))
        runCurrent()

        vm.state.value.gaugeStyle shouldBe "CLASSIC_ANALOG"
    }

    @Test
    fun `SetThemeMode reaches the repository`() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)

        vm.onIntent(SettingsIntent.SetThemeMode(ThemeMode.DARK))
        runCurrent()

        vm.state.value.themeMode shouldBe ThemeMode.DARK
    }

    @Test
    fun `SetKeepScreenOn reaches the repository`() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)

        vm.onIntent(SettingsIntent.SetKeepScreenOn(false))
        runCurrent()

        vm.state.value.keepScreenOn shouldBe false
    }

    @Test
    fun `SetRecordTrips reaches the repository`() = runTest(dispatcher) {
        val repo = FakeSettingsRepository()
        val vm = SettingsViewModel(repo)

        vm.onIntent(SettingsIntent.SetRecordTrips(true))
        runCurrent()

        vm.state.value.recordTrips shouldBe true
    }

    /**
     * Features never call a `NavController` themselves — see `App.kt`'s KDoc. The About row is
     * no exception: it emits an effect and leaves the navigation to the composition root.
     */
    @Test
    fun `the About row emits OpenAbout rather than navigating itself`() = runTest(dispatcher) {
        val vm = SettingsViewModel(FakeSettingsRepository())

        vm.effect.test {
            vm.onIntent(SettingsIntent.OpenAbout)
            awaitItem() shouldBe SettingsEffect.OpenAbout
        }
    }

    @Test
    fun `the Vehicle row emits OpenGarage rather than navigating itself`() = runTest(dispatcher) {
        val vm = SettingsViewModel(FakeSettingsRepository())

        vm.effect.test {
            vm.onIntent(SettingsIntent.OpenVehicle)
            awaitItem() shouldBe SettingsEffect.OpenGarage
        }
    }
}
