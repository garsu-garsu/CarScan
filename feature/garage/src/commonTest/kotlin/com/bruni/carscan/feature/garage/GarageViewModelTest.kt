package com.bruni.carscan.feature.garage

import app.cash.turbine.test
import com.bruni.carscan.core.data.CatalogEntry
import io.kotest.matchers.collections.shouldContainExactly
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

class GarageViewModelTest {

    // viewModelScope is Dispatchers.Main.immediate, which does not exist in a JVM unit test.
    // Sharing one scheduler with runTest is what lets the ViewModel's own launches advance
    // under virtual time instead of hanging.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val entry = CatalogEntry(
        make = "Kia",
        model = "EV6",
        obdbRepo = "Kia-EV6",
        minYear = 2022,
        maxYear = 2024,
    )

    private fun viewModel(
        catalog: FakeVehicleCatalog = FakeVehicleCatalog(listOf(entry)),
        vehicles: FakeVehicleRepository = FakeVehicleRepository(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        now: () -> Long = { 1_000L },
        newId: () -> String = { "fixed-id" },
    ) = GarageViewModel(catalog = catalog, vehicles = vehicles, settings = settings, now = now, newId = newId)

    @Test
    fun `state exposes the catalog entries on init`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        vm.state.value.entries shouldContainExactly listOf(entry)
    }

    @Test
    fun `Select remembers the vehicle with the catalog's fields, marks it active, and emits the effect`() =
        runTest(dispatcher) {
            val vehicles = FakeVehicleRepository()
            val settings = FakeSettingsRepository()
            val vm = viewModel(vehicles = vehicles, settings = settings, now = { 5_000L }, newId = { "vehicle-1" })

            vm.effect.test {
                vm.onIntent(GarageIntent.Select(entry))
                runCurrent()

                vehicles.remembered.single().let { vehicle ->
                    vehicle.id shouldBe "vehicle-1"
                    vehicle.make shouldBe "Kia"
                    vehicle.model shouldBe "EV6"
                    vehicle.obdbRepo shouldBe "Kia-EV6"
                    // A representative year for the skeleton — the newest one this entry covers.
                    vehicle.modelYear shouldBe 2024L
                    vehicle.displayName shouldBe "Kia EV6"
                    vehicle.createdMs shouldBe 5_000L
                }
                settings.activeVehicleIds shouldContainExactly listOf("vehicle-1")

                awaitItem() shouldBe GarageEffect.Selected
            }
        }
}
