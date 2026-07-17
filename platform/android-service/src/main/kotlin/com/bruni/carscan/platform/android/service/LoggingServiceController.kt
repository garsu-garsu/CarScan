package com.bruni.carscan.platform.android.service

/** Starts/stops the foreground service that keeps trip logging alive in the background. */
interface LoggingServiceController {
    fun start()
    fun stop()
}
