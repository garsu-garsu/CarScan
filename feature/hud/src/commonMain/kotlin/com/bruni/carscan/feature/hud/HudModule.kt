package com.bruni.carscan.feature.hud

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Spelled out rather than `viewModelOf(::HudViewModel)` — see `:feature:settings`'s module for
 * why: the reflective form resolves *every* constructor parameter from the graph and ignores
 * Kotlin's defaults, so it would demand bindings for `now` and `ticks` too and fail at runtime.
 */
val hudModule: Module = module {
    viewModel { HudViewModel(get(), get(), get(), get()) }
}
