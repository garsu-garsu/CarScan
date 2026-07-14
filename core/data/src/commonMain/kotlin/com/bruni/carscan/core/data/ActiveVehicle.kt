package com.bruni.carscan.core.data

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens need to know about the vehicle we are talking to.
 *
 * Declared here and satisfied in :composeApp over :core:obd and :core:vehicle — the same
 * inward-pointing inversion as [SampleSource] and [ObdConnector]. Every screen wanted this
 * independently (a gauge needs a signal's range, the tile picker needs the supported set), and
 * three copies of it in three feature modules is how "km/h on one screen, mph on another" starts.
 */
interface ActiveVehicle {

    /**
     * The effective signalset for the connected vehicle: SAE J1979 ∪ the vehicle's own OEM
     * commands, filtered to its model year. Null when nothing is connected.
     *
     * This is where a signal's *range* comes from — `fmt.min`/`fmt.max` — and a gauge needs that,
     * not the range of whatever values have arrived so far. Autoscaling a tachometer to its
     * observed range makes idle look like redline.
     *
     * `fmt.omin`/`omax`/`oval` are the optimal band and the redline, which is what turns a number
     * into something a driver can read at a glance.
     */
    val signalset: StateFlow<EffectiveSignalset?>

    /**
     * Command ids (`"7E0.0142"`) the poller has given up on after repeated NO_DATA.
     *
     * The tile picker must exclude these. Offering a PID the car cannot answer produces a tile
     * that is stale forever, and the user blames the app rather than the car.
     */
    val unsupported: StateFlow<Set<String>>
}

/**
 * Tells the poller what the user is actually looking at.
 *
 * A counterfeit adapter has ~15 queries per second, in total, for the whole app. If the scheduler
 * does not know which tiles are on screen it spreads that budget evenly — and the six gauges the
 * user is watching crawl while a PID nobody has looked at all week gets refreshed on schedule.
 *
 * So every screen showing live values must call this, and must call it again when the selection
 * changes. Bound in :composeApp to `PidScheduler.setVisible`.
 */
interface VisibleSignals {
    fun setVisible(keys: Set<MetricKey>)
}
