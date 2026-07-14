package com.bruni.carscan.core.database

import app.cash.sqldelight.db.SqlDriver

/** A PRAGMA and the value it must hold, e.g. `foreign_keys` = `true`. */
data class Pragma(val name: String, val value: String)

/**
 * The connection configuration the app runs on.
 *
 * **These are per-CONNECTION settings, not per-database**, and that distinction is
 * why this is data rather than a one-off `execute` at startup. Every SQLDelight
 * driver hands out more than one connection: the JDBC driver opens a *fresh
 * connection for each statement* run outside a transaction, and AndroidSqliteDriver
 * keeps a pool. Running these once against whichever connection answered first
 * configures a connection that is then discarded, and leaves the ones that do the
 * real work sitting at their defaults.
 *
 * The trap is that it looks like it worked. `journal_mode` is a persistent property
 * of the database *file*, so WAL survives across connections and reads back
 * correctly — while `synchronous` and `foreign_keys` silently revert. A schema full
 * of REFERENCES clauses that are never enforced is indistinguishable from one that
 * is, right up until a deleted vehicle leaves its trips behind as unreachable rows
 * that consume storage forever.
 *
 * So each platform applies this list where SQLite actually wants it: Android in
 * `AndroidSqliteDriver.Callback.onOpen`, JDBC as connection properties.
 *
 * - `journal_mode=WAL` — the writer commits once a second while the UI reads the
 *   same database. Under the default rollback journal the writer blocks readers.
 * - `synchronous=NORMAL` — with WAL this is durable against process death, which is
 *   what actually happens (Android kills the app). It gives up only on power loss.
 *   FULL would fsync every one-second commit for a guarantee we cannot use.
 * - `busy_timeout=3000` — less than this and a GC pause becomes SQLITE_BUSY.
 * - `cache_size=-8000` — negative means KiB, so 8 MB: enough to keep a whole trip's
 *   chunks resident while playback scrubs the timeline.
 *
 * `foreign_keys` is `true` and not `ON` so that the one literal is valid both as
 * PRAGMA syntax and as a JDBC connection property, which accepts only `true`/`false`.
 */
val CARSCAN_PRAGMAS: List<Pragma> = listOf(
    Pragma("journal_mode", "WAL"),
    Pragma("synchronous", "NORMAL"),
    Pragma("foreign_keys", "true"),
    Pragma("busy_timeout", "3000"),
    Pragma("cache_size", "-8000"),
    Pragma("temp_store", "MEMORY"),
)

val Pragma.sql: String get() = "PRAGMA $name=$value;"

/**
 * Executes the PRAGMAs against [driver].
 *
 * Correct only for a driver that holds a single connection. Do not use it for a
 * pooled one — see [CARSCAN_PRAGMAS].
 */
fun applyCarScanPragmas(driver: SqlDriver) {
    for (pragma in CARSCAN_PRAGMAS) {
        driver.execute(identifier = null, sql = pragma.sql, parameters = 0)
    }
}
