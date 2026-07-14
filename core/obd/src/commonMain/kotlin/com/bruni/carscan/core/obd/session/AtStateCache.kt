package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.model.obdb.ObdProtocol
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.obd.ElmResponse
import com.bruni.carscan.core.obd.Exchanger

/** The flow-control triple. All three move together or none of them do. */
data class FcState(
    val header: String,
    val data: String,
    val mode: Int,
)

/**
 * What we believe the adapter's AT registers currently hold.
 *
 * This exists to *not send commands*. An OBDb command carries its addressing with it —
 * header, receive filter, extended address, flow control, timeout — and the obvious
 * implementation re-applies all of it before every request. That is three to five extra
 * round trips per reading. On a clone doing fifteen exchanges a second, spending four of
 * every five on `ATSH 7E0` when the header was already `7E0` cuts the useful throughput
 * to a quarter, and a quarter of fifteen is three: not a dashboard.
 *
 * So [applyContext] sends only what actually changed, and this is the record of what it
 * last sent. It is only ever right if it is updated *after* the adapter confirms — see
 * [applyContext] — and it must be discarded or replayed whenever the adapter resets,
 * because `ATZ`, `ATWS` and a brown-out all wipe the real registers while leaving this
 * object cheerfully intact. `ElmSession` replays it after every recovery for exactly
 * that reason.
 */
class AtStateCache {
    /** `ATSH` — the request CAN id. */
    var header: String? = null

    /** `ATCRA` — the hardware receive filter. Null means "no filter set". */
    var rxFilter: String? = null

    /** `ATCEA` — the ISO-TP extended address. */
    var extAddr: String? = null

    var fc: FcState? = null

    /** `ATST` — the per-command timeout, in ELM units of 4 ms. Null means adaptive (`ATAT1`). */
    var stTimeout: Int? = null

    /**
     * `ATSP` — the protocol number.
     *
     * Seed this from a persisted [AdapterInfo.protocolNum] before `connect()` and the
     * initializer skips the protocol search entirely.
     */
    var protocol: Int? = null

    /** Which UDS diagnostic session each ECU is in, keyed by header. */
    val ecuSession: MutableMap<String, String> = mutableMapOf()

    /** Everything an adapter reset throws away. The capabilities in [AdapterFacts] survive; this does not. */
    fun invalidate() {
        header = null
        rxFilter = null
        extAddr = null
        fc = null
        stTimeout = null
        ecuSession.clear()
    }
}

/** OBDb names protocols; the ELM327 numbers them. */
internal fun ObdProtocol.atNumber(): Int = when (this) {
    ObdProtocol.ISO_9141_2 -> 3
    ObdProtocol.ISO_14230 -> 5
    ObdProtocol.ISO_15765_4_11BIT -> 6
    ObdProtocol.ISO_15765_4_29BIT -> 7
}

/** The flow-control block an ECU is told to use: block size 0, no separation time. */
internal const val FC_DATA = "300000"

/**
 * Put the adapter into the state [cmd] needs, sending only the commands that change something.
 *
 * Ordering is not cosmetic:
 *  - `ATSP` goes first, because switching protocol resets the protocol-specific registers
 *    underneath it — including the header.
 *  - flow control goes *after* `ATSH`, because on many firmwares setting the header resets
 *    the flow-control state. Send `ATFCSH` first and it is silently undone.
 *
 * The cache is updated only when the adapter answers `OK`. A command that failed did not
 * take effect, and recording it as if it had is how the next reading comes back from the
 * wrong ECU — a wrong number, not an error.
 */
suspend fun Exchanger.applyContext(cmd: ObdbCommand, cache: AtStateCache) {
    // Protocol first. Switching it resets the registers underneath it — including the
    // header — so anything set before it would be silently undone.
    cmd.proto?.atNumber()?.let { protocol ->
        if (cache.protocol != protocol && at("ATSP${protocol.toString(16).uppercase()}")) {
            cache.protocol = protocol
        }
    }

    val header = cmd.hdr.uppercase()
    if (cache.header != header && at("ATSH $header")) {
        cache.header = header
        // Many firmwares reset flow control when the header changes. Whatever we believed
        // about it a moment ago is no longer true, and re-sending it is not optional.
        cache.fc = null
    }

    // An absent `rax` is not "leave the old filter alone" — it is "clear it". A stale
    // ATCRA drops every frame of the next command in hardware, and the command just times
    // out with no clue as to why.
    val rxFilter = cmd.rax?.uppercase()
    if (cache.rxFilter != rxFilter) {
        val command = if (rxFilter != null) "ATCRA $rxFilter" else "ATCRA"
        if (at(command)) cache.rxFilter = rxFilter
    }

    val extAddr = cmd.eax?.uppercase()
    if (cache.extAddr != extAddr) {
        val command = if (extAddr != null) "ATCEA $extAddr" else "ATCEA"
        if (at(command)) cache.extAddr = extAddr
    }

    // After ATSH. Never before it.
    if (cmd.fcm1) {
        val wanted = FcState(header, FC_DATA, 1)
        if (cache.fc != wanted && at("ATFCSH $header") && at("ATFCSD $FC_DATA") && at("ATFCSM1")) {
            cache.fc = wanted
        }
    } else if (cache.fc?.mode == 1 && at("ATFCSM0")) {
        cache.fc = cache.fc?.copy(mode = 0)
    }

    val tmo = cmd.tmo
    val timeout = tmo?.toIntOrNull(16)
    if (tmo != null && timeout != null) {
        if (cache.stTimeout != timeout && at("ATAT0") && at("ATST ${tmo.uppercase().padStart(2, '0')}")) {
            cache.stTimeout = timeout
        }
    } else if (cache.stTimeout != null && at("ATAT1")) {
        // Back to adaptive timing, or every command from here on waits out the fixed
        // timeout this one command asked for.
        cache.stTimeout = null
    }

    cmd.din?.let { session ->
        if (cache.ecuSession[header] != session && at("10$session")) {
            cache.ecuSession[header] = session
        }
    }
}

/** True when the adapter accepted the command. */
private suspend fun Exchanger.at(ascii: String): Boolean =
    exchange(ElmRequest(ascii)) is ElmResponse.Ok
