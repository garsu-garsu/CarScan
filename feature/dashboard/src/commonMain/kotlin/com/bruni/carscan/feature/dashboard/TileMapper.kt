package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.designsystem.gauge.GaugeSpec
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.asDoubleOrNull
import com.bruni.carscan.core.units.DefaultUnitConverter
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.units.displayUnitFor
import com.bruni.carscan.core.units.toUnitId
import com.bruni.carscan.core.vehicle.SignalSource
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the vehicle's signalset says about one tile's signal: everything a gauge needs that the
 * user did not choose. Derived at bind time, never persisted — see [DashboardTile].
 */
data class TileBinding(
    val label: String,
    val nativeUnit: ObdUnit?,
    val optimalFrom: Double?,
    val optimalTo: Double?,
    val redlineFrom: Double?,
    /** The command's declared poll period. OBDb's `freq` is SECONDS BETWEEN REQUESTS, not hertz. */
    val periodMs: Long,
) {
    /**
     * How old a reading may get before the tile stops claiming to be live.
     *
     * Derived from the command's own period, never a constant. A fixed two-second window would
     * mark a signal OBDb declares at `freq: 3600` — odometer, traction-battery capacity —
     * permanently stale, because it is *supposed* to be an hour old.
     */
    val stalenessWindowMs: Long
        get() = maxOf(MIN_STALENESS_WINDOW_MS, periodMs * STALENESS_PERIODS)
}

/** Below this, jitter in a fast poll would flicker the tile in and out of staleness. */
const val MIN_STALENESS_WINDOW_MS: Long = 1_500

/** Miss this many polls in a row and the reading is no longer live. */
const val STALENESS_PERIODS: Long = 3

fun bind(source: SignalSource): TileBinding {
    val fmt = source.signal.fmt
    return TileBinding(
        label = source.signal.name,
        nativeUnit = fmt.unit,
        // omin/omax/oval are not decoder inputs — they are the green zone and the redline, and
        // this is the only place they are read.
        optimalFrom = fmt.omin,
        optimalTo = fmt.omax,
        redlineFrom = fmt.oval,
        periodMs = (source.command.command.freq * 1000).roundToLong().coerceAtLeast(1),
    )
}

/**
 * One tile, ready to draw: the gauge, plus the units its numbers are now expressed in.
 *
 * The units ride along because [GaugeSpec.unitLabel] must be a *localized* string, and only a
 * composable can resolve one. The mapper decides **which** unit the tile is in — that is the
 * decision worth testing — and the screen turns that decision into a word.
 */
data class RenderedTile(
    val spec: GaugeSpec,
    /** What the car reports this signal in. */
    val nativeUnit: ObdUnit?,
    /** What [spec] has been converted to, or null when the app offers no choice for this quantity. */
    val displayUnit: UnitId?,
)

/**
 * The state mapper, and **the only place in the app where a value stops being what the car said
 * and becomes what the user reads.**
 *
 * Samples travel and are stored natively — km/h, celsius, kilopascal — each carrying its own
 * [ObdUnit]. Conversion happens here: not in the poller, not in the ring buffer, not on the way to
 * disk. A second conversion site is how an app ends up showing km/h on the dashboard and mph on
 * the live chart, and how a display preference retroactively corrupts a recorded trip.
 *
 * Conversion is delegated whole to `:core:units`. Nothing here knows that a mile is 1.609344 km,
 * that the imperial gallon is not the US one, or that temperature is affine —
 * [DefaultUnitConverter] knows, once. Every value converted here is an **absolute** reading, so
 * every one goes through `convert`; a *difference* would need `convertDelta`, and a gauge never
 * draws one.
 *
 * The converted number is deliberately **not** formatted here. A gauge needs a `Float` — the
 * needle's geometry is computed from it — and `:core:designsystem` renders the numerals through
 * `LocalNumberFormatter`, so the six locales that write `13,8` get their comma without this module
 * touching a string. (`UnitReadout` is the right call for anything that draws a converted number as
 * *text*. A gauge does not: it draws an angle.)
 *
 * [GaugeSpec.isStale] is the other half of the job. A tachometer resting at 0 rpm and one that has
 * never received a value are the same picture, and users read the first as "engine off" rather than
 * "the app is broken". A tile is stale when no sample has arrived, when the newest is older than
 * [TileBinding.stalenessWindowMs], and — the case that is *invisible* from here — when the ECU is
 * answering with an OBDb `nullmin`/`nullmax` sentinel. `SignalDecoder` returns null for a sentinel
 * and `PollDecoder` emits **no sample at all**, so an unplugged sensor is indistinguishable from
 * silence and the last good reading sits in `latest` forever. Only the age check catches it.
 *
 * A stale tile keeps its last reading on the dial. Snapping to zero would read as "the car
 * stopped" — a different and far more alarming lie than "no reading" — and the renderer already
 * dims the tile and prints `--` in place of the numerals.
 *
 * [nowMs] and [SensorSample.timestampMs] are both **wall time**. See [DashboardClock]: comparing a
 * sample against a monotonic clock is the seam that was silently discarding every recorded trip.
 */
