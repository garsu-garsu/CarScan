package com.bruni.carscan.core.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * Counts transactions and statements on the way to a real driver.
 *
 * The throughput requirement is not "the writes finished" — a writer that opens one
 * transaction per sample also finishes, just far slower and with 200x the fsync
 * traffic, and it would pass any test that only checked the rows landed. So the
 * assertion has to be on the transaction count itself, which means counting them.
 *
 * Not thread-safe, and does not need to be: every test drives it from a single
 * `runTest` scheduler thread.
 */
class CountingSqlDriver(private val delegate: SqlDriver) : SqlDriver {

    /** Number of times a transaction was opened. */
    var transactions: Int = 0
        private set

    /** Number of INSERT/UPDATE/DELETE statements executed. */
    var executes: Int = 0
        private set

    fun reset() {
        transactions = 0
        executes = 0
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        transactions++
        return delegate.newTransaction()
    }

    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        executes++
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        delegate.addListener(queryKeys = queryKeys, listener = listener)
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        delegate.removeListener(queryKeys = queryKeys, listener = listener)
    }

    override fun notifyListeners(vararg queryKeys: String) {
        delegate.notifyListeners(queryKeys = queryKeys)
    }

    override fun close() {
        delegate.close()
    }
}
