package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.obdb.Fmt
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.model.obdb.ObdbSignal
import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.obd.ElmResponse
import com.bruni.carscan.core.obd.Exchanger
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * An [Exchanger] that is not a lookup table: it costs time, it is strictly one command at a
 * time (the caller's own coroutine is what serializes it, because there is only one), and it
 * can lie the way a clone lies — `NO DATA`, `?` to a frame-count suffix, a five-second stall.
 *
 * [obdLatencyMs] is the throughput ceiling. A cheap adapter that manages 15 queries per
 * second is one whose every OBD round trip takes 66 ms, and that is exactly how it is
 * modelled here: no separate rate limiter, just the latency that causes the limit.
 */
class FakeExchanger(
    var obdLatencyMs: Long = 25,
    var atLatencyMs: Long = 1,
) : Exchanger {

    val requests = mutableListOf<ElmRequest>()

    /** OBD ascii (e.g. "010C") -> the lines the adapter answers with. Absent ⇒ `NO DATA`. */
    val replies = mutableMapOf<String, List<String>>()

    /** OBD ascii -> faults handed out in order, ahead of [replies]. */
    val faults = mutableMapOf<String, MutableList<ElmErrorKind>>()

    /** A clone that never learnt `010C1` and says `?` to it. */
    var rejectsExpectedFrames = false

    /** Runs before the latency is spent — the seam a stall is injected through. */
    var beforeExchange: (suspend (ElmRequest) -> Unit)? = null

    val asciiSent: List<String> get() = requests.map { it.ascii }
    val obdRequests: List<ElmRequest> get() = requests.filterNot { it.ascii.startsWith("AT") }

    fun countOf(ascii: String): Int = requests.count { it.ascii == ascii }

    override suspend fun exchange(request: ElmRequest): ElmResponse {
        requests += request
        beforeExchange?.invoke(request)

        val isAt = request.ascii.startsWith("AT")
        val latency = if (isAt) atLatencyMs else obdLatencyMs
        delay(latency)
        val rtt = latency.milliseconds

        if (isAt) return ElmResponse.Ok(listOf("OK"), rtt)

        if (request.expectedFrames != null && rejectsExpectedFrames) {
            return ElmResponse.Err(ElmErrorKind.QUESTION_MARK, "?")
        }

        faults[request.ascii]?.removeFirstOrNull()?.let { kind ->
            return ElmResponse.Err(kind, kind.name)
        }

        val lines = replies[request.ascii]
            ?: return ElmResponse.Err(ElmErrorKind.NO_DATA, "NO DATA")
        return ElmResponse.Ok(lines, rtt)
    }
}

// --- OBDb command builders -------------------------------------------------------------

fun signal(
    id: String,
    unit: ObdUnit = ObdUnit.RPM,
    len: Int = 16,
    bix: Int = 0,
    mul: Double = 1.0,
    div: Double = 1.0,
    add: Double = 0.0,
    metric: SuggestedMetric? = null,
) = ObdbSignal(
    id = id,
    name = id,
    suggestedMetric = metric,
    fmt = Fmt(bix = bix, len = len, mul = mul, div = div, add = add, unit = unit),
)

fun command(
    hdr: String = "7E0",
    rax: String? = "7E8",
    mode: String = "01",
    pid: String = "0C",
    freq: Double = 1.0,
    dbg: Boolean = false,
    din: String? = null,
    dout: String? = null,
    signals: List<ObdbSignal> = listOf(signal("RPM", ObdUnit.RPM, len = 16, div = 4.0)),
) = ObdbCommand(
    hdr = hdr,
    rax = rax,
    din = din,
    dout = dout,
    dbg = dbg,
    cmd = mapOf(mode to pid),
    freq = freq,
    signals = signals,
)

/** `7E8 04 41 0C 1A F8` — a single-frame RPM answer. PCI 04: four payload bytes, not three. */
const val RPM_FRAME = "7E8 04 41 0C 1A F8"

/** `7E8 03 41 0D 50` — 80 km/h. */
const val SPEED_FRAME = "7E8 03 41 0D 50"
