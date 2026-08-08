package com.bruni.carscan.feature.live

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * What :composeApp still has to bind for this screen to resolve:
 *
 *  * [SeriesCatalog] — over the active vehicle's `EffectiveSignalset` (label, range, native unit).
 *  * [VisibleSignals] — over `PidScheduler.setVisible`, which no feature can reach directly.
 *  * `Flow<UnitPreferences>` — the user's display units, straight off `Settings.units`.
 */
val liveModule: Module = module {
    // Spelled out rather than `viewModelOf(::LiveViewModel)`: that resolves *every* constructor
    // parameter from the graph and ignores Kotlin's defaults, so it would demand an `Int` binding
    // for the ring-buffer capacity and fail at runtime, where Koin failures live.
    viewModel { LiveViewModel(get(), get(), get(), get(), get()) }
}
