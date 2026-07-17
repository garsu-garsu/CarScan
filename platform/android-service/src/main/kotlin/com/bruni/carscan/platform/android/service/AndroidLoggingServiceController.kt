package com.bruni.carscan.platform.android.service

import android.content.Context

/** Starts/stops [TripTrackingService]. */
class AndroidLoggingServiceController(context: Context) : LoggingServiceController {

    private val appContext = context.applicationContext

    override fun start() = TripTrackingService.start(appContext)

    override fun stop() = TripTrackingService.stop(appContext)
}
