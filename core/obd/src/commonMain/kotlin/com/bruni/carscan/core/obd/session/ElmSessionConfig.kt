package com.bruni.carscan.core.obd.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The timings the session runs on, and where it gets "now" from.
 *
 * [timeSource] is a parameter rather than a call to a real clock for the same reason the
 * emulator takes an `ElmClock`: every test here runs on `runTest`'s virtual time, and a
 * wall clock would make `ElmResponse.Ok.roundTrip` — and therefore the measured adapter
 * RTT the scheduler budgets against — depend on how fast the CI machine happens to be.
 */
class ElmSessionConfig(
    /**
     * How long to read-and-discard when the stream is untrustworthy.
     *
     * After a timeout the adapter may still be mid-sentence. Writing the next command
     * into that produces `STOPPED` at best, and at worst the late answer arrives while
     * we are waiting for the new one and every response from then on is off by one.
     */
    val drainWindow: Duration = 200.milliseconds,

    /** `ATZ` and `ATWS` are hardware resets; a clone can take seconds. */
    val resetTimeout: Duration = 5.seconds,

    /** And it is still booting when it answers. Write into that and the command is simply lost. */
    val resetSettle: Duration = 1.seconds,

    /** A protocol search on a cold bus walks every protocol in turn. */
    val searchTimeout: Duration = 10.seconds,

    val timeSource: TimeSource = TimeSource.Monotonic,

    /**
     * Called when the session catches itself in a state that should be unreachable.
     *
     * Today that is exactly one thing: `STOPPED`, which means the adapter received a byte
     * while it was still answering — i.e. something wrote outside the mutex. There is no
     * recovery worth writing for a bug of ours; there is only finding out about it.
     */
    val onAssert: (String) -> Unit = {},
)
