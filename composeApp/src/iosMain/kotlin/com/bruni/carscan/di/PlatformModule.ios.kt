package com.bruni.carscan.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.sqldelight.db.SqlDriver
import com.bruni.carscan.core.database.DriverFactory
import com.bruni.carscan.core.transport.ble.BleTransportFactory
import com.bruni.carscan.nav.AppSettingsOpener
import com.bruni.carscan.obd.DefaultTransports
import com.bruni.carscan.obd.Transports
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * **Never compiled.** Kotlin/Native's Apple targets need Xcode, so this file has never been past a
 * type-checker; it is written to be structurally right and should be expected to need fixing on
 * first contact with a Mac.
 */
actual fun platformModule(): Module = module {

    single<SqlDriver> { DriverFactory().createDriver() }

    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.createWithPath {
            "${documentsDirectory()}/$PREFERENCES_FILE".toPath()
        }
    }

    // No SPP, and not because it is unimplemented: Apple's ExternalAccessory framework reaches
    // only MFi-certified hardware and no ELM327 clone is MFi. Bluetooth Classic is not a gap to be
    // closed later — it does not exist here. The connect screen is built from
    // `ObdConnector.supported`, so it simply has no Bluetooth Classic section.
    single<Transports> { DefaultTransports(ble = BleTransportFactory(), spp = null) }

    single<AppSettingsOpener> {
        AppSettingsOpener {
            val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
            if (url != null) UIApplication.sharedApplication.openURL(url)
        }
    }
}

private fun documentsDirectory(): String {
    val url: NSURL = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    ) ?: error("No documents directory")
    return requireNotNull(url.path) { "Documents directory has no path" }
}

private const val PREFERENCES_FILE = "carscan.preferences_pb"
