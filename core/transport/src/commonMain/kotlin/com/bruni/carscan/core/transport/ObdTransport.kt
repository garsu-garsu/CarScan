package com.bruni.carscan.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * A raw byte pipe to an ELM327 adapter.
 *
 * [incoming] is chunked arbitrarily by the underlying stack: one chunk is NOT
 * one line. Framing is deliberately left to the layer above.
 */
interface ObdTransport {
    suspend fun open()
    val incoming: Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
    suspend fun close()
}

enum class TransportKind { BLE, SPP, WIFI }

/** iOS supports BLE and WIFI only: Apple blocks SPP for non-MFi devices. */
interface TransportFactory {
    val supported: Set<TransportKind>
}
