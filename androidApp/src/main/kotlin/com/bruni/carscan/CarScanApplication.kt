package com.bruni.carscan

import android.app.Application
import com.bruni.carscan.di.carScanModules
import com.bruni.carscan.obd.TripRecorder
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class CarScanApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@CarScanApplication)
            modules(carScanModules())
        }

        // The recorder must exist before the first sample does. It is the only subscriber that
        // puts anything on disk, and a sample that arrives before it is listening is a second of a
        // drive that was never recorded. It costs nothing while recording is off — which is the
        // default — because all it does then is watch a flow.
        get<TripRecorder>().start(get<CoroutineScope>())
    }
}
