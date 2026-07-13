package com.bruni.carscan.core.transport.fake

/**
 * The ways an ELM327 says no.
 *
 * These arrive on the same wire, in the same place, as a hex payload, which is why the
 * framer must recognise them *before* it tries to parse hex — `BUS BUSY` is not a number.
 * Cheap clones emit them constantly, so every one of them is reproducible here.
 */
enum class ElmFault(val wire: String) {
    /** The ECU did not answer in time. By far the most common: an unsupported PID. */
    NO_DATA("NO DATA"),

    /** Bus-level failure — wrong protocol, ignition off, wiring. */
    CAN_ERROR("CAN ERROR"),

    /** Another node is holding the bus. Transient; retry. */
    BUS_BUSY("BUS BUSY"),

    /** The adapter aborted the request, usually because a byte arrived mid-exchange. */
    STOPPED("STOPPED"),

    /** The adapter's own RX buffer overflowed. A clone under load does this. */
    BUFFER_FULL("BUFFER FULL"),

    /** The command was not understood. Also what a clone says to `010C1`. */
    UNKNOWN_COMMAND("?"),

    /** No protocol could be negotiated. */
    UNABLE_TO_CONNECT("UNABLE TO CONNECT"),

    /** Supply voltage dipped and the adapter reset itself. The AT state is now gone. */
    LV_RESET("LV RESET"),
}
