package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.obd.poll.PollerHealth
import com.bruni.carscan.core.transport.TransportKind

/**
 * Turns a platform exception into something the user can act on.
 *
 * The exceptions worth telling a user about — :core:transport's `Spp*` family, and the bare
 * `SecurityException` Kable throws on a refused `BLUETOOTH_SCAN` — are JVM types declared in
 * `androidMain`. A commonMain ViewModel cannot catch them or even name them. So the
 * classification happens here, at the edge, and what crosses into common code is [ConnectFailure].
 *
 * [kind] is only ever the fallback. A failure we recognise is classified from the exception,
 * because the transport it happened on is a far worse guess than the exception itself.
 */
internal expect fun Throwable.asConnectFailure(kind: TransportKind): ConnectFailure

/**
 * The fallback, shared by every platform: what an *unrecognised* failure on this transport most
 * likely was.
 *
 * Wi-Fi is not lumped in with the rest. Those adapters are their own access point, so a socket
 * that will not open nearly always means the phone is still on some other network — and joining
 * it also kills cellular data, which the user needs to be told. `ADAPTER_UNREACHABLE` would send
 * them to the car to check a cable that is fine.
 */
internal fun TransportKind.unknownFailure(): ConnectFailure = when (this) {
    TransportKind.WIFI -> ConnectFailure.WIFI_NOT_JOINED
    TransportKind.BLE, TransportKind.SPP -> ConnectFailure.ADAPTER_UNREACHABLE
}

/**
 * Why the init ladder gave up, as something a user can do something about.
 *
 * `UNABLE_TO_CONNECT` is nearly always the ignition being off, and that is nearly always the real
 * cause — so it gets its own answer, because "turn the ignition on" is a fix and an ELM error code
 * is not.
 *
 * Note which kinds cannot arrive here at all: `BUFFER_FULL` and `QUESTION_MARK`. `ElmSession`
 * degrades through both — it halves the write chunk, retires the frame-count suffix — and
 * *connects anyway*. Showing a user an error code for a session that worked is worse than showing
 * them nothing.
 */
internal fun ElmErrorKind?.asConnectFailure(): ConnectFailure = when (this) {
    ElmErrorKind.UNABLE_TO_CONNECT -> ConnectFailure.IGNITION_OFF
    else -> ConnectFailure.ADAPTER_UNREACHABLE
}

/**
 * :core:obd's health, as :core:data's.
 *
 * The two mirror each other field for field on purpose: :core:data declares the port and cannot
 * see :core:obd, so this trivial mapping is the price of a repository that does not know an
 * ELM327 exists — and that is what lets the emulator drive the whole UI with no car.
 *
 * [connection] is not in [PollerHealth] and cannot be: the poller has no idea whether anything is
 * connected. It comes from the connector.
 */
internal fun PollerHealth.asSessionHealth(connection: ConnectionState) = SessionHealth(
    connection = connection,
    capacityHz = capacityHz,
    loadHz = loadHz,
    meanRttMs = meanRttMs,
    dropRatePct = dropRatePct,
    stretchedCount = stretchedCount,
)
