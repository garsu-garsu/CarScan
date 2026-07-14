package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.model.obdb.Fmt
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.model.obdb.ObdbSignal
import com.bruni.carscan.core.transport.fake.ElmClock
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * `runTest`'s virtual clock, wearing a [TimeSource].
 *
 * Without this, `ElmResponse.Ok.roundTrip` is measured against the wall, and the measured
 * adapter RTT — which the poll scheduler budgets its whole frame against — would depend
 * on how loaded the CI box happened to be. Every timing assertion below is exact because
 * of this, and none of them sleeps.
 */
class VirtualTimeSource(private val nowMs: () -> Long) : TimeSource {
    override fun markNow(): TimeMark {
        val start = nowMs()
        return object : TimeMark {
            override fun elapsedNow(): Duration = (nowMs() - start).milliseconds
        }
    }
}

fun TestScope.virtualTime(): TimeSource = VirtualTimeSource { testScheduler.currentTime }

fun TestScope.elmClock(): ElmClock = ElmClock { testScheduler.currentTime }

fun TestScope.sessionConfig(
    onAssert: (String) -> Unit = {},
): ElmSessionConfig = ElmSessionConfig(timeSource = virtualTime(), onAssert = onAssert)

/** A minimal OBDb command — only the addressing fields matter to the session. */
fun obdbCommand(
    hdr: String,
    pid: String,
    mode: String = "01",
    rax: String? = null,
    eax: String? = null,
    fcm1: Boolean = false,
    tmo: String? = null,
    din: String? = null,
) = ObdbCommand(
    hdr = hdr,
    rax = rax,
    eax = eax,
    fcm1 = fcm1,
    tmo = tmo,
    din = din,
    cmd = mapOf(mode to pid),
    freq = 1.0,
    signals = listOf(ObdbSignal(id = "S_$hdr$pid", name = "signal", fmt = Fmt(len = 8))),
)
