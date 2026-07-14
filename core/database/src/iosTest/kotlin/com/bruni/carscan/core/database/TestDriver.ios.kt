package com.bruni.carscan.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import com.bruni.carscan.db.CarScanDb

// Unverified: no Apple target is registered on a non-macOS host, so this file has
// never been compiled. See README — the iOS source sets are expected to need
// fixing on first contact with a Mac.
actual fun createTestDriver(path: String?): SqlDriver {
    val driver =
        if (path == null) inMemoryDriver(CarScanDb.Schema)
        else NativeSqliteDriver(CarScanDb.Schema, path)
    applyCarScanPragmas(driver)
    return driver
}

actual fun tempDbPath(name: String): String = name
