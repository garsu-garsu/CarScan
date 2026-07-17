package com.bruni.carscan.obd

import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.platform.android.service.LoggingServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Starts/stops [LoggingServiceController] to match the user's `backgroundTracking` opt-in — see
 * [com.bruni.carscan.core.data.Settings.backgroundTracking].
 */
class BackgroundTrackingManager(
    private val settings: SettingsRepository,
    private val service: LoggingServiceController,
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.settings.map { it.backgroundTracking }.distinctUntilChanged()
                .collect { enabled -> if (enabled) service.start() else service.stop() }
        }
    }
}
