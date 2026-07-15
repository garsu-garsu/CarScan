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
                    // query(), not execSQL(): journal_mode=WAL and busy_timeout RETURN a row, and
                    // Android's execSQL refuses any statement that produces a result ("Queries can
                    // be performed using query or rawQuery methods only") — it crashes on the first
                    // real DB open. query() runs both the returning and the set-only pragmas.
                    // Host tests use the JDBC driver, which has no such restriction, so this only
                    // ever surfaced on a real device (found connecting to an adapter).
                    for (pragma in CARSCAN_PRAGMAS) db.query(pragma.sql).close()
                }
            },
        )
}
