package com.bruni.carscan.core.designsystem.gauge

import androidx.compose.runtime.Immutable
import com.bruni.carscan.core.units.NumberFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything a gauge decides before it touches a pixel.
 *
 * Pixel output cannot be meaningfully asserted, so every decision that could be wrong lives
 * here as a pure function instead of inside a `DrawScope`: the value→sweep mapping and its
 * clamping, the angle of the needle, where the ticks land, and how far the coloured bands
 * run. The renderers are then thin enough that a smoke test covers them.
 */

/** Shown instead of a number when there is no reading. "NaN" on a dashboard reads as a crash. */
const val NO_READING: String = "--"

private const val DEG_TO_RAD: Float = (PI / 180.0).toFloat()

/**
 * Where [value] sits along the gauge, as a fraction of the sweep in `0f..1f`.
 *
 * Clamped, so a bad decode can never sweep the needle past the end stop or paint arc outside
 * the dial. Degenerate (`min == max`), inverted (`max < min`) and non-finite ranges collapse
 * to `0f` rather than dividing by zero, and a `NaN` value returns `0f` rather than poisoning
 * every coordinate downstream — a `NaN` that reaches `drawArc` silently draws nothing at all,
 * which looks exactly like a working gauge reading zero.
 */
fun valueToFraction(value: Float, min: Float, max: Float): Float {
    if (!min.isFinite() || !max.isFinite()) return 0f
    val span = max - min
    if (span <= 0f) return 0f
    if (value.isNaN()) return 0f
    // Infinity survives the division and coerces onto an end stop, which is what we want.
    return ((value - min) / span).coerceIn(0f, 1f)
}

/** The angle, in degrees, at [fraction] along a sweep of [sweepAngle] that begins at [startAngle]. */
fun fractionToAngle(fraction: Float, startAngle: Float, sweepAngle: Float): Float =
    startAngle + fraction * sweepAngle

/**
 * The position of tick [index] of [count], as a fraction of the sweep.
 *
 * The first tick lands exactly on `0f` and the last exactly on `1f`, so the scale meets both
 * end stops instead of stopping a tick short.
 */
fun tickFraction(index: Int, count: Int): Float =
    if (count <= 1) 0f else index.toFloat() / (count - 1).toFloat()

/** Horizontal offset from the centre of a dial, in the screen's y-grows-down convention. */
fun polarX(angleDegrees: Float, radius: Float): Float = radius * cos(angleDegrees * DEG_TO_RAD)

/** Vertical offset from the centre of a dial, in the screen's y-grows-down convention. */
fun polarY(angleDegrees: Float, radius: Float): Float = radius * sin(angleDegrees * DEG_TO_RAD)

/** A coloured zone on the dial — the optimal band or the redline — in sweep fractions. */
@Immutable
data class GaugeBand(val startFraction: Float, val endFraction: Float)

/**
 * The band `[from, to]` expressed as fractions of the sweep, clamped to the gauge's range, or
 * `null` when there is nothing to draw: either bound absent or non-finite, an inverted or
 * zero-width band, or a band that lies entirely outside the gauge.
 *
 * The redline is asked for as `bandFraction(spec.redlineFrom, spec.max, spec.min, spec.max)`.
 */
fun bandFraction(from: Float?, to: Float?, min: Float, max: Float): GaugeBand? {
    if (from == null || to == null) return null
    if (!from.isFinite() || !to.isFinite()) return null
    if (!min.isFinite() || !max.isFinite() || max - min <= 0f) return null
    if (to <= from) return null

    val start = valueToFraction(from, min, max)
    val end = valueToFraction(to, min, max)
    // Both bounds clamped onto the same end stop: the band is outside the gauge entirely.
    if (end <= start) return null
    return GaugeBand(start, end)
}

/**
 * [value] rounded to [decimals] places and written the way [formatter]'s locale writes it.
 *
 * The rounding is the [formatter]'s — half-up, exactly [decimals] places, no grouping — so
 * `13.8` at two places is still `"13.80"` and `1726.4` at zero places is still `"1726"`. That
 * is the contract [GaugeSpec.decimals] rests on and it did not change; what changed is that
 * the decimal separator is no longer a hard-coded `'.'`, which was wrong for six of the eight
 * shipping locales (ru, de, pl, pt-BR, es, uk all write `13,8`).
 *
 * The [formatter] is passed in rather than looked up, because this is a pure function and the
 * callers are composables that already have [com.bruni.carscan.core.designsystem.theme.LocalNumberFormatter].
 */
fun formatGaugeValue(value: Float, decimals: Int, formatter: NumberFormatter): String {
    if (!value.isFinite()) return NO_READING
    return formatter.format(value.toDouble(), decimals.coerceIn(0, MAX_DECIMALS))
}

private const val MAX_DECIMALS = 3
