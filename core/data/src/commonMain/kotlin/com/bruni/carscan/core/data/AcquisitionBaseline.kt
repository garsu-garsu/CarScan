package com.bruni.carscan.core.data

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The dashboard's current tile signals, exposed without a feature→feature dependency.
 *
 * `AcquisitionController` (in `:composeApp`) needs to know what the dashboard would poll even
 * while the user is nowhere near it — that is the whole point of the acquisition-source setting.
 * `:feature:dashboard` cannot depend on `:composeApp`, and `:composeApp` must not depend on
 * `:feature:dashboard` just to read a list of keys, so this port sits in `:core:data`, the same
 * inward-pointing inversion as [ActiveVehicle] and [VisibleSignals].
 */
interface AcquisitionBaseline {
    val dashboardSignals: StateFlow<Set<MetricKey>>
    fun setDashboardSignals(keys: Set<MetricKey>)
}

class DefaultAcquisitionBaseline : AcquisitionBaseline {
    private val _dashboardSignals = MutableStateFlow<Set<MetricKey>>(emptySet())
    override val dashboardSignals: StateFlow<Set<MetricKey>> = _dashboardSignals.asStateFlow()

    override fun setDashboardSignals(keys: Set<MetricKey>) {
        _dashboardSignals.value = keys
    }
}

/**
 * The six standard J1979 signals a brand-new dashboard is seeded with — see
 * `DashboardViewModel.defaultTiles()` in `:feature:dashboard`, which this must stay in sync with.
 *
 * `AcquisitionController` falls back to this set whenever the selected acquisition source has
 * nothing better to offer yet: an empty dashboard, or the monitoring/HUD sources before their own
 * bookmark/fixed sets land.
 */
val STANDARD_CORE_SIGNALS: Set<MetricKey> = setOf(
    MetricKey.Metric(SuggestedMetric.SPEED),
    MetricKey.Signal("RPM"),
    MetricKey.Metric(SuggestedMetric.ENGINE_COOLANT_TEMPERATURE),
    MetricKey.Metric(SuggestedMetric.ENGINE_LOAD),
    MetricKey.Signal("IAT"),
    MetricKey.Metric(SuggestedMetric.THROTTLE_POSITION),
)
