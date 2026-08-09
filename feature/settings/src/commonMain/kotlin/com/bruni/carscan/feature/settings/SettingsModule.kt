package com.bruni.carscan.feature.settings

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Spelled out rather than `viewModelOf(::SettingsViewModel)` — the reflective form resolves
 * every constructor parameter from the graph and ignores Kotlin's defaults, so a constructor
 * that grows an optional parameter later would fail at runtime, where Koin failures live.
 */
val settingsModule: Module = module {
    viewModel { SettingsViewModel(get(), get()) }
    viewModel { PaywallViewModel(get(), get()) }
}
