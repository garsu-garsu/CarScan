package com.bruni.carscan.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.bruni.carscan.db.CarScanDb

actual class DriverFactory {
    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(CarScanDb.Schema, "carscan.db").also(::applyCarScanPragmas)
}
