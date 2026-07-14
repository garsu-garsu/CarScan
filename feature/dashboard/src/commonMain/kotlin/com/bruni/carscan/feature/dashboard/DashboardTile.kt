package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.model.MetricKey

/**
 * One tile, as the user configured it and as it is persisted.
 *
 * This is the *whole* of what survives a restart. Everything else a gauge needs — the label,
 * the native unit, the optimal band, how often the command is polled — is derived from the
 * vehicle's signalset at bind time, never stored. Storing it would freeze a copy of OBDb data
 * into the user's layout: the day the signalset is updated, or the user moves the layout to a
 * different car, the tile would keep rendering the old vehicle's units and redline.
 *
 * [min] and [max] are in the signal's **native** unit, for the same reason. A layout saved with
 * an mph range and then read with a km/h preference would otherwise show a 240 km/h car pinned
 * at the end stop.
 */
data class DashboardTile(
    val id: String,
    val key: MetricKey,
    val style: GaugeStyleId,
    val min: Double,
    val max: Double,
)
