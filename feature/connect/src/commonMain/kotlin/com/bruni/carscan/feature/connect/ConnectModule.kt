package com.bruni.carscan.feature.connect

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * What this screen needs from the graph: the `ObdConnector` port, the learned-quirks table,
 * and the session's health.
 *
 * The connector itself is bound in :composeApp, over :core:obd. Nothing here — and nothing
 * anywhere above :core:data — can construct an `ElmSession`, which is the point.
 */
val connectModule: Module = module {
    viewModel {
        ConnectViewModel(
            connector = get(),
            adapters = get(),
            session = get(),
            nowMs = { Clock.System.now().toEpochMilliseconds() },
        )
    }
}
