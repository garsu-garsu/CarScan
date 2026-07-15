package com.bruni.carscan.feature.garage

import com.bruni.carscan.core.data.newUuid
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Spelled out rather than `viewModelOf(::GarageViewModel)` — the reflective form resolves every
 * constructor parameter from the graph and ignores Kotlin's defaults, so a constructor that grows
 * an optional parameter later would fail at runtime, where Koin failures live.
 *
 * `VehicleCatalog` is bound in :composeApp, the only place that knows the bundled assets — same
 * seam as `ObdConnector`/`ActiveVehicle`/`SignalsetProvider`.
 */
val garageModule: Module = module {
    viewModel {
        GarageViewModel(
            catalog = get(),
            vehicles = get(),
            settings = get(),
            signalsets = get(),
            now = { Clock.System.now().toEpochMilliseconds() },
            newId = { newUuid() },
        )
    }
}
