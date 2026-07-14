package com.bruni.carscan.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bruni.carscan.db.CarScanDb
import java.nio.file.Files
import java.util.Properties

/**
 * The PRAGMAs go in as connection *properties*, not as statements run against the
 * driver after it is built. JdbcSqliteDriver opens a fresh connection for every
 * statement executed outside a transaction, so a PRAGMA executed on the driver
 * configures a connection that is closed moments later. As properties, they are
 * applied by SQLite to every connection at open — which is the same guarantee
 * AndroidSqliteDriver.Callback.onOpen gives in production.
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

actual fun tempDbPath(name: String): String {
    val dir = Files.createTempDirectory("carscan-test")
    dir.toFile().deleteOnExit()
    val file = dir.resolve(name).toFile()
    file.deleteOnExit()
    return file.absolutePath
}