fun resolve(
    tile: DashboardTile,
    binding: TileBinding?,
    sample: SensorSample?,
    nowMs: Long,
    units: UnitPreferences,
): RenderedTile {
    val native = binding?.nativeUnit
    val nativeId = native?.toUnitId()
    val displayId = native?.let { displayUnitFor(it, units) }

    // A unit OBDb declares that the app offers no choice in — rpm, percent, volts, g/s — has no
    // UnitId at all, and is shown exactly as decoded. That is the honest answer: a made-up
    // conversion is indistinguishable from a real one once it is on a gauge.
    fun Double.display(): Double =
        if (nativeId != null && displayId != null) {
            DefaultUnitConverter.convert(this, nativeId, displayId)
        } else {
            this
        }

    val window = binding?.stalenessWindowMs ?: MIN_STALENESS_WINDOW_MS
    // Text (a VIN, an ECU name) has no numeric value and cannot be gauged. Treating it as zero
    // would draw a needle for it.
    val reading = sample?.value?.asDoubleOrNull

    // The end stops move with the needle. Converting the value but not the range draws 62 mph
    // against a 0–240 dial and reports a car at motorway speed as barely moving.
    val min = tile.min.display()
    val max = tile.max.display()

    return RenderedTile(
        spec = GaugeSpec(
            label = binding?.label ?: tile.key.fallbackLabel(),
            value = (reading?.display() ?: 0.0).toFloat(),
            min = min.toFloat(),
            max = max.toFloat(),
            // Filled in by the screen: only a composable can resolve a localized symbol.
            unitLabel = "",
            decimals = decimalsFor(min, max),
            // All six numbers on the dial — value, both end stops, both edges of the green band,
            // and the redline — convert, or none of them may. ClassicAnalogGauge prints its tick
            // labels from min + fraction * span, so a partial conversion is a needle at the wrong
            // angle on a dial labelled in the wrong unit, with a correct number in the middle.
            optimalFrom = binding?.optimalFrom?.display()?.toFloat(),
            optimalTo = binding?.optimalTo?.display()?.toFloat(),
            redlineFrom = binding?.redlineFrom?.display()?.toFloat(),
            isStale = sample == null ||
                reading == null ||
                nowMs - sample.timestampMs > window,
        ),
        nativeUnit = native,
        displayUnit = displayId,
    )
}

/**
 * Precision from the span of the dial, because that is what decides whether a digit carries
 * information: one decimal place on a 0–8000 rpm tachometer is noise, and none at all on a
 * 0.7–1.3 lambda gauge leaves three distinct values.
 */
private fun decimalsFor(min: Double, max: Double): Int {
    val span = abs(max - min)
    return when {
        span >= 50.0 -> 0
        span >= 5.0 -> 1
        else -> 2
    }
}

/** A tile for a signal this vehicle does not have still needs a name on it. */
private fun MetricKey.fallbackLabel(): String = when (this) {
    is MetricKey.Signal -> signalId
    is MetricKey.Metric -> metric.name
}
