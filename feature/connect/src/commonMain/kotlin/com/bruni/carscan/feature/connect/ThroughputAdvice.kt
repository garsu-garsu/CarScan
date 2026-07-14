package com.bruni.carscan.feature.connect

import kotlin.math.floor

/**
 * What this adapter can actually fund, in the only units a user can act on.
 *
 * Reads as *"your adapter: 14 queries/sec — 6 tiles at 2 Hz"*. A user who is told that
 * buys a better adapter. A user who is told nothing watches a dashboard stutter, decides
 * the app is broken, and leaves a one-star review — so the embarrassing number is the
 * product, not a debug readout.
 */
data class ThroughputAdvice(
    /** Round trips per second this adapter measurably manages. Never more than it managed. */
    val queriesPerSec: Int,
    val tiles: Int,
    val hz: Int,
)

/** The default dashboard. Tiles are dropped below this only when the adapter cannot fund them. */
private const val DEFAULT_TILES = 6

/**
 * The rates the dashboard offers, fastest first.
 *
 * It stops at 10 Hz because no gauge redraws faster and no human sees it. A 100 q/s STN
 * could fund 16 Hz; offering it would spend the adapter's headroom on frames nobody looks at.
 */
private val RATES = intArrayOf(10, 5, 2, 1)

/**
 * The largest honest budget [capacityHz] can pay for.
 *
 * Null when there is no budget to state — which covers both "no round trip has returned yet"
 * (capacity is 0 until one does) and "this adapter cannot fund a single tile at 1 Hz". Both
 * mean *say nothing*, because "0 queries/sec" reads as a broken adapter and would be shown
 * to every user on every connect, in the second before the first measurement lands.
 */
fun adviseThroughput(capacityHz: Double, desiredTiles: Int = DEFAULT_TILES): ThroughputAdvice? {
    // Floor, never round. 14.9 measured is 14 promised: a budget built on a capacity the
    // adapter has not demonstrated overcommits by design, and it overcommits forever.
    val queries = floor(capacityHz).toInt()
    if (queries < 1) return null

    val tiles = minOf(desiredTiles, queries)
    // tiles <= queries, so the 1 Hz rung always fits and the fallback is unreachable.
    val hz = RATES.firstOrNull { tiles * it <= queries } ?: 1
    return ThroughputAdvice(queriesPerSec = queries, tiles = tiles, hz = hz)
}
