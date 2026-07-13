package com.bruni.carscan.core.model.obdb

/**
 * The service/PID pair from [ObdbCommand.cmd], resolved once so that no caller has
 * to re-derive it from the raw map.
 *
 * [pidBytes] is the number of PID bytes the ECU echoes back before the payload, and
 * it is the whole reason this class exists: mode 01 echoes `41 <pid>` and mode 22
 * echoes `62 <pid-hi> <pid-lo>`. Strip the wrong count and every `fmt.bix` in the
 * command lands one byte off, which produces plausible-looking wrong numbers rather
 * than an error.
 */
data class CommandSpec(
    /** Request service byte: 0x01, 0x21 or 0x22. */
    val mode: Int,
    /** PID hex as written in the signalset, e.g. "0C" or "E003". */
    val pid: String,
    /** PID bytes echoed in the positive response. */
    val pidBytes: Int,
) {
    /** The positive-response service byte: request mode + 0x40. */
    val responseSid: Int get() = mode + 0x40

    /** What is written to the adapter, e.g. "010C" or "220101". */
    val request: String get() = mode.toString(16).uppercase().padStart(2, '0') + pid
}

/**
 * Resolves [ObdbCommand.cmd] into a [CommandSpec].
 *
 * Throws rather than guessing: an unrecognized service means the signalset uses a
 * mode we have not implemented, and silently treating it as mode 01 would decode
 * garbage with full confidence.
 */
fun ObdbCommand.spec(): CommandSpec {
    val (service, pid) = cmd.entries.singleOrNull()?.toPair()
        ?: error("OBDb command must carry exactly one service->PID entry, got: $cmd")
    val mode = service.toIntOrNull(16)
        ?: error("Unparseable OBD service '$service'")
    val pidBytes = when (mode) {
        0x01, 0x21 -> 1
        0x22 -> 2
        else -> error("Unsupported OBD service 0x${service}; add it deliberately, do not default")
    }
    return CommandSpec(mode = mode, pid = pid.uppercase(), pidBytes = pidBytes)
}

/**
 * Stable identity of a command for persistence and failure memory, e.g. "7E4.220101".
 * Header-qualified because the same PID on two ECUs is two different commands.
 */
fun ObdbCommand.commandId(): String = "$hdr.${spec().request}"

/**
 * True when this command applies to [modelYear]. An absent filter means every year.
 *
 * Ported from OBDb's reference `Filter.matches`
 * (.schemas/python/can/signals.py). Two rules here are counter-intuitive and are
 * the reason this is not written from the field names alone:
 *
 *  - When [from] and [to] are BOTH present and `from >= to`, the range is INVERTED:
 *    it means "at most `to`, or at least `from`" — a hole in the middle, not an
 *    empty set. Ford-F-150 relies on this: `{to: 2003, from: 2011}` selects
 *    2003-and-earlier plus 2011-and-later.
 *  - [years] is OR'd on top of whatever the range decided; it never narrows it.
 */
fun YearFilter?.matches(modelYear: Int): Boolean {
    if (this == null) return true

    val rangeMatches = when {
        from != null && to != null ->
            if (from < to) modelYear in from..to
            else modelYear >= from || modelYear <= to // inverted range: a hole in the middle
        to != null -> modelYear <= to
        from != null -> modelYear >= from
        else -> false
    }

    return rangeMatches || modelYear in years
}
