package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.obdb.Fmt
import com.bruni.carscan.core.model.obdb.MapEntry
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.model.obdb.ObdbSignal
import com.bruni.carscan.core.model.obdb.Signalset

/**
 * Shapes borrowed from the real signalsets: `VSS` is SAE J1979's speed PID (mode 01, `0D`,
 * km/h, tagged `speed`), `RPM` is `0C` — the gauge that has no `suggestedMetric` and is the
 * reason tiles are keyed on [MetricKey] at all.
 */
fun signal(
    id: String,
    name: String = id,
    metric: SuggestedMetric? = null,
    unit: ObdUnit? = null,
    min: Double = 0.0,
    max: Double? = null,
    omin: Double? = null,
    omax: Double? = null,
    oval: Double? = null,
    hidden: Boolean = false,
    map: Map<String, MapEntry> = emptyMap(),
) = ObdbSignal(
    id = id,
    name = name,
    hidden = hidden,
    suggestedMetric = metric,
    fmt = Fmt(
        len = 8,
        unit = unit,
        min = min,
        max = max,
        omin = omin,
        omax = omax,
        oval = oval,
        map = map,
    ),
)

fun command(
    hdr: String = "7E0",
    rax: String? = "7E8",
    pid: String = "0D",
    freq: Double = 0.25,
    dbg: Boolean = false,
    vararg signals: ObdbSignal,
) = ObdbCommand(
    hdr = hdr,
    rax = rax,
    cmd = mapOf("01" to pid),
    freq = freq,
    dbg = dbg,
    signals = signals.toList(),
)

fun signalset(vararg commands: ObdbCommand) = Signalset(commands = commands.toList())

fun sample(
    key: MetricKey,
    value: Double,
    unit: ObdUnit?,
    signalId: String = "SIG",
    timestampMs: Long = 0,
) = SensorSample(
    signalId = signalId,
    key = key,
    value = DecodedValue.Numeric(value),
    unit = unit,
    timestampMs = timestampMs,
)

val SPEED_KEY = MetricKey.Metric(SuggestedMetric.SPEED)
val RPM_KEY = MetricKey.Signal("RPM")
