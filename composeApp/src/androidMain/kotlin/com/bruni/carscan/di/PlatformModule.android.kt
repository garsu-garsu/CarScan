package com.bruni.carscan.di

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.sqldelight.db.SqlDriver
import com.bruni.carscan.core.database.DriverFactory
import com.bruni.carscan.core.transport.ble.BleTransportFactory
import com.bruni.carscan.core.transport.spp.SppTransportFactory
import com.bruni.carscan.nav.AppSettingsOpener
import com.bruni.carscan.obd.DefaultTransports
import com.bruni.carscan.obd.Transports
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {

    single<SqlDriver> { DriverFactory(androidContext()).createDriver() }

    single<DataStore<Preferences>> {
        val context = androidContext()
        PreferenceDataStoreFactory.createWithPath {
            context.filesDir.resolve(PREFERENCES_FILE).absolutePath.toPath()
        }
    }

    // All three transports. Android is the only platform where that sentence is true.
    single<Transports> {
        DefaultTransports(
            ble = BleTransportFactory(),
            spp = SppTransportFactory(androidContext()),
        )
    }

    single<AppSettingsOpener> {
        val context = androidContext()
        AppSettingsOpener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
            // Started from application context, which has no task of its own to go into.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

/** DataStore requires the `.preferences_pb` suffix; it does not append it. */
private const val PREFERENCES_FILE = "carscan.preferences_pb"
