package com.bruni.carscan.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * A driver over a throwaway database, with the production PRAGMAs applied.
 *
 * [path] null means in-memory. Pass a real path when the test needs to observe
 * something in-memory SQLite does not have — WAL, for instance, is silently
 * downgraded to `memory` journal mode for a `:memory:` database.
 */
expect fun createTestDriver(path: String? = null): SqlDriver

/** A filesystem path in a temp dir that does not exist yet. */
expect fun tempDbPath(name: String): String

/** Runs a scalar query and returns the value as text. Used to read PRAGMAs back. */
fun SqlDriver.queryScalar(sql: String): String? =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            if (cursor.next().value) QueryResult.Value(cursor.getString(0))
            else QueryResult.Value(null)
        },
        parameters = 0,
    ).value

/** `SELECT count(*)` against an arbitrary table, for "nothing was written" assertions. */
fun SqlDriver.countRows(table: String): Long =
    queryScalar("SELECT count(*) FROM $table;")?.toLong() ?: 0L
