package com.bruni.carscan.feature.hud

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
 * Shapes borrowed from the real signalsets — see `feature/dashboard`'s `Fixtures.kt`. Kept as a
 * local copy rather than a shared import: features never depend on each other, tests included.
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
    map: Map<String, MapEntry> = emptyMap(),
) = ObdbSignal(
    id = id,
    name = name,
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
    vararg signals: ObdbSignal,
) = ObdbCommand(
    hdr = hdr,
    rax = rax,
    cmd = mapOf("01" to pid),
    freq = freq,
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

val SPEED_KEY = HUD_SPEED_KEY
val RPM_KEY = HUD_RPM_KEY
