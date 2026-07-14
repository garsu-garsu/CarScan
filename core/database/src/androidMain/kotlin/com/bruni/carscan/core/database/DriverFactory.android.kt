package com.bruni.carscan.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.bruni.carscan.db.CarScanDb

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = CarScanDb.Schema,
            context = context,
            name = "carscan.db",
            // onOpen, not once on the driver afterwards: foreign_keys, synchronous and
            // busy_timeout are per-connection, and this driver keeps a pool. See
            // CARSCAN_PRAGMAS.
            callback = object : AndroidSqliteDriver.Callback(CarScanDb.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    for (pragma in CARSCAN_PRAGMAS) db.execSQL(pragma.sql)
                }
            },
        )
}
