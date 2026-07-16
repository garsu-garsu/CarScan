package com.bruni.carscan.feature.garage

import app.cash.turbine.test
import com.bruni.carscan.core.data.CatalogEntry
import com.bruni.carscan.core.data.SignalsetAvailability
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
        signalsets: FakeSignalsetProvider = FakeSignalsetProvider(),
        now: () -> Long = { 1_000L },
        newId: () -> String = { "fixed-id" },
    ) = GarageViewModel(
        catalog = catalog,
        vehicles = vehicles,
        settings = settings,
        signalsets = signalsets,
        now = now,
        newId = newId,
    )

    @Test
    fun `state exposes the catalog entries on init`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        vm.state.value.entries shouldContainExactly listOf(entry)
    }

    @Test
    fun `state starts loading and clears once the catalog resolves`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.state.value.loading shouldBe true
        vm.state.value.entries shouldContainExactly emptyList()

        runCurrent()

        vm.state.value.loading shouldBe false
        vm.state.value.entries shouldContainExactly listOf(entry)
    }

    @Test
    fun `Search narrows byMake to entries whose display name matches, case-insensitively`() = runTest(dispatcher) {
        val niro = CatalogEntry(make = "Kia", model = "Niro", obdbRepo = "Kia-Niro", minYear = 2022, maxYear = 2024)
        val vm = viewModel(catalog = FakeVehicleCatalog(listOf(entry, niro)))
        runCurrent()

        vm.onIntent(GarageIntent.Search("ev6"))

        vm.state.value.byMake shouldBe mapOf("Kia" to listOf(entry))
    }

    @Test
    fun `an empty query after a Search restores every entry`() = runTest(dispatcher) {
        val niro = CatalogEntry(make = "Kia", model = "Niro", obdbRepo = "Kia-Niro", minYear = 2022, maxYear = 2024)
        val vm = viewModel(catalog = FakeVehicleCatalog(listOf(entry, niro)))
        runCurrent()

        vm.onIntent(GarageIntent.Search("ev6"))
        vm.onIntent(GarageIntent.Search(""))

        vm.state.value.byMake shouldBe mapOf("Kia" to listOf(entry, niro))
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

    @Test
    fun `Select downloads the signalset while online and emits the effect on DOWNLOADED`() = runTest(dispatcher) {
        val vehicles = FakeVehicleRepository()
        val settings = FakeSettingsRepository()
        val signalsets = FakeSignalsetProvider(result = SignalsetAvailability.DOWNLOADED)
        val vm = viewModel(vehicles = vehicles, settings = settings, signalsets = signalsets, newId = { "vehicle-1" })

        vm.effect.test {
            vm.onIntent(GarageIntent.Select(entry))
            runCurrent()

            signalsets.ensureAvailableCalls shouldContainExactly listOf("Kia-EV6")
            vehicles.remembered.single().id shouldBe "vehicle-1"
            settings.activeVehicleIds shouldContainExactly listOf("vehicle-1")
            vm.state.value.downloading shouldBe null
            vm.state.value.message shouldBe null

            awaitItem() shouldBe GarageEffect.Selected
        }
    }

    @Test
    fun `Select still records and activates the vehicle when there is no network, and surfaces the offline message`() =
        runTest(dispatcher) {
            val vehicles = FakeVehicleRepository()
            val settings = FakeSettingsRepository()
            val signalsets = FakeSignalsetProvider(result = SignalsetAvailability.NO_NETWORK)
            val vm = viewModel(
                vehicles = vehicles,
                settings = settings,
                signalsets = signalsets,
                newId = { "vehicle-1" },
            )

            vm.onIntent(GarageIntent.Select(entry))
            runCurrent()

            // The choice sticks even though the download failed — the connect path falls back
            // to standard PIDs, and the download can be retried.
            vehicles.remembered.single().id shouldBe "vehicle-1"
            settings.activeVehicleIds shouldContainExactly listOf("vehicle-1")
            vm.state.value.downloading shouldBe null
            vm.state.value.message shouldBe DownloadMessage.Offline
        }

    @Test
    fun `Select succeeds without a lingering downloading flag when the signalset is already bundled`() =
        runTest(dispatcher) {
            val signalsets = FakeSignalsetProvider(result = SignalsetAvailability.AVAILABLE)
            val vm = viewModel(signalsets = signalsets)

            vm.effect.test {
                vm.onIntent(GarageIntent.Select(entry))
                runCurrent()

                vm.state.value.downloading shouldBe null
                vm.state.value.message shouldBe null

                awaitItem() shouldBe GarageEffect.Selected
            }
        }
}
