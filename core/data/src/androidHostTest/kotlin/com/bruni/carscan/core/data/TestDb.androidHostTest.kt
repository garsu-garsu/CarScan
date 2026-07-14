package com.bruni.carscan.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bruni.carscan.core.database.CARSCAN_PRAGMAS
import com.bruni.carscan.db.CarScanDb
import java.util.Properties

/**
 * PRAGMAs as connection *properties*: JdbcSqliteDriver opens a fresh connection for
 * every statement run outside a transaction, so a PRAGMA executed against the driver
 * configures a connection that is closed moments later. See CARSCAN_PRAGMAS.
 */
actual fun createTestDriver(path: String?): SqlDriver {
    val properties = Properties().apply {
        for (pragma in CARSCAN_PRAGMAS) setProperty(pragma.name, pragma.value)
    }
    val driver = JdbcSqliteDriver(
        url = if (path == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path",
        properties = properties,
    )
    CarScanDb.Schema.create(driver)
    return driver
}
