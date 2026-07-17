package com.bruni.carscan.feature.trip

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val tripModule: Module = module {
    viewModel { TripListViewModel(get(), get()) }
}
